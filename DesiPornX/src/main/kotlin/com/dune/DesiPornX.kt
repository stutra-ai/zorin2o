package com.dune

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class DesiPornX : MainAPI() {
    override var mainUrl = "https://desipornx.org"
    override var name = "DesiPornX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/videos" to "Newest",
        "$mainUrl/videos?sort=most_viewed" to "Most Viewed",
        "$mainUrl/videos?sort=top_rated" to "Top Rated",
        "$mainUrl/categories" to "Categories"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "$mainUrl/videos?page=$page"
        val res = app.get(url).document
        
        val home = res.select("div.video-box, article.video-item, div.item").mapNotNull {
            it.mainPageResults()
        }
        
        val hasNext = res.selectFirst("a.pagination-next, a.next, a:contains(Next)") != null
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) "$mainUrl/search?q=$query" else "$mainUrl/search?page=$page&q=$query"
        val res = app.get(url).document
        
        val results = res.select("div.video-box, article.video-item, div.item").mapNotNull {
            it.mainPageResults()
        }
        
        val hasNext = res.selectFirst("a.pagination-next, a.next, a:contains(Next)") != null
        
        return newSearchResponseList(results, hasNext)
    }

    private fun Element.mainPageResults(): SearchResponse? {
        val link = this.selectFirst("a.title, h3 a, a") ?: return null
        val title = link.text().trim()
        val href = fixUrlNull(link.attr("href")) ?: return null
        val img = this.selectFirst("img") ?: return null
        val poster = fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") })
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url).document
        
        val title = res.selectFirst("div.video-title h1, h1.title, h1")?.text()?.trim() ?: return null
        val poster = res.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        
        val recommendations = res.select("div.related-videos div.video-box, div.sidebar-videos article").mapNotNull {
            val link = it.selectFirst("a.title, h3 a, a") ?: return@mapNotNull null
            val rectitle = link.text().trim()
            val rechref = fixUrl(link.attr("href"))
            val img = it.selectFirst("img") ?: return@mapNotNull null
            val recposter = img.attr("data-src").ifEmpty { img.attr("src") }
            
            newMovieSearchResponse(rectitle, rechref, TvType.NSFW) {
                this.posterUrl = fixUrlNull(recposter)
            }
        }

        val actorslist = res.select("div.video-actors a,.actors-list a").map {
            Actor(it.text().trim(), null)
        }

        val tags = res.select("div.video-tags a, .tags-list a").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = res.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
            this.tags = tags
            this.recommendations = recommendations
            addActors(actorslist)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data).text
        
        // Regular expression fallback to find direct mp4 or m3u8 sources if embedded in scripts
        val sourceRegex = Regex("'(https?://[^']+?\\.(?:mp4|m3u8)[^']*)'")
        val foundSources = sourceRegex.findAll(res).map { it.groupValues[1] }.toSet()

        for (source in foundSources) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    source
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.type = if (source.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        }

        // Check for standard iframe video embeds if direct links are not found
        if (foundSources.isEmpty()) {
            val iframeSrc = res.select("iframe").attr("src")
            if (iframeSrc.isNotEmpty()) {
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            }
        }

        return foundSources.isNotEmpty()
    }
}