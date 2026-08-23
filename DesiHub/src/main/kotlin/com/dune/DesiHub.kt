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

    override val mainPage = mainPageOf(
        "$mainUrl/videos" to "Latest",
        "$mainUrl/videos?sort=most_viewed" to "Most Viewed",
        "$mainUrl/videos?sort=top_rated" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data}&page=$page"
        val document = app.get(pageUrl).document
        
        val list = document.select("div.video-item, div.item, article, div.card, div.video-card").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        val hasNext = document.selectFirst("a.next, .pagination-next, a:contains(Next)") != null
        return newHomePageResponse(HomePageList(request.name, list, isHorizontalImages = true), hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = if (page <= 1) "$mainUrl/search?q=$query" else "$mainUrl/search?q=$query&page=$page"
        val document = app.get(searchUrl).document
        
        val results = document.select("div.video-item, div.item, article, div.card, div.video-card").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        val hasNext = document.selectFirst("a.next, .pagination-next, a:contains(Next)") != null
        return newSearchResponseList(results, hasNext)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = this.selectFirst("a.title, h3 a, a.video-title, a") ?: return null
        val title = anchor.text().trim()
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        
        val img = this.selectFirst("img")
        val rawImg = img?.attr("data-src")
        val poster = fixUrlNull(if (!rawImg.isNullOrEmpty()) rawImg else img?.attr("src"))
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1).list
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.title, h1.video-title, h1")?.text()?.trim() ?: "DesiHub Video"
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val description = document.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
        
        val tags = document.select(".tags a, .categories a, .video-tags a").map { it.text().trim() }
        
        val recommendations = document.select("div.related-videos div.item, div.video-item, .sidebar article").mapNotNull { element ->
            element.toSearchResponse()
        }

        val actors = document.select(".actors a, .video-actors a").map { 
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
        val document = app.get(data).document
        var foundAny = false

        // Collect all potential video holders, iframes, or source elements to support multi-video pages
        val elements = document.select("iframe, source, video, .player-embed iframe, .video-container iframe, embed")
        
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
                    // Automatically pass embedded iframe players through Cloudstream's extractor resolvers
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