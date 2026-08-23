package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

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
        mainUrl to "Home",
        "$mainUrl/category/desi" to "Desi",
        "$mainUrl/category/hindi" to "Hindi",
        "$mainUrl/category/milf" to "MILF",
        "$mainUrl/category/web-series" to "Web Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "${request.data}/"
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }

        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("article, div.item, div.box")

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
        val linkElement = this.selectFirst("a")
        val href = fixUrlNull(linkElement?.attr("href")) ?: return null

        val imgElement = this.selectFirst("img")
        val title = imgElement?.attr("alt")?.trim()?.ifBlank { null }
            ?: linkElement?.attr("title")?.trim()?.ifBlank { null }
            ?: linkElement?.text()?.trim()?.ifBlank { null }
            ?: this.selectFirst("h2, h3")?.text()?.trim()
            ?: return null

        val posterUrl = fixUrlNull(imgElement?.attr("src") ?: imgElement?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/page/$page/?s=$query"
        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("article, div.item")

        val results = items.mapNotNull { it.toSearchResponse() }
        val hasNext = results.isNotEmpty()

        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("div.poster img, div.entry-content img")?.attr("src"))
        val description = document.select("div.entry-content p").joinToString(" ") { it.text() }.ifBlank { null }
        
        val tags = document.select("span.tags a, rel-tag a").mapNotNull { it.text().trim() }
        val actors = document.select("span.cast a, .actors a").mapNotNull { Actor(it.text()) }

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
        val document = app.get(data, headers = mainHeaders).document

        // Handle multiple iframes or single video embeds dynamically
        val iframes = document.select("iframe")
        val videoSources = document.select("video source, source")

        var foundLinks = false

        // Loop through all embedded iframes (handles multi-part or multi-source pages)
        for ((index, iframe) in iframes.withIndex()) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                val fixedUrl = fixUrl(src)
                try {
                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    Log.d("DesiHub", "Error loading iframe $fixedUrl: ${e.message}")
                }
            }
        }

        // Loop through any direct source elements found on the page
        for ((index, source) in videoSources.withIndex()) {
            val src = source.attr("src")
            if (src.isNotBlank()) {
                val fixedUrl = fixUrl(src)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Direct ${index + 1}",
                        url = fixedUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                    }
                )
                foundLinks = true
            }
        }

        return foundLinks
    }
}