package com.dune

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

class IndiaSocialBook : MainAPI() {
    override var mainUrl = "https://indiasocialbook.com/videos"
    override var name = "IndiaSocialBook"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home / Recent",
        "$mainUrl/blog/" to "Blog",
        "$mainUrl/categories/" to "Categories",
        "$mainUrl/tags/" to "Tags",
        "$mainUrl/actors/" to "Actors"
    )

    // ==================== MAIN PAGE ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        
        val results = document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, results, true), true)
    }

    // ==================== SEARCH ====================
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = query.replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$formattedQuery" else "$mainUrl/page/$page/?s=$formattedQuery"
        val document = app.get(url).document
        
        val results = document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, true)
    }

    // ==================== QUICK SEARCH ====================
    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val formattedQuery = query.replace(" ", "+")
        val url = "$mainUrl/?s=$formattedQuery"
        val document = app.get(url).document
        return document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, .thumb-block a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        
        val img = this.selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: img?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ==================== LOAD (only videos) ====================
    override suspend fun load(url: String): LoadResponse? {
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        
        val tags = document.select("span.tags-links a, .tagcloud a").map { it.text() }
        val actors = document.select("span.actor-links a, .template-actors a").map { Actor(it.text()) }
        val recommendations = document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }

        val contentArea = document.selectFirst("div.entry-content, article.post, div.video-player") ?: document
        val tabNavs = contentArea.select("ul.tab-nav li")
        
        val validIframes = contentArea.select("iframe, embed").filter {
            val src = it.attr("src")
            !src.isBlank() && src != "about:blank" && !src.contains("googlesyndication")
        }

        val episodes = mutableListOf<Episode>()

        if (tabNavs.isNotEmpty()) {
            tabNavs.mapIndexed { index, el ->
                val tabName = el.text().trim().ifEmpty { "Part ${index + 1}" }
                val targetTabId = el.attr("data-tab")
                val iframeSrc = if (targetTabId.isNotBlank()) {
                    contentArea.selectFirst("div.tab-content div#$targetTabId iframe, div#$targetTabId iframe")?.attr("src")
                } else null
                
                val resolvedIframe = iframeSrc ?: contentArea.select("div.tab-pane iframe, iframe").getOrNull(index)?.attr("src")
                val episodeData = if (!resolvedIframe.isNullOrBlank()) fixUrl(resolvedIframe) else "$url#tab_$index"

                newEpisode(episodeData) {
                    this.name = tabName
                    this.episode = index + 1
                }
            }.let { episodes.addAll(it) }
        } else if (validIframes.isNotEmpty()) {
            validIframes.mapIndexed { index, iframe ->
                val iframeSrc = iframe.attr("src")
                val episodeData = if (iframeSrc.isBlank()) fixUrl(iframeSrc) else "$url#iframe_$index"
                newEpisode(episodeData) {
                    this.name = if (validIframes.size > 1) "Part ${index + 1}" else title
                    this.episode = index + 1
                }
            }.let { episodes.addAll(it) }
        } else {
            episodes.add(newEpisode(url) { this.name = title; this.episode = 1 })
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    // ==================== LOAD LINKS (no actors) ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36", "Referer" to mainUrl)
        var foundLinks = false

        val targetUrl = data.substringBefore("#")
        if (targetUrl.isNotBlank() && targetUrl.startsWith("http")) {
            try {
                val response = app.get(targetUrl, headers = headers)
                val doc = response.document

                // Base64 for player-x.php
                if (data.contains("player-x.php?q=")) {
                    try {
                        val base64Query = data.substringAfter("q=").substringBefore("&")
                        val decodedBytes = Base64.decode(base64Query, Base64.DEFAULT)
                        val decoded = String(decodedBytes, Charsets.UTF_8)
                        val unescaped = withContext(Dispatchers.IO) {
                            URLDecoder.decode(decoded, "UTF-8")
                        }
                        val targets = listOf(decoded, unescaped)
                        
                        targets.forEach { target ->
                            if (extractVideoSources(target, targetUrl, callback)) foundLinks = true
                        }
                    } catch (_: Exception) {}
                }

                // Regular iframes
                doc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (!src.isBlank() && !src.contains("googlesyndication")) {
                        if (extractVideoSources(fixUrl(src), targetUrl, callback)) foundLinks = true
                    }
                }

                // Direct HTML scan
                if (extractVideoSources(doc.html(), targetUrl, callback)) foundLinks = true

            } catch (_: Exception) {}
        }

        return foundLinks
    }

    private fun extractVideoSources(input: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        val regex = "(?:src=[\"']|https?://)[^\"'\\s]+\\.(?:mp4|m3u8|webm|mov)(?:\\?[^\"'\\s]*)?".toRegex(RegexOption.IGNORE_CASE)

        regex.findAll(input).forEach { match ->
            var url = match.value
            if (url.startsWith("src=")) url = url.substringAfter("src=").trim('"', '\'')

            if (!url.contains("googlesyndication") && !url.contains("magsrv") && !url.contains("ad-provider")) {
                val videoUrl = fixUrl(url)
                val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = type
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        return found
    }
}