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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Most Recent",
        "$mainUrl/most_viewed/" to "Most Popular",
        "$mainUrl/new/" to "Last Added",
        "$mainUrl/longest/" to "Longest",
        "$mainUrl/top_rated/" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/$page/"
        val document = app.get(url).document
        val home = document.select("div.th").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = query.replace(" ", "-")
        val url = if (page <= 1) "$mainUrl/search/$formattedQuery/" else "$mainUrl/search/$formattedQuery/$page/"
        val document = app.get(url).document
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
        val document = app.get(url).document
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
            val res = app.get(url)
            val document = res.document
            val fullHtml = res.text

            Log.d(name, "Fetching video page: $url (Status: ${res.code})")

            // 1. Check standard HTML tags: <video>, <source>, <iframe>, <embed>, <object>
            val elements = document.select("video, source, iframe, embed, object")
            for (el in elements) {
                val src = el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-url") }
                if (src.isNotEmpty()) {
                    val resolved = fixUrl(src)
                    if (!resolved.contains("ads") && !resolved.endsWith(".js") && !resolved.contains("banner")) {
                        Log.d(name, "Found link via tag element: $resolved")
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

            // 2. Comprehensive raw text/script scanning for file URLs or embedded player variables
            val urlRegex = """(https?://[^\s"'+\\]+?\.(?:mp4|m3u8|mov|webm|ts)[^\s"'+\\]*)""".toRegex()
            urlRegex.findAll(fullHtml).forEach { match ->
                val foundUrl = fixUrl(match.value)
                if (!foundUrl.contains("ads") && !foundUrl.contains("banner") && !foundUrl.endsWith(".js")) {
                    Log.d(name, "Found link via global regex: $foundUrl")
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

            // 3. Fallback: Check if the site uses a specific custom embedded player or player variables in scripts
            val jsVarRegex = """(?:file|src|url|video_url|play_url)\s*[:=]\s*["'](https?://[^"']+)["']""".toRegex()
            jsVarRegex.findAll(fullHtml).forEach { match ->
                val foundUrl = fixUrl(match.groupValues[1])
                if (!foundUrl.contains("ads") && !foundUrl.endsWith(".js")) {
                    Log.d(name, "Found link via JS variable pattern: $foundUrl")
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