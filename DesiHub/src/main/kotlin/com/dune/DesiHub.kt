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
        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        
        val imgElement = this.selectFirst("img")
        val rawImgUrl = imgElement?.attr("src") ?: imgElement?.attr("data-src")
        
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

    private fun isInvalidUrl(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore("?") // Strips query parameters to correctly check file extensions
        return path.contains("_next/image") || 
               path.contains("favicon") ||
               path.contains("logo") ||
               path.contains("poster") ||
               path.endsWith(".jpg") || path.endsWith(".jpeg") || 
               path.endsWith(".png") || path.endsWith(".webp") || 
               path.endsWith(".gif") || path.endsWith(".svg") || 
               path.endsWith(".ico") ||
               path.endsWith(".css") || path.endsWith(".js") ||
               path.endsWith(".vtt") || path.endsWith(".srt")
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = mainHeaders)
        val document = res.document
        val htmlString = res.text

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("main img")?.attr("src"))
        val description = document.select("main p").joinToString(" ") { it.text() }

        val videoUrls = mutableSetOf<String>()

        // 1. Collect strictly from <video> tags and their direct <source> children
        document.select("video").forEach { video ->
            val vUrl = video.attr("src").ifBlank { video.attr("data-src") }
            if (vUrl.isNotBlank() && !isInvalidUrl(vUrl)) videoUrls.add(fixUrl(vUrl))

            video.select("source").forEach { source ->
                val sUrl = source.attr("src").ifBlank { source.attr("data-src") }
                if (sUrl.isNotBlank() && !isInvalidUrl(sUrl)) videoUrls.add(fixUrl(sUrl))
            }
        }

        // 2. Collect valid player iframes
        document.select("iframe").forEach { iframe ->
            val iUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (iUrl.isNotBlank() && !isInvalidUrl(iUrl) && !iUrl.contains("ads") && !iUrl.contains("syndication")) {
                videoUrls.add(fixUrl(iUrl))
            }
        }

        // 3. Regex scan for m3u8/mp4/ts streams hidden in scripts/payload
        val videoUrlRegex = "https?://[^\\s\"']+?\\.(?:m3u8|mp4|ts)(?:\\?[^\\s\"']*)?".toRegex()
        videoUrlRegex.findAll(htmlString).forEach { matchResult ->
            val vUrl = matchResult.value.replace("\\/", "/")
            if (!vUrl.contains("googletagmanager") && 
                !vUrl.contains("cloudflare") && 
                !vUrl.contains("schema.org") && 
                !isInvalidUrl(vUrl)) {
                videoUrls.add(vUrl)
            }
        }

        // Fallback: If no specific video links found, store the page URL itself
        if (videoUrls.isEmpty()) {
            videoUrls.add(url)
        }

        // If there is only 1 valid media link, return as a Movie response (plays directly without episode screen)
        if (videoUrls.size == 1) {
            return newMovieLoadResponse(title, url, TvType.NSFW, videoUrls.first()) {
                this.posterUrl = poster
                this.posterHeaders = mainHeaders
                this.plot = description
            }
        }

        // Otherwise, return as a TvSeries only when multiple actual parts/videos exist
        val episodes = videoUrls.mapIndexed { index, vUrl ->
            newEpisode(vUrl) {
                name = "Part ${index + 1}"
                season = 1
                episode = index + 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
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
        when {
            data.contains(".m3u8") -> {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = data,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
            data.contains(".mp4") || data.contains(".ts") -> {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name MP4",
                        url = data,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
            data.contains("desihub.tv/post/") -> {
                val document = app.get(data, headers = mainHeaders).document
                document.select("video").forEach { video ->
                    val vUrl = video.attr("src").ifBlank { video.attr("data-src") }
                    if (vUrl.isNotBlank() && !isInvalidUrl(vUrl)) {
                        val fixedUrl = fixUrl(vUrl)
                        val type = if (fixedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback.invoke(
                            newExtractorLink(name, name, fixedUrl, type) {
                                this.referer = mainUrl
                            }
                        )
                    }
                    video.select("source").forEach { source ->
                        val sUrl = source.attr("src").ifBlank { source.attr("data-src") }
                        if (sUrl.isNotBlank() && !isInvalidUrl(sUrl)) {
                            val fixedUrl = fixUrl(sUrl)
                            val type = if (fixedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink(name, name, fixedUrl, type) {
                                    this.referer = mainUrl
                                }
                            )
                        }
                    }
                }
                document.select("iframe").forEach { iframe ->
                    val iUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                    if (iUrl.isNotBlank() && !isInvalidUrl(iUrl)) {
                        loadExtractor(fixUrl(iUrl), data, subtitleCallback, callback)
                    }
                }
            }
            else -> {
                loadExtractor(data, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}