package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class Xbazz : MainAPI() {
    override var mainUrl = "https://xbaaz.com"
    override var name = "Xbazz"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept-Encoding" to "gzip, deflate, br",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/videos" to "Latest Videos",
        "$mainUrl/videos?sort=most_viewed" to "Most Viewed",
        "$mainUrl/videos?sort=top_rated" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        }
        
        // Use Cloudstream app.get with interceptor capability for cookies/challenges
        val document = app.get(pageUrl, headers = headers, allowRedirects = true).document
        
        var list = document.select("div.item, article, div.video-item, div.thumb-block, div.box, .well, div.col-grid, .video-card, div.col-sm-4, div.col-md-3, div.col-lg-3").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }

        if (list.isEmpty()) {
            list = document.select("a:has(img)").mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val fixedHref = if (href.startsWith("/")) "$mainUrl$href" else href
                
                val img = anchor.selectFirst("img") ?: return@mapNotNull null
                val title = anchor.attr("title").ifEmpty { img.attr("alt").ifEmpty { anchor.text() } }.trim()
                if (title.isEmpty() || title.length < 2) return@mapNotNull null

                val rawImg = img.attr("data-src").ifEmpty { img.attr("data-lazy-src") }
                val poster = fixUrlNull(if (rawImg.isNotEmpty()) rawImg else img.attr("src"))

                newMovieSearchResponse(title, fixedHref, TvType.NSFW) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }
        }
        
        val hasNext = document.selectFirst("a.next, .pagination-next, a:contains(Next), li.next a, a.pagi-next") != null
        return newHomePageResponse(HomePageList(request.name, list, isHorizontalImages = true), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl, headers = headers, allowRedirects = true).document
        
        var list = document.select("div.item, article, div.video-item, div.thumb-block, div.box, .well, div.col-grid, .video-card, div.col-sm-4, div.col-md-3").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }

        if (list.isEmpty()) {
            list = document.select("a:has(img)").mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val fixedHref = if (href.startsWith("/")) "$mainUrl$href" else href
                
                val img = anchor.selectFirst("img") ?: return@mapNotNull null
                val title = anchor.attr("title").ifEmpty { img.attr("alt").ifEmpty { anchor.text() } }.trim()
                if (title.isEmpty() || title.length < 2) return@mapNotNull null

                val rawImg = img.attr("data-src").ifEmpty { img.attr("data-lazy-src") }
                val poster = fixUrlNull(if (rawImg.isNotEmpty()) rawImg else img.attr("src"))

                newMovieSearchResponse(title, fixedHref, TvType.NSFW) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }
        }

        return list
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val fixedHref = if (href.startsWith("/")) "$mainUrl$href" else href
        
        val imgElement = this.selectFirst("img") ?: anchor.selectFirst("img") ?: return null
        val title = this.selectFirst(".title, .video-title, h3, h4, .card-title")?.text()?.trim()
            ?: anchor.attr("title").ifEmpty { imgElement.attr("alt").ifEmpty { anchor.text() } }.trim()
            
        if (title.isEmpty()) return null

        val rawImg = imgElement.attr("data-src").ifEmpty { imgElement.attr("data-lazy-src") }
        val poster = fixUrlNull(if (rawImg.isNotEmpty()) rawImg else imgElement.attr("src"))
        
        return newMovieSearchResponse(title, fixedHref, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers, allowRedirects = true).document
        
        val title = document.selectFirst("h1.title, h1.video-title, h1")?.text()?.trim() ?: "Xbazz Video"
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val description = document.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
        
        val tags = document.select(".video-tags a, .categories a, .tags a").map { it.text().trim() }
        
        val recommendations = document.select("div.item, article, div.video-item, div.thumb-block").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }

        val actors = document.select(".video-actors a, .actors a").map { 
            Actor(it.text().trim(), null) 
        }

        val episodes = mutableListOf<Episode>()
        val playlistElements = document.select(".playlist-item, .part-item, .episodes-list a, .video-parts a")
        
        if (playlistElements.isNotEmpty()) {
            playlistElements.forEachIndexed { index, element ->
                val epUrl = fixUrlNull(element.attr("href")) ?: url
                val fixedEpUrl = if (epUrl.startsWith("/")) "$mainUrl$epUrl" else epUrl
                val epTitle = element.text().trim().ifEmpty { "Part ${index + 1}" }
                episodes.add(
                    newEpisode(fixedEpUrl) {
                        this.name = epTitle
                        this.season = 1
                        this.episode = index + 1
                        this.posterUrl = poster
                    }
                )
            }
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(".mp4") || data.contains(".m3u8")) {
            callback.invoke(
                newExtractorLink(
                    name,
                    "$name Direct Stream",
                    data
                ) {
                    this.referer = "$mainUrl/"
                    this.type = if (data.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val document = app.get(data, headers = headers, allowRedirects = true).document
        var foundAny = false

        val elements = document.select("iframe, source, video, .player-container iframe, .embed-responsive iframe")
        elements.forEachIndexed { index, element ->
            val src = element.attr("src").ifEmpty { element.attr("data-src") }
            if (src.isNotBlank()) {
                val fixedUrl = fixUrl(src) ?: return@forEachIndexed
                val label = "$name Player #${index + 1}"

                if (fixedUrl.contains(".mp4") || fixedUrl.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            label,
                            fixedUrl
                        ) {
                            this.referer = "$mainUrl/"
                            this.type = if (fixedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundAny = true
                } else {
                    loadExtractor(fixedUrl, "$mainUrl/", subtitleCallback, callback)
                    foundAny = true
                }
            }
        }

        val scriptContent = document.select("script").html()
        val urlRegex = "(https?://[^\\s\"']+\\.(?:mp4|m3u8)[^\\s\"']*)".toRegex()
        
        urlRegex.findAll(scriptContent).forEach { match ->
            val rawUrl = match.value.replace("\\/", "/")
            if (rawUrl.isNotBlank() && !rawUrl.contains("ads")) {
                val cleanUrl = rawUrl.substringBefore("\"").substringBefore("'")
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name Direct Stream",
                        cleanUrl
                    ) {
                        this.referer = "$mainUrl/"
                        this.type = if (cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        this.quality = Qualities.Unknown.value
                    }
                )
                foundAny = true
            }
        }

        return foundAny
    }
}