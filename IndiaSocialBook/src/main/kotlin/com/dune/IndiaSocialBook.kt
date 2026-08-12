package com.dune

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class IndiaSocialBook : MainAPI() {
    override var mainUrl = "https://indiasocialbook.com"
    override var name = "IndiaSocialBook"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.Video)

    private val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/videos/" to "Videos"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }

        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("article, div.item, div.post-box, div.video-item, div.masonry-item, div.elementor-post")
        val home = items.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2 a, h3 a, a.title, h2.entry-title a, a.elementor-post__thumbnail__link") ?: return null
        val title = titleElement.text().trim().ifBlank { 
            titleElement.attr("title").trim()
        }.ifBlank { return null }
        
        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        
        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("src") 
                ?: imgElement?.attr("data-src") 
                ?: imgElement?.attr("data-lazy-src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = "$mainUrl/page/$page/?s=$query"
        val document = app.get(searchUrl, headers = mainHeaders).document
        val items = document.select("article, div.item, div.post-box, div.video-item, div.masonry-item, div.elementor-post")
        val results = items.mapNotNull { it.toSearchResult() }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Unknown Title"
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.post-thumbnail img, .entry-content img, .elementor-post__thumbnail img")?.attr("src")
        )
        val description = document.selectFirst("div.entry-content, .description, .elementor-widget-theme-post-content")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.posterHeaders = mainHeaders
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("IndiaSocialBook", "Loading Links for URL: $data")
        val res = app.get(data, headers = mainHeaders)
        val document = res.document
        val htmlContent = res.text

        // 1. Harvest standard iframes and embed elements
        val iframeLinks = document.select("iframe, embed, object").mapNotNull { 
            it.attr("src").ifEmpty { it.attr("data-src") } 
        }

        // 2. Harvest anchor links pointing to video hosts inside content area
        val anchorLinks = document.select("div.entry-content a, .video-container a, a.external").mapNotNull { 
            it.attr("href") 
        }

        val allDiscoveredLinks = (iframeLinks + anchorLinks).distinct()
        Log.d("IndiaSocialBook", "Discovered DOM links: $allDiscoveredLinks")

        for (link in allDiscoveredLinks) {
            if (link.isNotBlank() && (link.startsWith("http") || link.startsWith("//"))) {
                val fixed = fixUrl(link)
                Log.d("IndiaSocialBook", "Passing DOM link to loadExtractor: $fixed")
                loadExtractor(fixed, data, subtitleCallback, callback)
            }
        }

        // 3. Fallback: Parse hidden script blocks or embedded JSON players containing links
        document.select("script").forEach { script ->
            val scriptText = script.data()
            // Match URLs inside script variables (e.g. file: "...", src: "...", or direct player URLs)
            val regexMatches = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4|mkv)|https?://(?:www\.)?(?:streamwish|vidhide|dood|filemoon|voe|streamtape|lulustream)[^\s"'<>]+""").findAll(scriptText)
            for (match in regexMatches) {
                val scriptLink = match.value
                Log.d("IndiaSocialBook", "Discovered Script Regex link: $scriptLink")
                loadExtractor(scriptLink, data, subtitleCallback, callback)
            }
        }

        return true
    }
}