package com.dune

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import java.net.URLDecoder

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
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = fetchWithAntiBot(url)
        val items = document.select("div.video-item, article, div.card, div.thumb-block")

        val home = items.mapNotNull { it.toSearchResponse() }
        val hasNext = home.isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    private suspend fun fetchWithAntiBot(url: String): org.jsoup.nodes.Document {
        var res = app.get(url, headers = mainHeaders)
        if (res.code == 403 || res.code == 503 || res.text.contains("captcha", ignoreCase = true)) {
            try {
                val captchaToken = APIHolder.getCaptchaToken(url, mainHeaders["User-Agent"] ?: "")
                if (captchaToken != null) {
                    val customHeaders = mainHeaders.toMutableMap()
                    customHeaders["X-Captcha-Token"] = captchaToken
                    res = app.get(url, headers = customHeaders)
                }
            } catch (_: Exception) {}
        }
        return res.document
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null

        val imgElement = this.selectFirst("img")
        val title = imgElement?.attr("alt")?.trim()?.ifBlank { null }
            ?: linkElement.attr("title").trim().ifBlank { null }
            ?: linkElement.text().trim().ifBlank { null }
            ?: this.selectFirst("h2, h3")?.text()?.trim()
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
        val items = document.select("div.video-item, article, div.card, div.thumb-block")
        val results = items.mapNotNull { it.toSearchResponse() }
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val url = "$mainUrl/videos/search?q=$query&page=1"
        val document = fetchWithAntiBot(url)
        return document.select("div.video-item, article, div.card, div.thumb-block").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchWithAntiBot(url)

        val title = document.selectFirst("h1.video-title, h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("div.video-description, .description")?.text()?.trim()

        val tags = document.select("div.tags a, .video-tags a, span.tags-links a").mapNotNull { it.text().trim() }
        val actors = document.select("div.actors a, .cast a, span.actor-links a").mapNotNull { Actor(it.text()) }

        val recommendations = document.select(
            "div.related-video div.thumb-block, div.related-videos article, " +
            "div.related-posts article, div.card, div.thumb-block, article.post"
        ).mapNotNull { it.toRecommendationResult() }.distinctBy { it.url }

        val contentArea = document.selectFirst("div.video-player, div.entry-content, article.post") ?: document
        val tabNavs = contentArea.select("ul.tab-nav li")
        
        val episodes = mutableListOf<Episode>()

        if (tabNavs.isNotEmpty()) {
            tabNavs.mapIndexed { index, el ->
                val tabName = el.text().trim().ifEmpty { "Part ${index + 1}" }
                val targetTabId = el.attr("data-tab")
                
                val iframeSrc = if (!targetTabId.isNullOrBlank()) {
                    contentArea.selectFirst("div#$targetTabId iframe")?.attr("src")
                } else null
                
                val resolvedIframe = iframeSrc ?: contentArea.select("div.tab-pane iframe").getOrNull(index)?.attr("src")
                val episodeData = if (!resolvedIframe.isNullOrBlank()) fixUrl(resolvedIframe) else "$url#tab_$index"

                newEpisode(episodeData) {
                    this.name = tabName
                    this.episode = index + 1
                }
            }.let { episodes.addAll(it) }
        } else {
            val validIframes = contentArea.select("iframe").filter {
                val src = it.attr("src")
                !src.isBlank() && src != "about:blank" && !src.contains("googlesyndication")
            }

            if (validIframes.isNotEmpty()) {
                validIframes.mapIndexed { index, iframe ->
                    val iframeSrc = iframe.attr("src")
                    newEpisode(fixUrl(iframeSrc)) {
                        this.name = if (validIframes.size > 1) "Part ${index + 1}" else title
                        this.episode = index + 1
                    }
                }.let { episodes.addAll(it) }
            } else {
                episodes.add(newEpisode(url) { this.name = title; this.episode = 1 })
            }
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster; this.posterHeaders = mainHeaders; this.plot = description; this.tags = tags; this.recommendations = recommendations; addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster; this.posterHeaders = mainHeaders; this.plot = description; this.tags = tags; this.recommendations = recommendations; addActors(actors)
            }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        return this.toSearchResponse()
    }

    private suspend fun extractVideosFromHtml(htmlContent: String, refererUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        val targets = mutableListOf(htmlContent)

        val qParamRegex = "[?&]q=([^&\"'\\s]+)".toRegex(RegexOption.IGNORE_CASE)
        qParamRegex.findAll(htmlContent).forEach { match ->
            try {
                var rawParam = match.groupValues[1]
                var previous: String
                do {
                    previous = rawParam
                    rawParam = URLDecoder.decode(rawParam, "UTF-8")
                } while (rawParam != previous)

                val cleanBase64 = rawParam.replace(Regex("[^A-Za-z0-9+/=]"), "")
                val paddedBase64 = cleanBase64.padEnd((cleanBase64.length + 3) / 4 * 4, '=')

                val decodedBytes = Base64.decode(paddedBase64, Base64.DEFAULT)
                val decodedString = String(decodedBytes, Charsets.UTF_8)
                
                var unescapedHtml = decodedString
                do {
                    previous = unescapedHtml
                    unescapedHtml = URLDecoder.decode(unescapedHtml, "UTF-8")
                } while (unescapedHtml != previous)

                targets.add(decodedString)
                targets.add(unescapedHtml)
            } catch (_: Exception) {}
        }

        val streamUrlRegex = Regex("[\"'](https?://[^\"']+\\.(m3u8|mp4|ts)[^\"']*)[\"']")
        for (target in targets) {
            streamUrlRegex.findAll(target).forEach { match ->
                var videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("src=")) {
                    videoUrl = videoUrl.substringAfter("src=").trim('"', '\'')
                }

                if (!videoUrl.contains("ads") && !videoUrl.contains("track") && !videoUrl.contains("googlesyndication")) {
                    found = true
                    val isM3u8 = videoUrl.contains(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = fixUrl(videoUrl),
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = refererUrl
                            this.headers = mainHeaders
                        }
                    )
                }
            }
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = data.substringBefore("#")
        var foundLinks = false

        try {
            // Direct evaluation of target URL content
            val document = if (targetUrl.contains("player-x.php")) {
                app.get(targetUrl, headers = mainHeaders)
            } else {
                fetchWithAntiBot(targetUrl)
            }

            if (extractVideosFromHtml(document.text, targetUrl, callback)) {
                foundLinks = true
            }

            // Fallback: intercept via standard WebView helper if direct GET fails
            if (!foundLinks) {
                val interceptedLinks = loadExtractor(targetUrl)
                if (!interceptedLinks.isNullOrEmpty()) {
                    interceptedLinks.forEach { link ->
                        callback.invoke(link)
                        foundLinks = true
                    }
                }
            }

            // Iterate through any remaining page iframes
            document.select("iframe").forEach { iframe ->
                val iframeSrc = fixUrl(iframe.attr("src"))
                if (iframeSrc.isNotBlank() && iframeSrc.startsWith("http") && !iframeSrc.contains("googlesyndication")) {
                    if (extractVideosFromHtml(iframeSrc, targetUrl, callback)) {
                        foundLinks = true
                    }
                    try {
                        val iframeDoc = app.get(iframeSrc, headers = mapOf("Referer" to targetUrl) + mainHeaders)
                        if (extractVideosFromHtml(iframeDoc.text, iframeSrc, callback)) {
                            foundLinks = true
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.d("Cloudstream", "Error loading links: ${e.message}")
        }

        return foundLinks
    }
}