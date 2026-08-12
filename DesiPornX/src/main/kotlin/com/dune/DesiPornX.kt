package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DesiPornX : MainAPI() {
    override var mainUrl = "https://desipornx.org"
    override var name = "DesiPornX"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/most_viewed/" to "Most Popular",
        "$mainUrl/longest/" to "Longest",
        "$mainUrl/top_rated/" to "Top Rated"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Handle pagination structure (e.g., /new/?p=2 or page-based URLs if applicable)
        val url = if (page > 1) {
            when {
                request.data.contains("most_viewed") -> "$mainUrl/most_viewed/?p=$page"
                request.data.contains("longest") -> "$mainUrl/longest/?p=$page"
                request.data.contains("top_rated") -> "$mainUrl/top_rated/?p=$page"
                else -> "$mainUrl/new/?p=$page"
            }
        } else {
            request.data
        }

        val document = app.get(url).document
        val home = document.select("div.th").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = this.selectFirst("span.th_nm")?.text() ?: linkElement.attr("title")
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        if (href.isBlank() || title.isBlank()) return null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "$mainUrl/",
            data = mapOf("q" to query)
        ).document
        return document.select("div.th").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: document.selectFirst("title")?.text().orEmpty()
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val html = document.html()

        var videoUrl = document.selectFirst("video source")?.attr("src") 
            ?: document.selectFirst("source")?.attr("src") 
            ?: document.selectFirst("video")?.attr("src")

        if (videoUrl.isNullOrBlank()) {
            val patterns = listOf(
                """file\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""".toRegex(),
                """src\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""".toRegex(),
                """(https?://[^\s"'<>]+?\.(?:mp4|m3u8)[^\s"'<>]*)""".toRegex()
            )
            
            for (regex in patterns) {
                val match = regex.find(html)
                if (match != null) {
                    videoUrl = match.groupValues[1]
                    break
                }
            }
        }

        if (videoUrl.isNullOrBlank()) return false

        videoUrl = fixUrl(videoUrl)

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = Qualities.P720.value
            }
        )

        return true
    }
}