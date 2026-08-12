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

        // Collect tab items if available
        val tabNavs = document.select("div.video-tabs ul.tab-nav li, ul.tab-nav li")
        
        // Collect all valid content iframes/embeds across the page
        val validIframes = document.select("article iframe, div.entry-content iframe, div.video-container iframe, iframe").filter {
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
                    document.selectFirst("div.tab-content div#$targetTabId iframe, div#$targetTabId iframe")?.attr("src")
                } else null
                
                val resolvedIframe = iframeSrc ?: document.select("div.tab-pane iframe, iframe").getOrNull(index)?.attr("src")
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
            // Single video source fallback
            val singleIframe = validIframes.firstOrNull()?.attr("src")
            val episodeData = if (!singleIframe.isNullOrBlank()) fixUrl(singleIframe) else url
            episodes.add(
                newEpisode(episodeData) {
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

    private suspend fun extractFromDoc(
        doc: Document,
        currentReferer: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false

        // 1. Target direct <source> and <video> tags
        doc.select("video source, video, source").forEach { element ->
            val src = element.attr("src")
                .takeIf { !it.isNullOrBlank() && it != "about:blank" }
                ?: element.attr("data-src")
                ?: element.attr("data-url")
                ?: element.attr("data-file")

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

        // 2. Handle iframes inside the document
        doc.select("iframe, embed").forEach { element ->
            val src = element.attr("src")
            if (!src.isNullOrBlank() && !src.startsWith("data:")) {
                val iframeUrl = fixUrl(src)
                
                // Check Base64 clean-tube-player pattern
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
                            if (extractFromDoc(iframeDoc, iframeUrl, headers, subtitleCallback, callback)) {
                                foundLinks = true
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // 3. Raw HTML regex scan for video file extensions (.mp4, .m3u8, etc.)
        val html = doc.html()
        val videoRegex = "https?://[^\\s\"'<>]+?\\.(mp4|m3u8|webm|m4v)[^\\s\"'<>]*".toRegex(RegexOption.IGNORE_CASE)
        for (match in videoRegex.findAll(html)) {
            var matchUrl = match.value.replace("&amp;", "&")
            matchUrl = matchUrl.trimEnd('"', '\'', '\\', '}', ']')
            
            if (!matchUrl.contains("googlesyndication") && !matchUrl.contains("facebook") && !matchUrl.contains("twitter")) {
                val fixed = fixUrl(matchUrl)
                val type = if (fixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = fixed,
                        type = type
                    ) {
                        this.referer = currentReferer
                        this.quality = Qualities.Unknown.value
                    }
                )
                foundLinks = true
            }
        }

        // 4. Scan JavaScript player configurations
        val jsConfigRegex = "(?:file|src|url|video_url|source)\\s*[:=]\\s*[\"'](https?://[^\"']+)[\"']".toRegex(RegexOption.IGNORE_CASE)
        for (match in jsConfigRegex.findAll(html)) {
            val matchUrl = match.groups[1]?.value?.replace("&amp;", "&") ?: continue
            if (!matchUrl.contains("googlesyndication") && !matchUrl.contains("wp-content/themes") && !matchUrl.contains("wp-includes")) {
                val fixed = fixUrl(matchUrl)
                val type = if (fixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = fixed,
                        type = type
                    ) {
                        this.referer = currentReferer
                        this.quality = Qualities.Unknown.value
                    }
                )
                foundLinks = true
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

        // If data points directly to an iframe or player URL, try extracting from it directly first
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
                } else {
                    val iframeDoc = app.get(data, headers = headers).document
                    if (extractFromDoc(iframeDoc, data, headers, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                }
            } catch (_: Exception) {}
        }

        // If not found yet, scrape the target page URL document
        if (!foundLinks) {
            try {
                if (targetUrl.isNotBlank()) {
                    val response = app.get(targetUrl, headers = headers)
                    if (extractFromDoc(response.document, targetUrl, headers, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                }
            } catch (_: Exception) {}
        }

        return foundLinks
    }
}