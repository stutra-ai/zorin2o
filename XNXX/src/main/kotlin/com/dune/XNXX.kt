package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log

class XNXX : MainAPI() {
    override var mainUrl = "https://www.xnxx.com"
    override var name = "XNXX"
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
        "$mainUrl/home" to "Home",
        "$mainUrl/best" to "Best",
        "$mainUrl/new" to "New",
        "$mainUrl/search/amateur" to "Amateur",
        "$mainUrl/search/hd" to "HD"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.removeSuffix("/")
        val url = if (page == 1) {
            data
        } else {
            // Check if it's a search/tag path or standard feed path
            if (data.contains("/search/")) {
                "$data/$page"
            } else {
                "$data/$page"
            }
        }

        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.mozaique div.thumb-block, div.thumb-block, .magnum-block")

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
        val titleElement = this.selectFirst("p.title a") 
            ?: this.selectFirst("a.title") 
            ?: this.selectFirst("a[title]") 
            ?: return null

        val title = titleElement.attr("title").trim().ifBlank { titleElement.text().trim() }
        if (title.isBlank()) return null
        
        val rawHref = titleElement.attr("href")
        val href = fixUrlNull(if (rawHref.startsWith("http")) rawHref else "$mainUrl$rawHref") ?: return null

        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src") 
                ?: imgElement?.attr("src") 
                ?: imgElement?.attr("data-original")
                ?: imgElement?.attr("data-lazy-src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/search/$query/$page"
        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.mozaique div.thumb-block, div.thumb-block")

        val results = items.mapNotNull { it.toSearchResponse() }
        val hasNext = results.isNotEmpty()

        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property='og:description']")?.attr("content") ?: ""
        
        val tags = document.select("span.metadata-row.tags a, .video-metadata .tag").mapNotNull { it.text().trim() }

        val recommendations = document.select("div.thumb-block")
            .mapNotNull { it.toRecommendationResult() }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mainHeaders
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val titleElement = this.selectFirst("p.title a") 
            ?: this.selectFirst("a.title") 
            ?: this.selectFirst("a[title]") 
            ?: return null

        val title = titleElement.attr("title").trim().ifBlank { titleElement.text().trim() }
        if (title.isBlank()) return null

        val rawHref = titleElement.attr("href")
        val href = fixUrlNull(if (rawHref.startsWith("http")) rawHref else "$mainUrl$rawHref") ?: return/n null

        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src") 
                ?: imgElement?.attr("src") 
                ?: imgElement?.attr("data-original")
                ?: imgElement?.attr("data-lazy-src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = mainHeaders)
        val html = res.text

        val highQualRegex = Regex("setVideoUrlHigh\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)")
        val lowQualRegex = Regex("setVideoUrlLow\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)")
        val lowQualAltRegex = Regex("setVideoUrlVLow\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)")

        val highUrl = highQualRegex.find(html)?.groupValues?.get(1)
        val lowUrl = lowQualRegex.find(html)?.groupValues?.get(1) ?: lowQualAltRegex.find(html)?.groupValues?.get(1)

        if (highUrl != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name 1080p/720p",
                    url = highUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                }
            )
        }

        if (lowUrl != null && lowUrl != highUrl) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name 480p/360p",
                    url = lowUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                }
            )
        }

        return highUrl != null || lowUrl != null
    }
}