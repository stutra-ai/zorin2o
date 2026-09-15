package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PinayCum : MainAPI() {
    override var mainUrl = "https://pinaycum.xyz"
    override var name = "PinayCum"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Adult)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val targetUrl = if (page == 1) {
            request.data
        } else {
            "$mainUrl/?page=$page"
        }

        val document = app.get(targetUrl).document
        val home = document.select("div.col, div.swiper-slide, article, .item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(request.name, home),
            hasNextPage = document.select("a.next, .pagination-next, li.page-item:last-child a").isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.select("a[href*='watch.php']").first() ?: this.select("a").first() ?: return null
        val href = fixUrl(linkElement.attr("href"))
        if (!href.contains("watch.php")) return null

        val title = this.select("h5, h6, .card-title, .title").text().ifEmpty {
            linkElement.attr("title").ifEmpty { "Video" }
        }

        val imgElement = this.select("img").first()
        val posterUrl = fixUrl(
            imgElement?.attr("data-src")?.ifEmpty { null }
                ?: imgElement?.attr("data-lazy-src")?.ifEmpty { null }
                ?: imgElement?.attr("src") ?: ""
        )

        return newMovieSearchResponse(title, href, TvType.Adult) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?search=$encodedQuery"
        val document = app.get(searchUrl).document

        return document.select("div.col, div.swiper-slide, article, .item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.select("h1, h2.title, .video-title").text().trim().ifEmpty { "PinayCum Video" }
        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.select(".description, .card-text, .info").text().trim()

        return newMovieLoadResponse(title, url, TvType.Adult, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Find all multi-server player tab buttons (e.g., Player 1, Player 2, Player 3, Player 4)
        val serverLinks = document.select("a.show_more[href*='s=']").map { fixUrl(it.attr("href")) }
        val linksToProcess = if (serverLinks.isNotEmpty()) serverLinks else listOf(data)

        // 2. Loop through each server variant page to grab its specific embedded player stream
        for (serverUrl in linksToProcess) {
            try {
                val serverDoc = if (serverUrl == data) document else app.get(serverUrl).document

                // Extract iframes or embedded player source containers
                val embeddedUrls = serverDoc.select("iframe, embed, video source, .player iframe")
                    .mapNotNull { it.attr("src").ifEmpty { it.attr("data-src") } }

                for (embedUrl in embeddedUrls) {
                    loadExtractor(fixUrl(embedUrl), serverUrl, subtitleCallback, callback)
                }

                // Scan script blocks for fallback video stream URLs, ignoring ad network traffic scripts
                val scripts = serverDoc.select("script").map { it.data() }
                for (script in scripts) {
                    Regex("""https?://[^\s"'<>]+(?:m3u8|mp4|embed|player)[^\s"'<>]*""").find(script)?.value?.let { directLink ->
                        if (!directLink.contains("impseeineclots") && !directLink.contains("cloudflare")) {
                            loadExtractor(fixUrl(directLink), serverUrl, subtitleCallback, callback)
                        }
                    }
                }
            } catch (_: Exception) {
                // Keep moving even if an individual server option fails to load
            }
        }

        return true
    }
}