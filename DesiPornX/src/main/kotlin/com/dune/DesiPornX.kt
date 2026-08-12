package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DesiPornX : MainAPI() {
    override var mainUrl = "https://www.desipornx.com" // Replace with actual URL
    override var name = "DesiPornX"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Adult)

    override val mainPage = mainPageOf(
        "$mainUrl/latest/" to "Latest",
        "$mainUrl/popular/" to "Popular"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div.item-class").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a.title")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Adult) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query/").document
        return document.select("div.item-class").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title")?.text().orEmpty()
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("div.description")?.text()

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

        // Example: Extracting a direct video link or handling an iframe source
        val videoUrl = document.selectFirst("source")?.attr("src") ?: return false

        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                referer = mainUrl,
                quality = Qualities.P720.value,
                isM3u8 = videoUrl.contains(".m3u8")
            )
        )

        return true
    }
}