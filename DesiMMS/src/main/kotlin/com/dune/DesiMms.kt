package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class DesiMms(val plugin: DesiMmsPlugin) : MainAPI() {
    override var name = "DesiMMS"
    override var mainUrl = "https://desimms.net"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest",
        "$mainUrl/trending/page/" to "Trending"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("${request.data}$page").document
        val home = document.select("article.post, div.item-content").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("h2 a, h3 a, a.title").text().trim()
        val href = this.select("h2 a, h3 a, a.title").attr("href")
        val posterUrl = this.select("img").attr("data-src").ifEmpty {
            this.select("img").attr("src")
        }

        if (title.isEmpty() || href.isEmpty()) return null

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.post, div.item-content").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("h1.entry-title, h1.title").text().trim()
        val poster = document.select("div.entry-content img").first()?.attr("src")
        val description = document.select("div.entry-content").text().trim()

        // 1. Extract External Video Links (Bunkr, StreamTape, etc.)
        val videoLinks = mutableSetOf<String>()
        // 2. Extract Direct Image Links for Gallery View
        val imageLinks = mutableListOf<String>()

        document.select("div.entry-content a, div.post-content a").forEach { element ->
            val href = element.attr("href")
            if (href.matches(Regex(".*\\.(jpg|jpeg|png|webp)(\\?.*)?$?", RegexOption.IGNORE_CASE))) {
                imageLinks.add(href)
            } else if (href.contains("bunkr") || href.contains("streamtape") || href.contains("embed")) {
                videoLinks.add(href)
            }
        }

        // Also grab inline post images if they aren't wrapped in links
        document.select("div.entry-content img, div.post-content img").forEach { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            if (src.isNotEmpty() && !imageLinks.contains(src)) {
                imageLinks.add(src)
            }
        }

        // If images exist, bundle them as a custom action or extra recommendation payload
        // You can pass image collections into cloudstream actions or handle them via custom UI hooks.
        return newMovieLoadResponse(title, url, TvType.Movie, videoLinks.toList()) {
            this.posterUrl = poster
            this.plot = description
            // If you want to attach custom plugin metadata blocks or handle gallery triggers:
            // Add tags or recommendations to store image payload data if needed.
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = data.split(",")
        for (link in links) {
            if (link.isNotBlank()) {
                loadExtractor(link.trim(), subtitleCallback, callback)
            }
        }
        return true
    }
}