package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class IndiaSocialBook : MainAPI() {
    override var mainUrl = "https://indiasocialbook.com"
    override var name = "IndiaSocialBook"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)

    private val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/videos" to "Home",
        "$mainUrl/videos/trending" to "Trending",
        "$mainUrl/videos/latest" to "Latest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}/page/$page"
        }

        Log.d("Cloudstream", "MainPage URL: $url")

        val document = fetchWithAntiBot(url)
        val items = document.select("div.video-item, article, div.card")

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

    private suspend fun fetchWithAntiBot(url: String): org.jsoup.nodes.Document {
        var res = app.get(url, headers = mainHeaders)
        
        if (res.code == 403 || res.code == 503 || res.text.contains("captcha", ignoreCase = true)) {
            Log.d("Cloudstream", "Anti-bot/Captcha triggered on $url. Attempting to acquire token...")
            try {
                val captchaToken = APIHolder.getCaptchaToken(url, mainHeaders["User-Agent"] ?: "")
                if (captchaToken != null) {
                    val customHeaders = mainHeaders.toMutableMap()
                    customHeaders["X-Captcha-Token"] = captchaToken
                    res = app.get(url, headers = customHeaders)
                }
            } catch (e: Exception) {
                Log.d("Cloudstream", "Captcha handling failed: ${e.message}")
            }
        }
        return res.document
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a")
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

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/videos/search?q=$query&page=$page"
        val document = fetchWithAntiBot(url)
        val items = document.select("div.video-item, article, div.card")

        val results = items.mapNotNull { it.toSearchResponse() }
        val hasNext = results.isNotEmpty()

        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val url = "$mainUrl/videos/search?q=$query&page=1"
        val document = fetchWithAntiBot(url)
        val items = document.select("div.video-item, article, div.card")
        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchWithAntiBot(url)

        val title = document.selectFirst("h1.video-title, h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("div.video-description, .description")?.text()?.trim()

        val tags = document.select("div.tags a, .video-tags a").mapNotNull { it.text().trim() }
        val actors = document.select("div.actors a, .cast a").mapNotNull { Actor(it.text()) }

        val recommendations = document.select("div.related-video, div.card").mapNotNull { it.toRecommendationResult() }

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
        val title = this.selectFirst("a")?.attr("title")?.trim() 
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))

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
        val document = fetchWithAntiBot(data)

        val streamUrlRegex = Regex("[\"'](https?://[^\"']+\\.(m3u8|mp4)[^\"']*)[\"']")
        val matches = streamUrlRegex.findAll(document.html())

        var foundLinks = false
        for (match in matches) {
            val videoUrl = match.groupValues[1]
            if (videoUrl.contains("ads") || videoUrl.contains("track")) continue

            foundLinks = true
            val isM3u8 = videoUrl.contains(".m3u8")
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                }
            )
        }

        if (!foundLinks) {
            val sourceApiRegex = Regex("source_url\\s*[:=]\\s*['\"]([^'\"]+)['\"]")
            val apiMatch = sourceApiRegex.find(document.html())?.groupValues?.get(1)
            if (!apiMatch.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(apiMatch),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                    }
                )
                foundLinks = true
            }
        }

        return foundLinks
    }
}