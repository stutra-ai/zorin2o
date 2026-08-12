package com.dune

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

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
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
        val response = app.get(data, headers = headers)
        val document = response.document
        var foundLinks = false

        // 1. Target direct <source> tags and <video> elements in the main document DOM
        document.select("video source, video, source").forEach { element ->
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
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                foundLinks = true
            }
        }

        // 2. Handle WordPress plugin iframe loaders / ajax video wrappers if present
        document.select("iframe, embed").forEach { element ->
            val src = element.attr("src")
            if (!src.isNullOrBlank() && !src.startsWith("data:")) {
                val iframeUrl = fixUrl(src)
                if (loadExtractor(iframeUrl, data, subtitleCallback, callback)) {
                    foundLinks = true
                } else {
                    // Try scraping inner page of the iframe/plugin container directly
                    try {
                        val iframeDoc = app.get(iframeUrl, headers = headers).document
                        iframeDoc.select("video, source").forEach { innerEl ->
                            val innerSrc = innerEl.attr("src") ?: innerEl.attr("data-src")
                            if (!innerSrc.isNullOrBlank()) {
                                val fixedInner = fixUrl(innerSrc)
                                val type = if (fixedInner.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                callback.invoke(
                                    newExtractorLink(
                                        name = name,
                                        source = name,
                                        url = fixedInner,
                                        type = type
                                    ) {
                                        this.referer = iframeUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                foundLinks = true
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 3. Raw HTML regex scan for embedded video file formats (.mp4, .m3u8, .webm, .m4v)
        val html = response.text
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
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                foundLinks = true
            }
        }

        // 4. Scan JavaScript player configurations (Fluid Player / WP plugins setup)
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
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                foundLinks = true
            }
        }

        return foundLinks
    }
}