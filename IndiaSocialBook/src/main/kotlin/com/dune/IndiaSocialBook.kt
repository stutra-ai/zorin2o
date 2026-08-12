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
        val response = app.get(data)
        val document = response.document
        var foundLinks = false

        // 1. Direct inspection of video and source elements in current DOM
        for (source in document.select("video source, source, video")) {
            val src = source.attr("__cporiginalvalueofsrc")
                .takeIf { !it.isNullOrBlank() }
                ?: source.attr("src")
                .takeIf { !it.isNullOrBlank() && it != "about:blank" }
                ?: source.attr("data-src")
                ?: source.attr("data-url")

            if (!src.isNullOrBlank() && !src.startsWith("data:")) {
                val videoUrl = fixUrl(src)
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

        // 2. Check standard iframes
        for (iframe in document.select("iframe")) {
            val src = iframe.attr("src")
                .takeIf { !it.isNullOrBlank() && it != "about:blank" }
                ?: iframe.attr("data-src")

            if (!src.isNullOrBlank() && !src.startsWith("data:")) {
                if (loadExtractor(fixUrl(src), data, subtitleCallback, callback)) {
                    foundLinks = true
                }
            }
        }

        // 3. Fallback: Parse entire text/html and script contents for any .mp4 links (handles fluid-player JS configs)
        val html = response.text
        val regex = "https?://[^\\s\"']+\\.mp4[^\\s\"']*".toRegex()
        
        for (match in regex.findAll(html)) {
            val matchUrl = match.value.replace("&amp;", "&")
            if (!matchUrl.contains("googlesyndication") && !matchUrl.contains("facebook")) {
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

        return foundLinks
    }
}