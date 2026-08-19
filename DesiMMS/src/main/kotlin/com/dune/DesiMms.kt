package com.kraptor

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
        val home = document.select("article.post, div.item-content, div.post-box").mapNotNull { it.toSearchResult() }
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
        return document.select("article.post, div.item-content, div.post-box").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("h1.entry-title, h1.title").text().trim()
        val poster = document.select("div.entry-content img, div.post-content img").first()?.attr("src")
        val description = document.select("div.entry-content, div.post-content").text().trim()

        val videoLinks = mutableSetOf<String>()
        val imageLinks = mutableListOf<String>()

        // Extract media links and image elements
        document.select("div.entry-content a, div.post-content a").forEach { element ->
            val href = element.attr("href")
            if (href.matches(Regex(".*\\.(jpg|jpeg|png|webp)(\\?.*)?$?", RegexOption.IGNORE_CASE))) {
                imageLinks.add(href)
            } else if (href.contains("bunkr") || href.contains("streamtape") || href.contains("embed") || href.contains("turbo")) {
                videoLinks.add(href)
            }
        }

        // Fallback: grab standard inline post images if no direct links detected
        if (imageLinks.isEmpty()) {
            document.select("div.entry-content img, div.post-content img").forEach { img ->
                val src = img.attr("data-src").ifEmpty { img.attr("src") }
                if (src.isNotEmpty()) {
                    imageLinks.add(src)
                }
            }
        }

        // If images are present, trigger plugin gallery hook context seamlessly
        if (imageLinks.isNotEmpty()) {
            plugin.loadGallery(title, imageLinks)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, videoLinks.toList()) {
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
        val links = data.split(",")
        for (link in links) {
            if (link.isNotBlank()) {
                loadExtractor(link.trim(), subtitleCallback, callback)
            }
        }
        return true
    }
}