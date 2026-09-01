package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.Jsoup

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
        "$mainUrl/home/" to "Home",
        "$mainUrl/best/" to "Best",
        "$mainUrl/new/" to "New",
        "$mainUrl/search/amateur" to "Amateur",
        "$mainUrl/search/hd" to "HD"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // XNXX pagination format: /home/2 or /best/2
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/${page}/"
        }

        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.mozaique div.thumb-block")

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
        val titleElement = this.selectFirst("p.title a") ?: return null
        val title = titleElement.attr("title").trim().ifBlank { titleElement.text().trim() }
        val href = fixUrlNull(titleElement.attr("href"))?.let { "$mainUrl$it" } ?: return null

        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src") ?: imgElement?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/search/$query/$page"
        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.mozaique div.thumb-block")

        val results = items.mapNotNull { it.toSearchResponse() }
        val hasNext = results.isNotEmpty()

        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query).results

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("meta[property='og:description']")?.attr("content") ?: ""
        
        // Extract tags/categories
        val tags = document.select("span.metadata-row.tags a").mapNotNull { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mainHeaders
            this.plot = description
            this.tags = tags
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

        // XNXX embeds direct video source links inside JavaScript variables on the watch page
        // Patterns typically look like: html5player.setVideoUrlHigh('...') or setVideoUrlLow('...')
        
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