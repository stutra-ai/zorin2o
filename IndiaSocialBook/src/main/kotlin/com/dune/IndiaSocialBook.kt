package com.dune

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
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
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        
        val tags = document.select("span.tags-links a, .tagcloud a").map { it.text() }
        val actors = document.select("span.actor-links a, .template-actors a").map { Actor(it.text()) }
        val recommendations = document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }

        val contentArea = document.selectFirst("div.entry-content, article.post") ?: document
        val tabNavs = contentArea.select("div.video-tabs ul.tab-nav li, ul.tab-nav li")
        
        val validIframes = contentArea.select("iframe, embed").filter {
            val src = it.attr("src")
            !src.isNullOrBlank() && 
            src != "about:blank" && 
            !src.contains("googlesyndication") && 
            !src.contains("facebook") && 
            !src.contains("twitter") &&
            !src.contains("histats")
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
        } else if (validIframes.size > 1) {
            validIframes.mapIndexed { index, iframe ->
                val iframeSrc = iframe.attr("src")
                val episodeData = if (!iframeSrc.isNullOrBlank()) fixUrl(iframeSrc) else "$url#iframe_$index"
                newEpisode(episodeData) {
                    this.name = "Part ${index + 1}"
                    this.episode = index + 1
                }
            }.let { episodes.addAll(it) }
        } else {
            episodes.add(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                }
            )
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

    private suspend fun extractFromElement(
        element: Element,
        currentReferer: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false

        // 1. Target direct <video>, <source>, and elements containing video attributes
        val videoElements = element.select("video, source, video source")
        for (el in videoElements) {
            val src = el.attr("src").takeIf { !it.isNullOrBlank() && it != "about:blank" }
                ?: el.attr("data-src")
                ?: el.attr("data-url")
                ?: el.attr("data-file")

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
                        this.referer = currentReferer
                        this.quality = Qualities.Unknown.value
                    }
                )
                foundLinks = true
            }
        }

        // Also check parent elements or wrapper blocks that might contain video sources via attributes
        element.select("[data-src], [data-url], [data-file]").forEach { el ->
            listOf("data-src", "data-url", "data-file").forEach { attr ->
                val src = el.attr(attr)
                if (!src.isNullOrBlank() && (src.endsWith(".mp4") || src.contains(".m3u8")) && !src.startsWith("data:")) {
                    val videoUrl = fixUrl(src)
                    val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = videoUrl,
                            type = type
                        ) {
                            this.referer = currentReferer
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            }
        }

        // 2. Handle iframes inside the element
        element.select("iframe, embed").forEach { el ->
            val src = el.attr("src")
            if (!src.isNullOrBlank() && !src.startsWith("data:") && !src.contains("googlesyndication")) {
                val iframeUrl = fixUrl(src)
                
                if (iframeUrl.contains("player-x.php?q=")) {
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
                                    this.referer = iframeUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            foundLinks = true
                        }
                    } catch (_: Exception) {}
                } else {
                    if (loadExtractor(iframeUrl, currentReferer, subtitleCallback, callback)) {
                        foundLinks = true
                    } else {
                        try {
                            val iframeDoc = app.get(iframeUrl, headers = headers).document
                            if (extractFromElement(iframeDoc.body(), iframeUrl, headers, subtitleCallback, callback)) {
                                foundLinks = true
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        return foundLinks
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

        val targetUrl = if (data.startsWith("http")) {
            if (data.contains("#")) data.substringBefore("#") else data
        } else {
            data.substringBefore("#")
        }

        if (data.startsWith("http") && !data.contains("indiasocialbook.com/videos/")) {
            try {
                if (loadExtractor(data, mainUrl, subtitleCallback, callback)) {
                    foundLinks = true
                } else if (data.contains("player-x.php?q=")) {
                    val base64Query = data.substringAfter("q=").substringBefore("&")
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
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                }
            } catch (_: Exception) {}
        }

        if (!foundLinks) {
            try {
                if (targetUrl.isNotBlank()) {
                    val response = app.get(targetUrl, headers = headers)
                    // Fallback to full document body if specific content area doesn't capture the player block
                    val contentArea = response.document.selectFirst("div.entry-content, article.post, div.wps-player-block") ?: response.document.body()
                    
                    if (data.contains("#tab_")) {
                        val tabIndex = data.substringAfter("#tab_").toIntOrNull() ?: 0
                        val tabPanes = contentArea.select("div.tab-pane, div.tab-content > div")
                        val targetPane = tabPanes.getOrNull(tabIndex) ?: contentArea
                        if (extractFromElement(targetPane, targetUrl, headers, subtitleCallback, callback)) {
                            foundLinks = true
                        }
                    } else if (data.contains("#iframe_")) {
                        val iframeIndex = data.substringAfter("#iframe_").toIntOrNull() ?: 0
                        val iframes = contentArea.select("iframe")
                        val targetIframe = iframes.getOrNull(iframeIndex)
                        val iframeSrc = targetIframe?.attr("src")
                        if (!iframeSrc.isNullOrBlank()) {
                            val fixedSrc = fixUrl(iframeSrc)
                            if (loadExtractor(fixedSrc, targetUrl, subtitleCallback, callback)) {
                                foundLinks = true
                            }
                        }
                    } else {
                        // Scan both the specific content area and the entire document body for single video sources
                        if (extractFromElement(contentArea, targetUrl, headers, subtitleCallback, callback)) {
                            foundLinks = true
                        } else if (extractFromElement(response.document.body(), targetUrl, headers, subtitleCallback, callback)) {
                            foundLinks = true
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return foundLinks
    }
}