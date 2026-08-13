package com.dune

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DesiPornX : MainAPI() {
    override var mainUrl = "https://www.desipornx.org"
    override var name = "DesiPornX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    // Standard headers to prevent 403 blocks
    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/53.36")

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Most Recent",
        "$mainUrl/most_viewed/" to "Most Popular",
        "$mainUrl/new/" to "Last Added",
        "$mainUrl/longest/" to "Longest",
        "$mainUrl/top_rated/" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Logic: if page 1, use base url. If page > 1, add /page/number/
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        
        val res = app.get(url, headers = headers)
        val document = res.document
        val home = document.select("div.th").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = query.replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/search/$formattedQuery/" else "$mainUrl/search/$formattedQuery/page/$page/"
        
        val res = app.get(url, headers = headers)
        val document = res.document
        val results = document.select("div.th").mapNotNull { it.toSearchResult() }
        
        return newSearchResponseList(results, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("a") ?: return null
        val title = this.selectFirst("span.th_nm")?.text() ?: titleElement.text()
        val img = this.selectFirst("img")

        val poster = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))

        return newMovieSearchResponse(title, fixUrl(titleElement.attr("href")), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = headers)
        val document = res.document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: "DesiPornX Video"
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val recommendations = document.select("div.th").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers)
        val html = res.text

        // 1. Check for iframe sources (common for embedded players)
        val iframeSrc = res.document.selectFirst("iframe")?.attr("src")
        if (!iframeSrc.isNullOrEmpty()) {
            val resolved = fixUrl(iframeSrc)
            callback.invoke(newExtractorLink(name, name, resolved, ExtractorLinkType.VIDEO) { this.referer = data })
        }

        // 2. Regex scan for direct stream URLs (.mp4, .m3u8)
        val urlRegex = """(https?://[^\s"'+\\]+?\.(?:mp4|m3u8|webm)[^\s"'+\\]*)""".toRegex()
        urlRegex.findAll(html).forEach { match ->
            val foundUrl = match.value.replace("\\", "")
            if (!foundUrl.contains("ads") && !foundUrl.contains("doubleclick")) {
                callback.invoke(newExtractorLink(name, name, foundUrl, ExtractorLinkType.VIDEO) {
                    this.referer = data
                })
            }
        }

        return true
    }
}