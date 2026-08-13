package com.dune

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Analsee : MainAPI() {
    override var mainUrl = "https://www.analsee.com"
    override var name = "Analsee"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Most Recent",
        "$mainUrl/videos/?sort_by=video_viewed" to "Most Viewed",
        "$mainUrl/videos/?sort_by=rating" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data.removeSuffix("/")}/$page/"
            }
        }
        val document = app.get(url).document
        val home = document.select("div.th").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val formattedQuery = query.replace(" ", "-")
        val url = "$mainUrl/search/$formattedQuery/"
        val document = app.get(url).document
        return document.select("div.th").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a.thumb") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        val img = this.selectFirst("img")
        val poster = img?.attr("data-src")?.ifEmpty { null } 
            ?: img?.attr("src")

        val title = linkElement.attr("title").ifEmpty { this.selectFirst(".thumb_title")?.text() } ?: return null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title")?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.trim() ?: return null
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        val tags = document.select(".video-info .tags a, .tags a").map { it.text() }
        
        val recommendations = document.select("div.th").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("Referer" to "$mainUrl/")).document
        val scriptContent = document.select("script").html()
        
        var found = false
        val regex = """['"](https?://[^"']+\.(?:mp4|m3u8)[^"']*)['"]""".toRegex()
        
        regex.findAll(scriptContent).forEach { match ->
            val videoUrl = match.groupValues[1].replace("\\/", "/")
            if (!videoUrl.contains("ads", ignoreCase = true) && !videoUrl.contains("track")) {
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        
        return found
    }
}