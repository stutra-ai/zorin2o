package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class IndiaSocialBook : MainAPI() {
    override var mainUrl = "https://indiasocialbook.com/videos/"
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
        "$mainUrl/category/indian-sex-video/desi-porn/" to "Desi"
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
        val items = document.select("article, div.item, div.post-box, div.video-item, div.masonry-item")
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
        val titleElement = this.selectFirst("h2 a, h3 a, a.title, h2.entry-title a") ?: return null
        val title = titleElement.text().trim().ifBlank { return null }
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
        val items = document.select("article, div.item, div.post-box, div.video-item, div.masonry-item")
        val results = items.mapNotNull { it.toSearchResult() }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Unknown Title"
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.post-thumbnail img, .entry-content img")?.attr("src")
        )
        val description = document.selectFirst("div.entry-content, .description")?.text()?.trim()

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
        val document = app.get(data, headers = mainHeaders).document

        // Locate all embedded iframes, sources, or external player links pointing to your registered extractors
        val iframeLinks = document.select("iframe").mapNotNull { 
            it.attr("src").ifEmpty { it.attr("data-src") } 
        }
        val anchorLinks = document.select("a.external, .video-container a, .entry-content a").mapNotNull { 
            it.attr("href") 
        }

        val allLinks = (iframeLinks + anchorLinks).distinct()

        for (link in allLinks) {
            if (link.isNotBlank()) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}