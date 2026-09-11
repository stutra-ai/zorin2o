package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log

class RouVideo : MainAPI() {
    override var name = "RouVideo"
    override var mainUrl = "https://rouvideo.com"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "zh"
    override var hasMainPage = true

    val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = mainHeaders).document
        val videoItems = ArrayList<SearchResponse>()

        // 1. Parse Next.js __NEXT_DATA__ JSON payload on the homepage for both /v/ and /s/ links
        val scriptContent = document.select("script#__NEXT_DATA__").html()
        if (scriptContent.isNotBlank()) {
            try {
                // Match both /v/ and /s/ hashes
                val pathRegex = Regex("/([vs])/([a-zA-Z0-9_-]{20,})/")
                val matches = pathRegex.findAll(scriptContent)
                
                for (match in matches) {
                    val typeChar = match.groupValues[1]
                    val hash = match.groupValues[2]
                    val isSeries = typeChar == "s"
                    val watchUrl = "$mainUrl/$typeChar/$hash"
                    val tvType = if (isSeries) TvType.TvSeries else TvType.Movie
                    
                    if (videoItems.none { it.url == watchUrl }) {
                        videoItems.add(
                            if (isSeries) {
                                newTvSeriesSearchResponse(
                                    name = "合集 $hash",
                                    url = watchUrl,
                                    type = tvType
                                ) {
                                    this.posterUrl = "https://v.rn252.xyz/m/$hash/cover.jpg"
                                }
                            } else {
                                newMovieSearchResponse(
                                    name = "影片 $hash",
                                    url = watchUrl,
                                    type = tvType
                                ) {
                                    this.posterUrl = "https://v.rn252.xyz/m/$hash/cover.jpg"
                                }
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("RouVideo", "Error parsing homepage JSON data: ${e.message}")
            }
        }

        // 2. Fallback to link preloads
        if (videoItems.isEmpty()) {
            val coverLinks = document.select("link[rel=preload][as=image]")
            for (link in coverLinks) {
                val href = link.attr("href")
                val hashRegex = Regex("/m/([a-zA-Z0-9_-]{20,})/")
                val match = hashRegex.find(href)
                
                if (match != null) {
                    val folderHash = match.groupValues[1]
                    val watchUrl = "$mainUrl/v/$folderHash"
                    val posterUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                    
                    if (videoItems.none { it.url == watchUrl }) {
                        videoItems.add(
                            newMovieSearchResponse("影片 $folderHash", watchUrl, TvType.Movie) {
                                this.posterUrl = posterUrl
                            }
                        )
                    }
                }
            }
        }

        val homePageList = listOf(HomePageList("熱門推薦", videoItems))
        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl, headers = mainHeaders).document
        val searchResults = ArrayList<SearchResponse>()

        val links = document.select("a[href*=/v/], a[href*=/s/]")
        for (link in links) {
            val href = link.attr("href")
            val title = link.text().ifBlank { "影片/合集" }
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            val isSeries = href.contains("/s/")
            val tvType = if (isSeries) TvType.TvSeries else TvType.Movie
            
            if (searchResults.none { it.url == fullUrl } && (href.contains("/v/") || href.contains("/s/"))) {
                searchResults.add(
                    if (isSeries) {
                        newTvSeriesSearchResponse(title, fullUrl, tvType)
                    } else {
                        newMovieSearchResponse(title, fullUrl, tvType)
                    }
                )
            }
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document
        
        val title = document.select("meta[property=og:title]").attr("content")
            .ifBlank { document.select("title").text() }
            .ifBlank { "RouVideo 內容" }

        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.select("meta[property=og:description]").attr("content")

        // Check if it's a serial / show (/s/) or a single movie (/v/)
        if (url.contains("/s/")) {
            val episodes = ArrayList<Episode>()
            val episodeLinks = document.select("a[href*=/v/]")
            
            var epIndex = 1
            for (epLink in episodeLinks) {
                val epHref = epLink.attr("href")
                val epUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                val epTitle = epLink.text().ifBlank { "第 $epIndex 集" }
                
                if (episodes.none { it.data == epUrl }) {
                    episodes.add(
                        Episode(
                            data = epUrl,
                            name = epTitle,
                            episode = epIndex++
                        )
                    )
                }
            }

            // Fallback if no sub-links found directly: treat the serial itself as an entry or load via its own hash
            if (episodes.isEmpty()) {
                episodes.add(
                    Episode(
                        data = url,
                        name = title,
                        episode = 1
                    )
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster.ifBlank { null }
                this.plot = description.ifBlank { null }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster.ifBlank { null }
                this.plot = description.ifBlank { null }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("RouVideo", "Loading links for URL: $data")
        
        try {
            val document = app.get(data, headers = mainHeaders).document
            val htmlContent = document.html()

            val scriptContent = document.select("script#__NEXT_DATA__").html()
            if (scriptContent.isNotBlank()) {
                val jsonHashRegex = Regex("/m/([a-zA-Z0-9_-]{20,})/")
                val jsonMatch = jsonHashRegex.find(scriptContent)
                if (jsonMatch != null) {
                    val folderHash = jsonMatch.groupValues[1]
                    val m3u8Url = "https://v.rn252.xyz/m/$folderHash/index.m3u8"
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name CDN",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                        }
                    )
                    return true
                }
            }

            val hashRegex = Regex("/m/([a-zA-Z0-9_-]{20,})/")
            val match = hashRegex.find(htmlContent)
            if (match != null) {
                val folderHash = match.groupValues[1]
                val m3u8Url = "https://v.rn252.xyz/m/$folderHash/index.m3u8"
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name CDN",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                    }
                )
                return true
            }

            val hlsRegex = Regex("[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']")
            val hlsMatch = hlsRegex.find(htmlContent)?.groupValues?.get(1)
            if (hlsMatch != null) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Stream",
                        url = hlsMatch,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                    }
                )
                return true
            }

        } catch (e: Exception) {
            Log.e("RouVideo", "Exception in loadLinks: ${e.message}", e)
        }

        return false
    }
}