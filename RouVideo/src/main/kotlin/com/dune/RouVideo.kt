package com.rouge.rouvideo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log

class RouVideo : MainAPI() {
    override var name = "RouVideo"
    override var mainUrl = "https://rouvideo.com" // Update if domain changes
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "zh"
    override bool hasMainPage = true

    override val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = mainHeaders).document
        val videoItems = ArrayList<SearchResponse>()

        // Extract video entries from preloaded cover links
        val coverLinks = document.select("link[rel=preload][as=image]")
        
        for (link in coverLinks) {
            val href = link.attr("href")
            val hashRegex = Regex("/m/([a-zA-Z0-9_-]{20,})/")
            val match = hashRegex.find(href)
            
            if (match != null) {
                val folderHash = match.groupValues[1]
                val watchUrl = "$mainUrl/v/$folderHash"
                val posterUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                
                // Avoid duplicate entries if multiple sizes/formats share the same hash
                if (videoItems.none { it.url == watchUrl }) {
                    videoItems.add(
                        newAnimeSearchResponse(
                            title = "影片 $folderHash",
                            url = watchUrl,
                            type = TvType.Movie
                        ) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }
        }

        val homePageList = listOf(HomePageList("熱門推薦", videoItems))
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl, headers = mainHeaders).document
        val searchResults = ArrayList<SearchResponse>()

        // Fallback or specific search parsing using standard anchor tags or Next.js state
        val links = document.select("a[href*=/v/]")
        for (link in links) {
            val href = link.attr("href")
            val title = link.text().ifBlank { "影片" }
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            
            if (searchResults.none { it.url == fullUrl } && href.contains("/v/")) {
                searchResults.add(
                    newAnimeSearchResponse(
                        title = title,
                        url = fullUrl,
                        type = TvType.Movie
                    )
                )
            }
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document
        
        // Try getting the title from meta or header tags
        val title = document.select("meta[property=og:title]").attr("content")
            .ifBlank { document.select("title").text() }
            .ifBlank { "RouVideo 影片" }

        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.select("meta[property=og:description]").attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster.ifBlank { null }
            this.plot = description.ifBlank { null }
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

            // 1. Parse Next.js __NEXT_DATA__ payload for server state variables
            val scriptContent = document.select("script#__NEXT_DATA__").html()
            if (scriptContent.isNotBlank()) {
                val jsonHashRegex = Regex("/m/([a-zA-Z0-9_-]{20,})/")
                val jsonMatch = jsonHashRegex.find(scriptContent)
                if (jsonMatch != null) {
                    val folderHash = jsonMatch.groupValues[1]
                    val m3u8Url = "https://v.rn252.xyz/m/$folderHash/index.m3u8"
                    Log.d("RouVideo", "Found M3U8 hash in Next.js JSON: $m3u8Url")
                    
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

            // 2. Fallback: Search raw HTML body content for stream hash patterns
            val hashRegex = Regex("/m/([a-zA-Z0-9_-]{20,})/")
            val match = hashRegex.find(htmlContent)
            if (match != null) {
                val folderHash = match.groupValues[1]
                val m3u8Url = "https://v.rn252.xyz/m/$folderHash/index.m3u8"
                Log.d("RouVideo", "Found M3U8 hash in raw HTML: $m3u8Url")
                
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

            // 3. Fallback: Direct .m3u8 endpoint matches
            val hlsRegex = Regex("[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']")
            val hlsMatch = hlsRegex.find(htmlContent)?.groupValues?.get(1)
            if (hlsMatch != null) {
                Log.d("RouVideo", "Found explicit M3U8 stream: $hlsMatch")
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

        Log.d("RouVideo", "Link extraction completely failed for: $data")
        return false
    }
}