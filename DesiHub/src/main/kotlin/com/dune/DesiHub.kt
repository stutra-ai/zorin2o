package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.Jsoup

class DesiHub : MainAPI() {
    override var mainUrl = "https://desihub.tv"
    override var name = "DesiHub"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie, TvType.AsianDrama)

    private val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/videos/trending" to "Trending",
        "$mainUrl/videos/latest" to "Latest",
        "$mainUrl/videos/top-rated" to "Top Rated",
        "$mainUrl/categories" to "Categories"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }

        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.video-item, article.post, div.item, .well")

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
        val linkElement = this.selectFirst("a.video-url, h3 a, a")
        val href = fixUrlNull(linkElement?.attr("href")) ?: return null

        val imgElement = this.selectFirst("img")
        val title = imgElement?.attr("alt")?.trim()?.ifBlank { null }
            ?: linkElement?.attr("title")?.trim()?.ifBlank { null }
            ?: linkElement?.text()?.trim()?.ifBlank { null }
            ?: return null

        val posterUrl = fixUrlNull(imgElement?.attr("src") ?: imgElement?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.video-item, article.post, div.item")

        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h1.video-title, h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("div.video-player img, div.poster img")?.attr("src"))
        val description = document.select("div.video-description, .description").text().ifBlank { null }
        
        val tags = document.select("div.tags a, .video-tags a").mapNotNull { it.text().trim() }
        val actors = document.select("div.cast a, .actors a").mapNotNull { Actor(it.text()) }
        
        val recommendations = document.select("div.related-videos div.video-item, .sidebar-videos article")
            .mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mainHeaders
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title = this.selectFirst("img")?.attr("alt")?.trim() ?: this.selectFirst("a")?.text()?.trim()
        if (title.isNullOrBlank()) return null

        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

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
        val document = Jsoup.parse(res.text)

        val serverButtons = document.select("ul.playlist-items li, div.server-tabs button, .video-sources a")
        
        if (serverButtons.isEmpty()) {
            extractVideosFromHtml(res.text, "Source 1", subtitleCallback, callback)
        } else {
            for ((index, button) in serverButtons.withIndex()) {
                val sourceName = button.text().trim().ifBlank { "Source ${index + 1}" }
                val targetLink = fixUrlNull(button.attr("href")) ?: button.attr("data-url")

                if (!targetLink.isNullOrBlank()) {
                    try {
                        val serverRes = app.get(targetLink, headers = mainHeaders)
                        extractVideosFromHtml(serverRes.text, sourceName, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.d("DesiHub", "Error loading server $sourceName: ${e.message}")
                    }
                }
            }
        }

        return true
    }

    private suspend fun extractVideosFromHtml(
        html: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = Jsoup.parse(html)
        
        val hlsRegex = Regex("[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']")
        hlsRegex.findAll(html).forEach { match ->
            val hlsUrl = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = "$name $sourceName",
                    name = sourceName,
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                }
            )
        }

        val iframes = doc.select("iframe")
        for (iframe in iframes) {
            val iframeSrc = fixUrlNull(iframe.attr("src") ?: iframe.attr("data-src")) ?: continue
            loadExtractor(iframeSrc, subtitleCallback, callback)
        }
    }
}