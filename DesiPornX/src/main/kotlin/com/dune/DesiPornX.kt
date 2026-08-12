package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DesiPornX : MainAPI() {
    override var mainUrl = "https://desipornx.org"
    override var name = "DesiPornX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home / Recent",
        "$mainUrl/most_viewed/" to "Most Popular",
        "$mainUrl/longest/" to "Longest",
        "$mainUrl/top_rated/" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/new/?p=$page"
        val document = app.get(url).document
        
        val results = document.select("div.th").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, results, true), true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = query.replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$formattedQuery" else "$mainUrl/page/$page/?s=$formattedQuery"
        val document = app.get(url).document
        
        val results = document.select("div.th").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("a") ?: return null
        val title = this.selectFirst("span.th_nm")?.text()?.trim() ?: titleElement.text().trim()
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
        return document.select("div.th").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Video"
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        
        val tags = document.select("span.tags-links a, .tagcloud a").map { it.text() }
        val actors = document.select("span.actor-links a, .template-actors a").map { Actor(it.text()) }
        val recommendations = document.select("div.th").mapNotNull { it.toSearchResult() }

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

        document.select("video source, iframe, div.post-thumbnail video").forEach { element ->
            val src = element.attr("src").takeIf { it.isNotBlank() } ?: element.attr("data-src")
            if (!src.isNullOrBlank()) {
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

        return foundLinks
    }
}