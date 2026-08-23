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

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("main img")?.attr("src"))
        val description = document.select("main p").joinToString(" ") { it.text() }

        val videoSources = mutableListOf<String>()

        // 1. Target direct HTML5 video elements and source tags inside video containers
        document.select("video, video source, div.player source").forEach { element ->
            val src = element.attr("src").ifBlank { element.attr("data-src") }
            if (src.isNotBlank() && !videoSources.contains(src)) {
                videoSources.add(fixUrl(src))
            }
        }

        // 2. Target valid player iframes (excluding ads, trackers, or general UI widgets)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && 
                !src.contains("googletagmanager") && 
                !src.contains("cloudflare") && 
                !src.contains("ads") && 
                !src.contains("syndication") &&
                !videoSources.contains(src)) {
                videoSources.add(fixUrl(src))
            }
        }

        // Fallback: If no explicit video tags or iframes are found, pass the post URL itself
        if (videoSources.isEmpty()) {
            videoSources.add(url)
        }

        // Map each valid source/iframe strictly to an Episode item
        val episodes = videoSources.mapIndexed { index, sourceUrl ->
            newEpisode(sourceUrl) {
                name = if (videoSources.size == 1) "Full Video" else "Source ${index + 1}"
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
                // If fallback page URL was passed, re-scrape inline players
                val document = app.get(data, headers = mainHeaders).document
                
                document.select("video, source").forEach { source ->
                    val vUrl = source.attr("src").ifBlank { source.attr("data-src") }
                    if (vUrl.isNotBlank()) {
                        val fixedUrl = fixUrl(vUrl)
                        val type = if (fixedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback.invoke(
                            newExtractorLink(name, name, fixedUrl, type) {
                                this.referer = mainUrl
                            }
                        )
                    }
                }
                
                document.select("iframe").forEach { iframe ->
                    val iUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                    if (iUrl.isNotBlank() && !iUrl.contains("ads")) {
                        loadExtractor(fixUrl(iUrl), data, subtitleCallback, callback)
                    }
                }
            }
            else -> {
                // Standard third-party extractor link handling for embedded players
                loadExtractor(data, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}