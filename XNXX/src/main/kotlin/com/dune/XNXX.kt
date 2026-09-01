package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class XNXX : MainAPI() {
    override var mainUrl = "https://www.xnxx.com"
    override var name = "XNXX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/todays-selection" to "Today's selection",
        "$mainUrl/search/familial_relations" to "Family",
        "$mainUrl/search/big_tits" to "Big Tits",
        "$mainUrl/search/deepthroat?top" to "Deepthroat",
        "$mainUrl/search/deep+throat?top" to "Deep Throat",
        "$mainUrl/search/rough?top" to "Rough",
        "$mainUrl/search/cum+in+mouth?top" to "Cum in mouth",
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
        val pageNum = if (page <= 1) "" else "$page/"
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/$pageNum"
        
        val document = app.get(url).document
        val home = document.select("div.mozaique div.thumb-block").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = query.replace(" ", "+")
        val pageNum = if (page <= 1) "" else "$page/"
        val url = "$mainUrl/search/$formattedQuery/$pageNum"
        
        val document = app.get(url).document
        val results = document.select("div.mozaique div.thumb-block").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("p.title a") ?: return null
        val imgElement = this.selectFirst("div.thumb img")
        
        val poster = fixUrlNull(
            imgElement?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: imgElement?.attr("src")
        )

        val href = fixUrl(titleElement.attr("href"))
        val title = titleElement.attr("title").ifEmpty { titleElement.text() }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).list

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1")?.text()?.trim() ?: return null
            
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        
        val tags = document.select("span.metadata-row.tags a.tag").map { it.text() }
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        
        val recommendations = document.select("div#related-video-container div.thumb-block").mapNotNull { toRecommendationResult() }
        val actors = document.select("span.metadata-row.pornstars a").map { Actor(it.text()) }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val titleElement = this.selectFirst("p.title a") ?: return null
        val imgElement = this.selectFirst("div.thumb img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: imgElement?.attr("src")
        )
        val title = titleElement.attr("title").ifEmpty { titleElement.text() }

        return newMovieSearchResponse(title, fixUrl(titleElement.attr("href")), TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = fixUrl(data)
        var videoLinkFound = false

        try {
            val html = app.get(url).text

            val lowUrl = html5Regex("setVideoUrlLow", html)
            val highUrl = html5Regex("setVideoUrlHigh", html)
            val hlsUrl = html5Regex("setVideoHLS", html)

            if (lowUrl != null) {
                callback.invoke(buildVideoLink("Low Quality", lowUrl))
                videoLinkFound = true
            }

            if (highUrl != null) {
                callback.invoke(buildVideoLink("High / HD", highUrl, Qualities.P720.value))
                videoLinkFound = true
            }

            if (hlsUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = "$name:HLS",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                videoLinkFound = true
            }

            if (!videoLinkFound) {
                Log.d(name, "XNXX video links could not be parsed from script variables.")
            }

        } catch (e: Exception) {
            Log.d(name, "Error parsing XNXX links: ${e.message}")
        }

        return videoLinkFound
    }

    private fun html5Regex(fnName: String, source: String): String? {
        val regex = """$fnName\s*\(\s*'([^']+)'\s*\)""".toRegex()
        return regex.find(source)?.groupValues?.get(1)
    }

    private fun buildVideoLink(qualityName: String, videoUrl: String, qualityVal: Int = Qualities.P480.value): ExtractorLink {
        return newExtractorLink(
            name = name,
            source = "$name ($qualityName)",
            url = videoUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            this.referer = mainUrl
            this.quality = qualityVal
        }
    }
}