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
        val document = app.get(url).document
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
        val document = app.get(data).document
        var foundLinks = false

        // 1. Prioritize HTML5 video elements and source tags (handling custom attributes like __cporiginalvalueofsrc)
        val videoElements = document.select("video, video source, source")
        for (element in videoElements) {
            val src = element.attr("__cporiginalvalueofsrc")
                .takeIf { !it.isNullOrBlank() }
                ?: element.attr("src")
                .takeIf { !it.isNullOrBlank() && it != "about:blank" }
                ?: element.attr("data-src")
                ?: element.attr("data-url")
                ?: element.attr("nitro-lazy-src")

            if (!src.isNullOrBlank() && !src.startsWith("data:")) {
                val videoUrl = fixUrl(src)

                if (element.tagName() == "iframe" || element.tagName() == "embed" || 
                    (!videoUrl.endsWith(".mp4", true) && !videoUrl.endsWith(".m3u8", true) && !videoUrl.contains("mp4"))) {
                    
                    if (loadExtractor(videoUrl, data, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            }
        }

        // 2. Check standard iframes and player embed containers
        val iframes = document.select("iframe, embed")
        for (iframe in iframes) {
            val src = iframe.attr("src")
                .takeIf { !it.isNullOrBlank() && it != "about:blank" }
                ?: iframe.attr("data-src")
                ?: iframe.attr("data-url")

            if (!src.isNullOrBlank() && !src.startsWith("data:")) {
                val iframeUrl = fixUrl(src)
                if (loadExtractor(iframeUrl, data, subtitleCallback, callback)) {
                    foundLinks = true
                }
            }
        }

        // 3. Fallback: Parse script tags and raw HTML for embedded stream links (.mp4 or .m3u8)
        val html = document.html()
        val regexPatterns = listOf(
            "\"(https?://[^\"]+\\.(?:mp4|m3u8|mkv)[^\"]*)\()\"".toRegex(),
            "src=[\"'](https?://[^\"']+\\.(?:mp4|m3u8|mkv)[^\"']*)[\"']".toRegex(),
            "__cporiginalvalueofsrc=[\"'](https?://[^\"']+)[\"']".toRegex()
        )

        for (pattern in regexPatterns) {
            for (match in pattern.findAll(html)) {
                val matchUrl = match.groupValues[1]
                if (!matchUrl.contains("googlesyndication") && !matchUrl.contains("facebook") && !matchUrl.contains("twitter")) {
                    val fixed = fixUrl(matchUrl)
                    callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = fixed,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }
}