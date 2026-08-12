package com.dune

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.math.BigInteger

class DesiPornX : MainAPI() {
    override var mainUrl = "https://desipornx.org"
    override var name = "DesiPornX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Most recent",
        "$mainUrl/most_viewed/" to "Most popular",
        "$mainUrl/new/" to "Last Added",
        "$mainUrl/longest/" to "Longest",
        "$mainUrl/top_rated/" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        val home = document.select("div.cnt_bl div.ths_bl div.th").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // DesiPornX uses POST form for search based on HTML form action="/" method="POST" with input name="q"
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.post(
            url,
            data = mapOf("q" to query)
        ).document
        
        val results = document.select("div.cnt_bl div.ths_bl div.th").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("span.th_nm") ?: this.selectFirst("a") ?: return null
        val anchor = this.selectFirst("a") ?: return null
        val img = this.selectFirst("img")

        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: img?.attr("src")
        )

        val durationText = this.selectFirst("span.th_dr")?.text()?.trim()

        return newMovieSearchResponse(
            titleElement.text(),
            fixUrl(anchor.attr("href")),
            TvType.NSFW
        ) {
            this.posterUrl = poster
            this.duration = parseDuration(durationText)
        }
    }

    private fun parseDuration(durationStr: String?): Int? {
        if (durationStr.isNullOrEmpty()) return null
        try {
            val parts = durationStr.replace(Regex("[^0-9:]"), "").split(":")
            return when (parts.size) {
                2 -> parts[0].toIntOrNull()?.times(60)
                3 -> parts[0].toIntOrNull()?.times(3600)?.plus(parts[1].toIntOrNull()?.times(60) ?: 0)
                else -> parts[0].toIntOrNull()
            }
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query).results

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() 
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val recommendations = document.select("div.ths_bl div.th").mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        return this.toSearchResult()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var videoLinkFound = false

        // Fluidplayer or native video element source extraction fallback
        // Looking for source tags or embedded script streams inside the watch page
        val scriptContent = document.select("script").html()
        
        // Check if there are direct sources or iframe embeds
        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        if (!iframeSrc.isNullOrEmpty()) {
            val iframeDoc = app.get(fixUrl(iframeSrc)).document
            iframeDoc.select("source, video").forEach { srcElement ->
                val videoUrl = srcElement.attr("src")
                val quality = srcElement.attr("label") ?: "HD"
                if (!videoUrl.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = fixUrl(videoUrl),
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName(quality)
                        }
                    )
                    videoLinkFound = true
                }
            }
        }

        // Search scripts or source elements directly on the main page
        document.select("source").forEach { source ->
            val videoUrl = source.attr("src")
            if (!videoUrl.isNullOrEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = fixUrl(videoUrl),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P720.value
                    }
                )
                videoLinkFound = true
            }
        }

        return videoLinkFound
    }
}