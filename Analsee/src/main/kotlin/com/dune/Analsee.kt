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
        "$mainUrl/most-popular/" to "Most Popular",
        "$mainUrl/top-rated/" to "Top Rated",
        "$mainUrl/longest/" to "Longest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/${page}/"
        val document = app.get(url).document
        val home = document.select("div.video-item, div.item, div.thumb-block, div.col").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val formattedQuery = query.replace(" ", "-")
        val url = "$mainUrl/search/$formattedQuery/"
        val document = app.get(url).document
        return document.select("div.video-item, div.item, div.thumb-block, div.col").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("a.title, p.title a, div.title a, h3 a") ?: this.selectFirst("a") ?: return null
        val img = this.selectFirst("img")

        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: img?.attr("src")
        )

        val href = fixUrl(titleElement.attr("href"))
        val title = titleElement.text().trim()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("video")?.attr("poster")
        )
        
        val tags = document.select("div.tags a, .video-tags a, ul.tags li a, .categories a").map { it.text() }
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val actors = document.select("div.models a, .cast a, span.model a, .pornstars a").map { Actor(it.text()) }
        
        val recommendations = document.select("div.video-item, div.item, div.thumb-block, div.col").mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val titleElement = this.selectFirst("a.title, p.title a, div.title a, h3 a") ?: this.selectFirst("a") ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src") 
                ?: this.selectFirst("img")?.attr("src")
        )
        return newMovieSearchResponse(
            titleElement.text().trim(),
            fixUrl(titleElement.attr("href")),
            TvType.NSFW
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = fixUrl(data)
        var videoFound = false

        try {
            val document = app.get(url).document
            val scriptContent = document.select("script").html()
            
            val sourceRegex = """"(https?://[^"]+\.(?:mp4|m3u8)[^"]*)"""".toRegex()
            sourceRegex.findAll(scriptContent).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                if (!videoUrl.contains("ads", ignoreCase = true)) {
                    val quality = if (videoUrl.contains("1080p")) Qualities.P1080.value 
                                  else if (videoUrl.contains("720p")) Qualities.P720.value 
                                  else Qualities.Unknown.value

                    callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = quality
                        }
                    )
                    videoFound = true
                }
            }

            if (!videoFound) {
                document.select("video source, source, iframe").forEach { element ->
                    val src = element.attr("src")
                    if (src.isNotEmpty()) {
                        val fixedSrc = fixUrl(src)
                        callback.invoke(
                            newExtractorLink(
                                name = name,
                                source = name,
                                url = fixedSrc,
                                type = if (fixedSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        videoFound = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(name, "Error loading links: ${e.message}")
        }

        return videoFound
    }
}