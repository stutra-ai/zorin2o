package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log

class XNXX : MainAPI() {
    override var mainUrl = "https://www.xnxx.com"
    override var name = "XNXX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/todays-selection" to "Today's selection",
        "$mainUrl/search/familial_relations" to "Family",
        "$mainUrl/search/big_tits" to "Big Tits",
        "$mainUrl/search/deepthroat?top" to "Deepthroat",
        "$mainUrl/search/deep+throat?top" to "Deep Throat",
        "$mainUrl/search/rough?top" to "Rough",
        "$mainUrl/search/cum+in+mouth?top" to "Cum in mouth"
        "$mainUrl/search/cum+inside?top" to "Cum Inside",
        "$mainUrl/search/girlfriend?top" to "Girlfriend",
        "$mainUrl/search/arab" to "Arab",
        "$mainUrl/search/casting?top" to "Casting",
        "$mainUrl/search/creampie" to "Creampie",
        "$mainUrl/search/asian_woman" to "Asian",
        "$mainUrl/search/missionary?top" to "Missionary",
        "$mainUrl/search/outdoor?top" to "Outdoor",
        "$mainUrl/search/cougar?top" to "Cougar",
        "$mainUrl/search/stepmom+and+stepson?top" to "Stepmom and Stepson",
        "$mainUrl/search/latina" to "Latina",
        "$mainUrl/search/stepdaughter?top" to "Stepdaughter",
        "$mainUrl/search/step+daughter?top" to "Step Daughter",
        "$mainUrl/search/pov?top" to "Pov",
        "$mainUrl/search/cowgirl?top" to "Cowgirl",
        "$mainUrl/search/real?top" to "Real",
        "$mainUrl/search/blowjob" to "Blowjob",
        "$mainUrl/search/cheating?top" to "Cheating",
        "$mainUrl/search/horny?top" to "Horny",
        "$mainUrl/search/double+penetration?top" to "Double penetration",
        "$mainUrl/search/exotic" to "Exotic",
        "$mainUrl/search/hardsex?top" to "Hardsex",
        "$mainUrl/search/shaved_pussy" to "Shaved Pussy",
        "$mainUrl/search/curvy?top" to "Curvy",
        "$mainUrl/search/virtual_reality" to "Virtual Realtity",
        "$mainUrl/search/couple?top" to "Couple",
        "$mainUrl/search/facial" to "Facial",
        "$mainUrl/search/brunette" to "Brunette",
        "$mainUrl/search/dirty+talk?top" to "Dirty Talk",
        "$mainUrl/search/reverse+cowgirl?top" to "Reverse Cowgirl",
        "$mainUrl/search/18+year+old?top" to "!8 Year Old",
        "$mainUrl/search/caught?top" to "Caught",
        "$mainUrl/search/fuck?top" to "Fu@k",
        "$mainUrl/search/cum+in+pussy?top" to "Cum in pussy",
        "$mainUrl/search/cute?top" to "Cute",
        "$mainUrl/search/cheating+wife?top" to "Cheating Wife",
        "$mainUrl/search/step+fantasy?top" to "Step Fantasy",
        "$mainUrl/search/roleplay?top" to "Roleplay",
        "$mainUrl/search/moaning?top" to "Moaning",
        "$mainUrl/search/fantasy?top" to "Fantasy",
        "$mainUrl/search/tight+pussy?top" to "Tight Pussy",
        "$mainUrl/search/hard+fuck?top" to "Hard Fu@k",
        "$mainUrl/search/car?top" to "Car",
        "$mainUrl/search/bikini?top" to "Bikini",
        "$mainUrl/search/riding?top" to "Riding",
        "$mainUrl/search/russian?top" to "Russian",
        "$mainUrl/search/hijab?top" to "Hijab",
        "$mainUrl/search/solo_and_masturbation" to "Solo Masturbation",
        "$mainUrl/search/bukkake" to "Bukkake",
        "$mainUrl/search/hot?top" to "Hot",
        "$mainUrl/search/real+orgasm?top" to "Real orgasm",
        "$mainUrl/search/rough+sex?top" to "Rough sex",
        "$mainUrl/search/indian" to "India / Indian girls",
        "$mainUrl/search/old?top" to "Old",
        "$mainUrl/search/outdoor?top" to "Outdoor",
        "$mainUrl/search/cougar?top" to "Cougar" 
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            "$base/$page"
        }

        val document = app.get(url, headers = mainHeaders).document
        // Comprehensive selectors to target video cards layout on XNXX
        val items = document.select("div.mozaique div.thumb-block, div.thumb-block, .magnum-block")

        val home = items.mapNotNull { it.toSearchResponse() }
        val hasNext = home.isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("p.title a") 
            ?: this.selectFirst("a.title") 
            ?: this.selectFirst("a[title]") 
            ?: return null

        val title = titleElement.attr("title").trim().ifBlank { titleElement.text().trim() }
        if (title.isBlank()) return null
        
        val rawHref = titleElement.attr("href")
        val href = fixUrlNull(if (rawHref.startsWith("http")) rawHref else "$mainUrl$rawHref") ?: return null

        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src") 
            ?: imgElement?.attr("src") 
            ?: imgElement?.attr("data-original")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/search/$query/$page"
        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.mozaique div.thumb-block, div.thumb-block")

        val results = items.mapNotNull { it.toSearchResponse() }
        val hasNext = results.isNotEmpty()

        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("meta[property='og:description']")?.attr("content") ?: ""
        
        val tags = document.select("span.metadata-row.tags a, .video-metadata .tag").mapNotNull { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mainHeaders
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = mainHeaders)
        val html = res.text

        // Parse native video links embedded inside the XNXX JavaScript player variables
        val highQualRegex = Regex("setVideoUrlHigh\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)")
        val lowQualRegex = Regex("setVideoUrlLow\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)")
        val lowQualAltRegex = Regex("setVideoUrlVLow\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)")

        val highUrl = highQualRegex.find(html)?.groupValues?.get(1)
        val lowUrl = lowQualRegex.find(html)?.groupValues?.get(1) ?: lowQualAltRegex.find(html)?.groupValues?.get(1)

        if (highUrl != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name 1080p/720p",
                    url = highUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                }
            )
        }

        if (lowUrl != null && lowUrl != highUrl) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name 480p/360p",
                    url = lowUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                }
            )
        }

        return highUrl != null || lowUrl != null
    }
}