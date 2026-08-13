package com.dune

import com.lagradost.cloudstream3.*
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
        "$mainUrl/videos/" to "Videos",
        "$mainUrl/videos/?sort_by=video_viewed" to "Most Viewed",
        "$mainUrl/videos/?sort_by=rating" to "Top Rated"
    )

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://www.analsee.com/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            if (request.data.contains("?")) {
                val parts = request.data.split("?")
                "${parts[0].removeSuffix("/")}/$page/?${parts[1]}"
            } else {
                "${request.data.removeSuffix("/")}/$page/"
            }
        }

        val document = app.get(url, headers = standardHeaders).document
        val home = document.select("div.th").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val formattedQuery = query.replace(" ", "-")
        val url = "$mainUrl/search/$formattedQuery/"
        val document = app.get(url, headers = standardHeaders).document
        return document.select("div.th").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        if (href.isBlank()) return null

        val img = this.selectFirst("img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")

        val title = linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst(".thumb_title, .title, span")?.text()?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: "Video"

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = standardHeaders).document
        
        val title = document.selectFirst("h1.title")?.text()?.trim() 
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.trim() ?: "Video"
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        val tags = document.select(".video-info .tags a, .tags a, .categories a").map { it.text() }
        
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
        val document = app.get(data, headers = standardHeaders).document
        val scriptContent = document.select("script").html()
        
        var found = false
        val regex = """['"](https?://[^"']+\.(?:mp4|m3u8)[^"']*)['"]""".toRegex()
        
        regex.findAll(scriptContent).forEach { match ->
            val videoUrl = match.groupValues[1].replace("\\/", "/")
            if (!videoUrl.contains("ads", ignoreCase = true) && !videoUrl.contains("track")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                found = true
            }
        }
        
        return found
    }
}