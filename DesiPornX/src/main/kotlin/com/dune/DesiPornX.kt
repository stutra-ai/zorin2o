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
        "$mainUrl/" to "Newest",
        "$mainUrl/most_viewed/" to "Most Popular",
        "$mainUrl/new/" to "Last Added",
        "$mainUrl/longest/" to "Longest",
        "$mainUrl/top_rated/" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/?p=$page"
        val res = app.get(url).document
        
        val home = res.select("div.th").mapNotNull {
            it.mainPageResults()
        }
        
        val hasNext = res.selectFirst("a.next, link[rel=\"next\"]") != null
        
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
        val url = if (page <= 1) "$mainUrl/?s=$query" else "$mainUrl/?s=$query&p=$page"
        val res = app.get(url).document
        
        val results = res.select("div.th").mapNotNull {
            it.mainPageResults()
        }
        
        val hasNext = res.selectFirst("a.next, link[rel=\"next\"]") != null
        
        return newSearchResponseList(results, hasNext)
    }

    private fun Element.mainPageResults(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val titleSpan = this.selectFirst("span.th_nm")
        val title = titleSpan?.text()?.trim() ?: link.text().trim()
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
        
        val title = res.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = res.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        
        val recommendations = res.select("div.th").mapNotNull {
            val link = it.selectFirst("a") ?: return@mapNotNull null
            val rectitle = it.selectFirst("span.th_nm")?.text()?.trim() ?: link.text().trim()
            val rechref = fixUrl(link.attr("href"))
            val img = it.selectFirst("img") ?: return@mapNotNull null
            val recposter = img.attr("data-src").ifEmpty { img.attr("src") }
            
            newMovieSearchResponse(rectitle, rechref, TvType.NSFW) {
                this.posterUrl = fixUrlNull(recposter)
            }
        }

        val actorslist = res.select("div.video-actors a, .actors-list a").map {
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
        val doc = app.get(data).document
        val resText = doc.html()
        
        val sourceRegex = Regex("'(https?://[^']+?\\.(?:mp4|m3u8)[^']*)'")
        val foundSources = sourceRegex.findAll(resText).map { it.groupValues[1] }.toSet()

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

        if (foundSources.isEmpty()) {
            val iframeSrc = doc.select("iframe").attr("src")
            if (iframeSrc.isNotEmpty()) {
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            }
        }

        return foundSources.isNotEmpty() || doc.select("iframe").isNotEmpty()
    }
}