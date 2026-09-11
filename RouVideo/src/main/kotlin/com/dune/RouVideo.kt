package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty

class RouVideo : MainAPI() {
    override var mainUrl = "https://rou.video"
    override var name = "Rou.video"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "热门短剧",
        "$mainUrl/rank" to "排行榜",
        "$mainUrl/fresh" to "最新更新"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = mainHeaders).document
        val scriptContent = document.select("script#__NEXT_DATA__").html()
        val homeList = mutableListOf<SearchResponse>()

        try {
            if (scriptContent.isNotBlank()) {
                val parsedJson = mapper.readValue(scriptContent, NextDataRoot::class.java)
                val pageProps = parsedJson.props?.pageProps
                
                val rawItems: List<VideoItem> = pageProps?.heroRanking 
                    ?: pageProps?.rankedSeries 
                    ?: pageProps?.latestVideos 
                    ?: emptyList()
                
                for (item in rawItems) {
                    val title = item.title ?: item.name ?: continue
                    val id = item.id ?: item.slug ?: continue
                    val poster = item.cover ?: item.poster ?: item.image ?: ""
                    val href = "$mainUrl/detail/$id"
                    val isSeries = item.firstEpisodeId != null || item.episodesCount != null

                    homeList.add(
                        if (isSeries) {
                            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                                this.posterUrl = poster
                                this.posterHeaders = mainHeaders
                            }
                        } else {
                            newMovieSearchResponse(title, href, TvType.Movie) {
                                this.posterUrl = poster
                                this.posterHeaders = mainHeaders
                            }
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.d("kraptor_$name", "Error parsing Next.js data: ${e.message}")
            val domItems = document.select("div.video-item, .item-card")
            for (element in domItems) {
                val link = element.selectFirst("a") ?: continue
                val href = fixUrlNull(link.attr("href")) ?: continue
                val title = element.selectFirst(".title, h3")?.text()?.trim() ?: "Unknown"
                val poster = fixUrlNull(element.selectFirst("img")?.attr("src"))
                
                homeList.add(
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                        this.posterHeaders = mainHeaders
                    }
                )
            }
        }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = homeList,
                isHorizontalImages = true
            ),
            hasNext = homeList.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.video-item, article")
        
        return items.mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val title = element.selectFirst(".title, h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = fixUrlNull(element.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.posterHeaders = mainHeaders
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h2, h1.title, h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("video")?.attr("poster") ?: document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""

        val episodeElements = document.select("div.max-h-\\[280px\\] a, div.episode-list a, .episodes-grid button")
        
        if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapIndexed { index, element ->
                val epHref = fixUrlNull(element.attr("href")) ?: url
                val epText = element.selectFirst(".truncate")?.text() ?: element.text()
                val epNum = epText.filter { it.isDigit() }.toIntOrNull() ?: (index + 1)
                
                newEpisode(epHref) {
                    name = epText.ifBlank { "第 ${epNum} 集" }
                    episode = epNum
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = mainHeaders
                this.plot = description
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = mainHeaders
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mainHeaders).document
        
        val posterAttr = document.selectFirst("video.rv-player-video")?.attr("poster")

        if (!posterAttr.isNullOrBlank() && posterAttr.contains("/m/")) {
            try {
                val regex = Regex("/m/([^/]+)/")
                val match = regex.find(posterAttr)
                if (match != null) {
                    val folderHash = match.groupValues[1]
                    val m3u8Url = "https://v.rn252.xyz/m/$folderHash/index.m3u8"
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name CDN",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                        }
                    )
                    return true
                }
            } catch (_: Exception) {}
        }

        val scriptText = document.select("script").html()
        val hlsRegex = Regex("[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']")
        val match = hlsRegex.find(scriptText)?.groupValues?.get(1)

        if (match != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name Stream",
                    url = match,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                }
            )
            return true
        }

        return false
    }

    data class NextDataRoot(
        @JsonProperty("props") val props: Props? = null
    )
    data class Props(
        @JsonProperty("pageProps") val pageProps: PageProps? = null
    )
    data class PageProps(
        @JsonProperty("heroRanking") val el1: List<VideoItem>? = null,
        @JsonProperty("rankedSeries") val el2: List<VideoItem>? = null,
        @JsonProperty("latestVideos") val el3: List<VideoItem>? = null
    ) {
        val heroRanking: List<VideoItem>? get() = el1
        val rankedSeries: List<VideoItem>? get() = el2
        val latestVideos: List<VideoItem>? get() = el3
    }
    data class VideoItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("firstEpisodeId") val firstEpisodeId: String? = null,
        @JsonProperty("episodesCount") val episodesCount: Int? = null
    )
}