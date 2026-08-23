package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DesiHub : MainAPI() {
    override var mainUrl = "https://desihub.tv"
    override var name = "DesiHub"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Home",
        "$mainUrl/explore/1" to "Explore"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = mainHeaders).document
        
        // Target post anchor cards directly based on homepage grid structure
        val items = document.select("div.grid a[href^=\"/post/\"]")

        val home = items.mapNotNull { it.toSearchResponse() }
        val hasNext = home.isNotEmpty() && page == 1

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        
        // Extract title from the heading inside the card element
        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        
        // Extract poster image source (handling Next.js optimized image paths if present)
        val imgElement = this.selectFirst("img")
        val rawImgUrl = imgElement?.attr("src") ?: imgElement?.attr("data-src")
        
        // Unescape/extract original image URL if it's wrapped inside Next.js image optimizer query params
        val posterUrl = if (rawImgUrl?.contains("url=") == true) {
            rawImgUrl.substringAfter("url=").substringBefore("&").replace("%3A", ":").replace("%2F", "/")
        } else {
            fixUrlNull(rawImgUrl)
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/x/$query"
        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.grid a[href^=\"/post/\"]")

        val results = items.mapNotNull { it.toSearchResponse() }
        return newSearchResponseList(results, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("main img")?.attr("src"))
        val description = document.select("main p").joinToString(" ") { it.text() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mainHeaders
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mainHeaders).document

        // 1. Check for direct video tags or sources
        document.select("video, source").forEach { source ->
            val videoUrl = source.attr("src").ifBlank { source.attr("data-src") }
            if (videoUrl.isNotBlank()) {
                val fixedUrl = fixUrl(videoUrl)
                val type = if (fixedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name ${if (type == ExtractorLinkType.M3U8) "HLS" else "MP4"}",
                        url = fixedUrl,
                        type = type
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }

        // 2. Check for iframes and pass them through Cloudstream's loadExtractor
        document.select("iframe").forEach { iframe ->
            val iframeUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (iframeUrl.isNotBlank()) {
                val fixedIframeUrl = fixUrl(iframeUrl)
                loadExtractor(fixedIframeUrl, data, subtitleCallback, callback)
            }
        }

        // 3. Fallback: Parse Next.js data script tags if links are embedded in JSON state
        val nextDataScript = document.selectFirst("script#__NEXT_DATA__")?.data()
        if (!nextDataScript.isNullOrBlank()) {
            val videoUrlRegex = "(https?://[^\"']+\\.(?:m3u8|mp4)[^\"']*)".toRegex()
            videoUrlRegex.findAll(nextDataScript).forEach { matchResult ->
                val videoUrl = matchResult.value.replace("\\/", "/")
                val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Stream",
                        url = videoUrl,
                        type = type
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }

        return true
    }
}