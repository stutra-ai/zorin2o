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

        val document = fetchWithAntiBot(url)
        val items = document.select("div.video-item, article, div.card, div.thumb-block")

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
        val hasNext = results.isNotEmpty()

        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val url = "$mainUrl/videos/search?q=$query&page=1"
        val document = fetchWithAntiBot(url)
        val items = document.select("div.video-item, article, div.card, div.thumb-block")
        return items.mapNotNull { it.toSearchResponse() }
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

        // Multi-part detection (Tabs, buttons, or multiple iframes)
        val contentArea = document.selectFirst("div.entry-content, article.post, div.video-player, div.player-container") ?: document
        val tabNavs = contentArea.select("ul.tab-nav li, div.player-tabs button, .video-parts a, ul.nav-tabs li")
        
        val validIframes = contentArea.select("iframe, embed").filter {
            val src = it.attr("src")
            !src.isBlank() && src != "about:blank" && !src.contains("googlesyndication")
        }

        val episodes = mutableListOf<Episode>()

        if (tabNavs.isNotEmpty()) {
            tabNavs.mapIndexed { index, el ->
                val tabName = el.text().trim().ifEmpty { "Part ${index + 1}" }
                val targetTabId = el.attr("data-tab") ?: el.attr("data-target")
                
                val iframeSrc = if (!targetTabId.isNullOrBlank()) {
                    contentArea.selectFirst("div.tab-content div#$targetTabId iframe, div#$targetTabId iframe, div$targetTabId iframe")?.attr("src")
                } else null
                
                val resolvedIframe = iframeSrc ?: contentArea.select("div.tab-pane iframe, div.panel iframe, iframe").getOrNull(index)?.attr("src")
                val episodeData = if (!resolvedIframe.isNullOrBlank()) fixUrl(resolvedIframe) else "$url#tab_$index"

                newEpisode(episodeData) {
                    this.name = tabName
                    this.episode = index + 1
                }
            }.let { episodes.addAll(it) }
        } else if (validIframes.size > 1) {
            validIframes.mapIndexed { index, iframe ->
                val iframeSrc = iframe.attr("src")
                val episodeData = if (iframeSrc.isNotBlank()) fixUrl(iframeSrc) else "$url#iframe_$index"
                newEpisode(episodeData) {
                    this.name = "Part ${index + 1}"
                    this.episode = index + 1
                }
            }.let { episodes.addAll(it) }
        } else {
            // Single video page
            episodes.add(newEpisode(url) { 
                this.name = title
                this.episode = 1 
            })
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.posterHeaders = mainHeaders
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.posterHeaders = mainHeaders
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        return this.toSearchResponse()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // If data points to an iframe or part tab link, fetch that specific target route
        val targetUrl = data.substringBefore("#")
        val document = fetchWithAntiBot(targetUrl)

        // If data is a direct iframe link from a tab
        var searchHtml = document.html()
        if (data.contains("#tab_") || data.contains("#iframe_")) {
            val index = data.substringAfter("_").toIntOrNull() ?: 0
            val iframes = document.select("iframe")
            if (index < iframes.size) {
                val iframeSrc = iframes[index].attr("src")
                if (iframeSrc.isNotBlank()) {
                    val frameRes = app.get(fixUrl(iframeSrc), headers = mainHeaders)
                    searchHtml += " " + frameRes.text
                }
            }
        }

        val streamUrlRegex = Regex("[\"'](https?://[^\"']+\\.(m3u8|mp4)[^\"']*)[\"']")
        val matches = streamUrlRegex.findAll(searchHtml)

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
                    this.headers = mainHeaders
                }
            )
        }

        if (!foundLinks) {
            val sourceApiRegex = Regex("source_url\\s*[:=]\\s*['\"]([^'\"]+)['\"]")
            val apiMatch = sourceApiRegex.find(searchHtml)?.groupValues?.get(1)
            if (!apiMatch.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(apiMatch),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = mainHeaders
                    }
                )
                foundLinks = true
            }
        }

        return foundLinks
    }
}