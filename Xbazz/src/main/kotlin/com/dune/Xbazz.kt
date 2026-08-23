package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class Xbaaz : MainAPI() {
    override var mainUrl = "https://xbaaz.com"
    override var name = "Xbaaz"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    // Cloudflare-resistant headers mimicking a standard modern desktop browser
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
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
        
        val document = app.get(pageUrl, headers = headers).document
        
        val list = document.select("div.video-card, article.item, div.item, .list-videos .item, article, div.box").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
        
        val hasNext = document.selectFirst("a.next, .pagination-next, a:contains(Next)") != null
        return newHomePageResponse(HomePageList(request.name, list, isHorizontalImages = true), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl, headers = headers).document
        
        return document.select("div.video-card, article.item, div.item, .list-videos .item, article, div.box").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
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
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        
        val title = document.selectFirst("h1.title, h1.video-title, h1")?.text()?.trim() ?: "Xbaaz Video"
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val description = document.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
        
        val tags = document.select(".video-tags a, .categories a, .tags a").map { it.text().trim() }
        
        val recommendations = document.select("div.video-card, article.item, div.item").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }

        val actors = document.select(".video-actors a, .actors a").map { 
            Actor(it.text().trim(), null) 
        }

        // Check for multiple parts/episodes if available
        val episodes = mutableListOf<Episode>()
        val playlistElements = document.select(".playlist-item, .part-item, .episodes-list a, .video-parts a")
        
        if (playlistElements.isNotEmpty()) {
            playlistElements.forEachIndexed { index, element ->
                val epUrl = fixUrlNull(element.attr("href")) ?: url
                val epTitle = element.text().trim().ifEmpty { "Part ${index + 1}" }
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = 1
                        this.episode = index + 1
                        this.posterUrl = poster
                    }
                )
            }
        }

        // Return as a TV Series if multiple parts exist, otherwise as a standard Movie view
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

        val document = app.get(data, headers = headers).document
        var foundAny = false

        // Extract iframes or embedded video sources
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

        // Script configuration fallback regex matcher for direct stream links
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