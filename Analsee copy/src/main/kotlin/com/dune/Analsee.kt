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
            if (request.data.endsWith("/")) {
                "${request.data}$page/"
            } else {
                "${request.data}/$page/"
            }
        }

        val res = app.get(url, headers = mainHeaders)
        val document = res.document
        
        // Comprehensive fallback selector to catch items across all layout types
        val items = document.select("div.th, div.item, article, div.video-item")
        Log.d("Analsee", "Found ${items.size} items for URL: $url")

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
        val linkElement = this.selectFirst("a.thumb, a") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null

        val imgElement = this.selectFirst("img")
        
        val title = this.selectFirst("span.thumb_title, .title, h3, h4")?.text()?.trim()
            ?: imgElement?.attr("alt")?.trim()?.ifBlank { null }
            ?: linkElement.attr("title").trim().ifBlank { null }
            ?: linkElement.text().trim().ifBlank { null }
            ?: return null

        if (title.contains("Categories", ignoreCase = true) || href.contains("/category/")) return null

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
        val items = document.select("div.th, div.item, article, div.video-item")

        val results = items.mapNotNull { it.toSearchResponse() }
        val hasNext = results.isNotEmpty()

        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val url = "$mainUrl/search/$query/1/"
        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.th, div.item, article, div.video-item")
        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(
            document.selectFirst("video")?.attr("poster") 
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val description = document.selectFirst("div.description, div.info, .video-description")?.text()?.trim()

        val tags = document.select("div.tags a, .video-tags a, .tags-list a").mapNotNull { it.text().trim() }
        val actors = document.select("div.models a, .cast a, .models-list a").mapNotNull { Actor(it.text()) }

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
        val res = app.get(data, headers = mainHeaders)
        val htmlContent = res.text

        // Extract direct video source URLs or streams embedded in script parameters/video tags
        val videoSource = Regex("url\\s*:\\s*['\"](https?://[^'\"]+\\.m3u8[^'\"]*)['\"]").find(htmlContent)?.groupValues?.get(1)
            ?: Regex("file\\s*:\\s*['\"](https?://[^'\"]+)['\"]").find(htmlContent)?.groupValues?.get(1)
            ?: Regex("src\\s*:\\s*['\"](https?://[^'\"]+\\.mp4[^'\"]*)['\"]").find(htmlContent)?.groupValues?.get(1)
            ?: org.jsoup.Jsoup.parse(htmlContent).selectFirst("video source")?.attr("src")
            ?: org.jsoup.Jsoup.parse(htmlContent).selectFirst("video")?.attr("src")

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