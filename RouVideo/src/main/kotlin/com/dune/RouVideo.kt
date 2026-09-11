package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log

class RouVideo : MainAPI() {
    override var name = "RouVideo"
    override var mainUrl = "https://rou.video"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "zh"
    override var hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Cookie" to "age_verified=true; adult=true; override_age=1"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/home", headers = headers).document
        val items = ArrayList<SearchResponse>()

        val cards = document.select("a:has(img)[href*='/v/'], a:has(img)[href*='/s/']")
        
        for (card in cards) {
            val href = card.attr("href")
            if (href.isBlank()) continue
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            
            if (items.any { it.url == fullUrl }) continue

            val rawText = card.text().trim()
            val title = card.select(".font-bold, h3, h4, .title").text()
                .ifBlank {
                    rawText.replace(Regex("本週更新.*?集\\s*"), "")
                        .replace(Regex("本週更新.*?期\\s*"), "")
                        .substringBefore("AI短劇")
                        .trim()
                }
                .ifBlank { 
                    val hash = href.substringAfterLast("/")
                    if (href.contains("/s/")) "合集 $hash" else "影片 $hash"
                }

            val img = card.select("img").first()
            val posterUrl = img?.attr("src")?.ifBlank { img.attr("data-src") } ?: ""

            val isSeries = href.contains("/s/")
            val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

            val res = if (isSeries) {
                newTvSeriesSearchResponse(title, fullUrl, tvType) {
                    this.posterUrl = if (posterUrl.startsWith("http")) posterUrl else if (posterUrl.isNotBlank()) "$mainUrl$posterUrl" else null
                }
            } else {
                newMovieSearchResponse(title, fullUrl, tvType) {
                    this.posterUrl = if (posterUrl.startsWith("http")) posterUrl else if (posterUrl.isNotBlank()) "$mainUrl$posterUrl" else null
                }
            }
            items.add(res)
        }

        return newHomePageResponse(listOf(HomePageList("熱門推薦", items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl, headers = headers).document
        val results = ArrayList<SearchResponse>()

        val links = document.select("a[href*='/v/'], a[href*='/s/']")
        for (link in links) {
            val href = link.attr("href")
            if (href.isBlank()) continue
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            if (results.any { it.url == fullUrl }) continue

            val title = link.text().ifBlank { "影片/合集" }
            val isSeries = href.contains("/s/")
            val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

            val res = if (isSeries) {
                newTvSeriesSearchResponse(title, fullUrl, tvType)
            } else {
                newMovieSearchResponse(title, fullUrl, tvType)
            }
            results.add(res)
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.select("meta[property=og:title]").attr("content")
            .ifBlank { document.select("title").text() }
            .ifBlank { "RouVideo 內容" }
        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.select("meta[property=og:description]").attr("content")

        return if (url.contains("/s/")) {
            val episodes = ArrayList<Episode>()
            val epLinks = document.select("a[href*='/v/']")
            var index = 1
            for (epLink in epLinks) {
                val epHref = epLink.attr("href")
                if (epHref.isBlank()) continue
                val epUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                val epTitle = epLink.text().ifBlank { "第 $index 集" }
                if (episodes.none { it.data == epUrl }) {
                    episodes.add(newEpisode(epUrl) {
                        this.name = epTitle
                        this.episode = index++
                    })
                }
            }
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) {
                    this.name = title
                    this.episode = 1
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster.ifBlank { null }
                this.plot = description.ifBlank { null }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster.ifBlank { null }
                this.plot = description.ifBlank { null }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // 1. Extract video ID from URL (e.g. cmtv4sq6u0009muxgtjm38ppu)
            val videoId = data.substringAfterLast("/v/").substringBefore("?")
            if (videoId.isNotBlank()) {
                val apiUrl = "$mainUrl/api/hls/$videoId"
                
                // Request API endpoint with allowRedirects = false to capture the 302 Location header
                val response = app.get(apiUrl, headers = headers, allowRedirects = false)
                val redirectUrl = response.headers["location"] ?: response.headers["Location"]

                if (!redirectUrl.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(name, "$name CDN", redirectUrl, ExtractorLinkType.M3U8) {
                            this.referer = "$mainUrl/"
                            this.headers = mapOf(
                                "Origin" to mainUrl,
                                "Referer" to "$mainUrl/"
                            )
                        }
                    )
                    return true
                }
            }

            // 2. Fallback: Parse OpenGraph meta image token hash
            val document = app.get(data, headers = headers).document
            val ogImage = document.select("meta[property=og:image]").attr("content")
            val hashMatch = Regex("/m/([a-zA-Z0-9_-]{20,})/").find(ogImage)
            
            if (hashMatch != null) {
                val hash = hashMatch.groupValues[1]
                val streamUrl = "https://v.rn252.xyz/m/$hash/index.m3u8"
                
                callback.invoke(
                    newExtractorLink(name, "$name CDN (Fallback)", streamUrl, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/"
                        )
                    }
                )
                return true
            }

        } catch (e: Throwable) {
            Log.e("RouVideo", "Error loading links: ${e.localizedMessage}", e)
        }
        return false
    }
}