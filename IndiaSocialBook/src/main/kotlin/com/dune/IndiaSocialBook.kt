package com.dune

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class IndiaSocialBook : MainAPI() {
    override var mainUrl = "https://indiasocialbook.com/videos"
    override var name = "IndiaSocialBook"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home / Recent",
        "$mainUrl/blog/" to "Blog",
        "$mainUrl/categories/" to "Categories",
        "$mainUrl/tags/" to "Tags",
        "$mainUrl/actors/" to "Actors"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        
        val results = document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, results, true), true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = query.replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$formattedQuery" else "$mainUrl/page/$page/?s=$formattedQuery"
        val document = app.get(url).document
        
        val results = document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, .thumb-block a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        
        val img = this.selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: img?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val formattedQuery = query.replace(" ", "+")
        val url = "$mainUrl/?s=$formattedQuery"
        val document = app.get(url).document
        return document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        
        val tags = document.select("span.tags-links a, .tagcloud a").map { it.text() }
        val actors = document.select("span.actor-links a, .template-actors a").map { Actor(it.text()) }
        val recommendations = document.select("article.post, div.thumb-block").mapNotNull { it.toSearchResult() }

        // Scrape tab navigation items explicitly
        val tabNavs = document.select("div.video-tabs ul.tab-nav li, ul.tab-nav li")
        
        val episodes = if (tabNavs.isNotEmpty()) {
            tabNavs.mapIndexed { index, el ->
                val tabName = el.text().trim().ifEmpty { "Part ${index + 1}" }
                Episode(
                    data = "$url#tab_$index",
                    name = tabName,
                    episode = index + 1
                )
            }
        } else {
            // Fallback for regular single video pages or alternative containers
            val fallbackIframes = document.select("div.video-tabs div.tab-pane iframe, iframe")
            if (fallbackIframes.size > 1) {
                fallbackIframes.mapIndexed { index, _ ->
                    Episode(
                        data = "$url#tab_$index",
                        name = "Part ${index + 1}",
                        episode = index + 1
                    )
                }
            } else {
                listOf(
                    Episode(
                        data = url,
                        name = title,
                        episode = 1
                    )
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
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to mainUrl
        )
        
        val baseUrl = data.substringBefore("#")
        val tabIndexStr = data.substringAfter("#tab_", "").toIntOrNull() ?: 0
        
        val response = app.get(baseUrl, headers = headers)
        val document = response.document
        var foundLinks = false

        // Extract iframes from tab panes or general document
        val panes = document.select("div.tab-pane iframe, iframe")
        val targetIframe = panes.getOrNull(tabIndexStr) ?: panes.firstOrNull()
        
        if (targetIframe != null) {
            val iframeSrc = targetIframe.attr("src")
            if (!iframeSrc.isNullOrBlank()) {
                val fixedIframeUrl = fixUrl(iframeSrc)
                
                // Check if it's the clean-tube-player plugin link containing base64 encoded config parameters
                if (fixedIframeUrl.contains("player-x.php?q=")) {
                    try {
                        val base64Query = fixedIframeUrl.substringAfter("q=").substringBefore("&")
                        val decodedBytes = Base64.decode(base64Query, Base64.DEFAULT)
                        val decodedString = String(decodedBytes, Charsets.UTF_8)
                        
                        // Regex search inside the decoded HTML snippet for source urls
                        val srcRegex = "src=[\"'](https?://[^\"']+)[\"']".toRegex(RegexOption.IGNORE_CASE)
                        for (match in srcRegex.findAll(decodedString)) {
                            val videoUrl = match.groups[1]?.value ?: continue
                            val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink(
                                    name = name,
                                    source = name,
                                    url = videoUrl,
                                    type = type
                                ) {
                                    this.referer = baseUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            foundLinks = true
                        }
                    } catch (_: Exception) {}
                }
                
                // Also attempt loading via standard extractor system if above didn't match cleanly
                if (!foundLinks) {
                    if (loadExtractor(fixedIframeUrl, baseUrl, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                }
            }
        }

        // General backup sweep across the page if no links were found through tab lookup
        if (!foundLinks) {
            document.select("video source, video, source").forEach { element ->
                val src = element.attr("src")
                    .takeIf { !it.isNullOrBlank() && it != "about:blank" }
                    ?: element.attr("data-src")

                if (!src.isNullOrBlank() && !src.startsWith("data:")) {
                    val videoUrl = fixUrl(src)
                    val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = videoUrl,
                            type = type
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }
}