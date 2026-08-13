package com.dune

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DesiPornX : MainAPI() {
    override var mainUrl = "https://www.desipornx.org"
    override var name = "DesiPornX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Most Recent",
        "$mainUrl/most_viewed/" to "Most Popular",
        "$mainUrl/new/" to "Last Added",
        "$mainUrl/longest/" to "Longest",
        "$mainUrl/top_rated/" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/$page/"
        
        val res = app.get(url, headers = headers)
        val document = res.document
        val home = document.select("div.th").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = query.replace(" ", "-")
        val url = if (page <= 1) "$mainUrl/search/$formattedQuery/" else "$mainUrl/search/$formattedQuery/$page/"
        
        val res = app.get(url, headers = headers)
        val document = res.document
        val results = document.select("div.th").mapNotNull { it.toSearchResult() }
        
        return newSearchResponseList(results, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("a") ?: return null
        val title = this.selectFirst("span.th_nm")?.text() ?: titleElement.text()
        val img = this.selectFirst("img")

        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: img?.attr("src")
        )

        return newMovieSearchResponse(
            title,
            fixUrl(titleElement.attr("href")),
            TvType.NSFW
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = headers)
        val document = res.document
        
        val title = document.selectFirst("h1")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() 
            ?: "DesiPornX Video"
        
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val recommendations = document.select("div.th").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = fixUrl(data)
        var videolink = false

        try {
            val res = app.get(url, headers = headers)
            val document = res.document
            val fullHtml = res.text

            // 1. Check standard HTML tags: <video>, <source>, <iframe>
            val elements = document.select("video, source, iframe, embed")
            for (el in elements) {
                val src = el.attr("src").ifEmpty { el.attr("data-src") }
                if (src.isNotEmpty()) {
                    val resolved = fixUrl(src)
                    if (!resolved.contains("ads") && !resolved.endsWith(".js") && !resolved.contains("banner")) {
                        callback.invoke(
                            newExtractorLink(
                                name = name,
                                source = name,
                                url = resolved,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        videolink = true
                    }
                }
            }

            // 2. Scan script tags and raw HTML for direct streams (.mp4, .m3u8)
            val urlRegex = """(https?://[^\s"'+\\]+?\.(?:mp4|m3u8|mov|webm)[^\s"'+\\]*)""".toRegex()
            urlRegex.findAll(fullHtml).forEach { match ->
                val foundUrl = fixUrl(match.value)
                if (!foundUrl.contains("ads") && !foundUrl.contains("banner") && !foundUrl.endsWith(".js")) {
                    callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = foundUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    videolink = true
                }
            }

        } catch (e: Exception) {
            Log.d(name, "Error loading links: ${e.message}")
        }

        return videolink
    }
}