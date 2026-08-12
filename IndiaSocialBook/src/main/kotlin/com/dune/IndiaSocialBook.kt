package com.dune

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        
        val results = document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, results, true), true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = query.replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$formattedQuery" else "$mainUrl/page/$page/?s=$formattedQuery"
        val document = app.get(url).document
        
        val results = document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, true)
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

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val formattedQuery = query.replace(" ", "+")
        val url = "$mainUrl/?s=$formattedQuery"
        val document = app.get(url).document
        return document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        
        val tags = document.select("span.tags-links a, .tagcloud a").map { it.text() }
        val actors = document.select("span.actor-links a, .template-actors a").map { Actor(it.text()) }
        val recommendations = document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }

        // Parse tab items from .video-tabs ul.tab-nav li
        val tabNavs = document.select("div.video-tabs ul.tab-nav li, ul.tab-nav li")
        
        val episodes = if (tabNavs.isNotEmpty()) {
            tabNavs.mapIndexed { index, el ->
                val tabName = el.text().trim().ifEmpty { "Part ${index + 1}" }
                val targetTabId = el.attr("data-tab")
                
                // Locate the matching iframe inside the corresponding tab pane
                val iframeSrc = document.selectFirst("div.tab-content div#$targetTabId iframe, div#$targetTabId iframe")?.attr("src")
                    ?: document.select("div.tab-pane iframe, iframe").getOrNull(index)?.attr("src")

                val episodeData = if (!iframeSrc.isNullOrBlank()) fixUrl(iframeSrc) else "$url#tab_$index"

                newEpisode(episodeData) {
                    this.name = tabName
                    this.episode = index + 1
                }
            }
        } else {
            val fallbackIframes = document.select("div.video-tabs div.tab-pane iframe, iframe")
            if (fallbackIframes.size > 1) {
                fallbackIframes.mapIndexed { index, iframe ->
                    val iframeSrc = iframe.attr("src")
                    val episodeData = if (!iframeSrc.isNullOrBlank()) fixUrl(iframeSrc) else "$url#tab_$index"
                    newEpisode(episodeData) {
                        this.name = "Part ${index + 1}"
                        this.episode = index + 1
                    }
                }
            } else {
                listOf(
                    newEpisode(url) {
                        this.name = title
                        this.episode = 1
                    }
                )
            }
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to mainUrl
        )
        var foundLinks = false

        // `data` directly holds the exact iframe URL passed from each episode/tab
        val iframeUrl = if (data.startsWith("http")) data else {
            val baseUrl = data.substringBefore("#")
            val tabIndexStr = data.substringAfter("#tab_", "").toIntOrNull() ?: 0
            try {
                val doc = app.get(baseUrl, headers = headers).document
                doc.select("div.tab-pane iframe, iframe").getOrNull(tabIndexStr)?.attr("src")?.let { fixUrl(it) } ?: ""
            } catch (_: Exception) { "" }
        }

        // Decode the clean-tube-player plugin base64 query string parameter containing the actual video sources
        if (iframeUrl.isNotBlank() && iframeUrl.contains("player-x.php?q=")) {
            try {
                val base64Query = iframeUrl.substringAfter("q=").substringBefore("&")
                val decodedBytes = Base64.decode(base64Query, Base64.DEFAULT)
                val decodedString = String(decodedBytes, Charsets.UTF_8)
                
                val srcRegex = "src=[\"'](https?://[^\"']+)[\"']".toRegex(RegexOption.IGNORE_CASE)
                for (match in srcRegex.findAll(decodedString)) {
                    val videoUrl = match.groups[1]?.value ?: continue
                    val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = videoUrl,
                            type = type
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            } catch (_: Exception) {}
        }

        if (!foundLinks && iframeUrl.isNotBlank()) {
            if (loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)) {
                foundLinks = true
            }
        }

        // Final fallback backup sweep if specific iframe decoding didn't yield links
        if (!foundLinks) {
            val baseUrl = data.substringBefore("#")
            try {
                val doc = app.get(baseUrl, headers = headers).document
                doc.select("video source, video, source").forEach { element ->
                    val src = element.attr("src")
                        .takeIf { !it.isNullOrBlank() && it != "about:blank" }
                        ?: element.attr("data-src")

                    if (!src.isNullOrBlank() && !src.startsWith("data:")) {
                        val videoUrl = fixUrl(src)
                        val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback.invoke(
                            newExtractorLink(
                                name = name,
                                source = name,
                                url = videoUrl,
                                type = type
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                }
            } catch (_: Exception) {}
        }

        return foundLinks
    }
}