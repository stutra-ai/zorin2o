package com.dune

import com.lagradost.api.Log
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

    override suspend fun search(query: String): List<SearchResponse> {
        val url = mainUrl
        val document = app.post(
            url,
            data = mapOf("q" to query)
        ).document
        
        return document.select("div.cnt_bl div.ths_bl div.th").mapNotNull { it.toSearchResult() }
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
            this.quality = durationText
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

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