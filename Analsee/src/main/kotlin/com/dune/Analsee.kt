package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class Analsee : MainAPI() {
    override var mainUrl = "https://www.analsee.com"
    override var name = "Analsee"
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
        "$mainUrl/" to "Home",
        "$mainUrl/videos/" to "Videos",
        "$mainUrl/categories/" to "Categories"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            // Adjust if pagination uses a different format on your target pages
            "${request.data.removeSuffix("/")}/$page/"
        }

        val document = app.get(url, headers = mainHeaders).document
        // Match the exact container class from your HTML snippet: div.th
        val items = document.select("div.th")

        val home = items.mapNotNull { it.toSearchResponse() }
        val hasNext = home.isNotEmpty()

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
        val linkElement = this.selectFirst("a.thumb") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null

        val imgElement = this.selectFirst("span.thumb_img img")
        
        // Target the explicit thumb_title span for clean title extraction
        val title = this.selectFirst("span.thumb_title")?.text()?.trim()
            ?: imgElement?.attr("alt")?.trim()?.ifBlank { null }
            ?: linkElement.attr("title").trim().ifBlank { null }
            ?: return null

        val posterUrl = fixUrlNull(
            imgElement?.attr("src") 
                ?: imgElement?.attr("data-src") 
                ?: imgElement?.attr("data-original")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/search/$query/$page/"
        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.th")

        val results = items.mapNotNull { it.toSearchResponse() }
        val hasNext = results.isNotEmpty()

        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val url = "$mainUrl/search/$query/1/"
        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.th")
        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(
            document.selectFirst("video")?.attr("poster") 
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val description = document.selectFirst("div.description, div.info")?.text()?.trim()

        val tags = document.select("div.tags a, .video-tags a").mapNotNull { it.text().trim() }
        val actors = document.select("div.models a, .cast a").mapNotNull { Actor(it.text()) }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mainHeaders
            this.plot = description
            this.tags = tags
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mainHeaders).document.toString()

        val videoSource = Regex("url\\s*:\\s*['\"](https?://[^'\"]+\\.m3u8[^'\"]*)['\"]").find(document)?.groupValues?.get(1)
            ?: Regex("file\\s*:\\s*['\"](https?://[^'\"]+)['\"]").find(document)?.groupValues?.get(1)
            ?: org.jsoup.Jsoup.parse(document).selectFirst("video source")?.attr("src")

        if (!videoSource.isNullOrBlank()) {
            val isM3u8 = videoSource.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoSource,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                }
            )
            return true
        }

        return false
    }
}