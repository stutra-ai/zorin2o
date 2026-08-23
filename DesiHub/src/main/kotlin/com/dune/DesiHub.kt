package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class DesiHub : MainAPI() {
    override var mainUrl = "https://desihub.tv"
    override var name = "DesiHub"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/videos" to "Latest",
        "$mainUrl/videos?sort=most_viewed" to "Most Viewed",
        "$mainUrl/videos?sort=top_rated" to "Top Rated",
        "$mainUrl/uncensored" to "Uncensored"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        }
        
        // Fetch using AJAX headers to capture dynamically rendered or async-loaded grids
        val document = app.get(pageUrl, headers = ajaxHeaders).document
        
        val list = document.select("div.video-card, article.item, div.item, .list-videos .item, article, div.box").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
        
        val hasNext = document.selectFirst("a.next, .pagination-next, a:contains(Next)") != null
        return newHomePageResponse(HomePageList(request.name, list, isHorizontalImages = true), hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = if (page <= 1) "$mainUrl/search?q=$query" else "$mainUrl/search?q=$query&page=$page"
        val document = app.get(searchUrl, headers = ajaxHeaders).document
        
        val results = document.select("div.video-card, article.item, div.item, .list-videos .item, article, div.box").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
        
        val hasNext = document.selectFirst("a.next, .pagination-next, a:contains(Next)") != null
        return newSearchResponseList(results, hasNext)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = this.selectFirst("a.title, a.video-title, a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        
        val title = anchor.text().trim().ifEmpty { anchor.attr("title").trim() }
        if (title.isEmpty()) return null

        val img = this.selectFirst("img") ?: anchor.selectFirst("img")
        val rawImg = img?.attr("data-src")?.ifEmpty { img.attr("data-lazy-src") } ?: ""
        val poster = fixUrlNull(if (rawImg.isNotEmpty()) rawImg else img?.attr("src"))
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1).list
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = ajaxHeaders).document
        
        val title = document.selectFirst("h1.title, h1.video-title, h1")?.text()?.trim() ?: "DesiHub Video"
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val description = document.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
        
        val tags = document.select(".video-tags a, .categories a, .tags a").map { it.text().trim() }
        
        val recommendations = document.select("div.video-card, article.item, div.item").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }

        val actors = document.select(".video-actors a, .actors a").map { 
            Actor(it.text().trim(), null) 
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = ajaxHeaders).document
        var foundAny = false

        val elements = document.select("iframe, source, video, .player-container iframe")
        
        elements.forEachIndexed { index, element ->
            val src = element.attr("src").ifEmpty { element.attr("data-src") }
            if (src.isNotBlank()) {
                val fixedUrl = fixUrl(src) ?: return@forEachIndexed
                val label = "$name #${index + 1}"

                if (fixedUrl.contains(".mp4") || fixedUrl.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            label,
                            fixedUrl,
                            "$mainUrl/"
                        ) {
                            this.type = if (fixedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundAny = true
                } else {
                    safeApiCall {
                        loadExtractor(fixedUrl, "$mainUrl/", subtitleCallback, callback)
                        foundAny = true
                    }
                }
            }
        }

        return foundAny
    }
}