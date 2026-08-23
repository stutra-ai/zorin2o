package com.dune

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class Desihub : MainAPI() {
    override var mainUrl = "https://desihub.tv"
    override var name = "DesiHub"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/videos?sort=latest" to "Latest",
        "${mainUrl}/videos?sort=most_viewed" to "Most Viewed",
        "${mainUrl}/videos?sort=top_rated" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}&page=$page"
        val res = app.get(url).document
        val home = res.select("div.video-card, article.video-item, div.item").mapNotNull {
            it.mainPageResults()
        }
        val hasNext = res.selectFirst("a:contains(Next), .pagination-next") != null
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
        val url = if (page <= 1) "$mainUrl/search?q=$query" else "$mainUrl/search?q=$query&page=$page"
        val res = app.get(url).document
        val results = res.select("div.video-card, article.video-item, div.item").mapNotNull {
            it.mainPageResults()
        }
        val hasNext = res.selectFirst("a:contains(Next), .pagination-next") != null
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
        val title = res.selectFirst("h1.video-title, h1")?.text()?.trim() ?: return null
        val poster = res.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        val recommendations = res.select("div.related-videos div.video-card, .sidebar div.item").mapNotNull {
            val link = it.selectFirst("a") ?: return@mapNotNull null
            val rectitle = link.text().trim()
            val rechref = fixUrl(link.attr("href"))
            val img = it.selectFirst("img") ?: return@mapNotNull null
            val recposter = img.attr("data-src").ifEmpty { img.attr("src") }

            newMovieSearchResponse(rectitle, rechref, TvType.NSFW) {
                this.posterUrl = fixUrlNull(recposter)
            }
        }

        val actorsList = res.select(".video-actors a, .actors-list a").map {
            Actor(it.text().trim(), null)
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = res.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
            this.tags = res.select(".video-tags a, .categories a").map { it.text().trim() }
            this.recommendations = recommendations
            addActors(actorsList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data).document
        
        // Handles multiple video sources/iframes on a single page
        val sources = res.select("iframe, source, video").mapNotNull { element ->
            element.attr("src").takeIf { it.isNotEmpty() }
        }.toMutableList()

        var loadedAny = false
        sources.forEachIndexed { index, sourceUrl ->
            val fixedUrl = fixUrl(sourceUrl)
            if (fixedUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name #${index + 1}",
                        fixedUrl
                    ) {
                        this.type = if (fixedUrl.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                        this.quality = Qualities.Unknown.value
                        this.referer = "$mainUrl/"
                    }
                )
                loadedAny = true
            }
        }

        return loadedAny
    }
}