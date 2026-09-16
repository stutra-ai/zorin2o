package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Locale

class Mrds66 : MainAPI() {
    override var mainUrl = "https://www.mrds66.com"
    override var name = "Mrds66"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "all"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Videos",
    )

    private val targetSelectors = "article, .post, .item, .entry"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "\({request.mainUrl}/page/\)page/"
        val document = app.get(url, referer = mainUrl).document
        
        var items = document.select(targetSelectors).mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }

        if (items.isEmpty()) {
            items = document.select("a[href*='/archives/']").mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(request.name, items, hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = if (page <= 1) "\(mainUrl/?s=\)query" else "\(mainUrl/page/\)page/?s=$query"
        val document = app.get(url, referer = mainUrl).document
        
        var results = document.select(targetSelectors).mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }

        if (results.isEmpty()) {
            results = document.select("a[href*='/archives/']").mapNotNull { it.toSearchResult() }
        }

        return newSearchResponseList(results, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.tagName() == "a") this else this.selectFirst("a[href*='/archives/']") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        
        val primaryTitle = selectFirst("h1, h2, h3, h4, h5, h6, .title, .entry-title")?.text()?.trim()
        val anchorTitle = anchor.text()?.trim()

        val title = when {
            !primaryTitle.isNullOrEmpty() -> primaryTitle
            !anchorTitle.isNullOrEmpty() -> anchorTitle
            else -> anchor.attr("title").ifEmpty { return null }
        }

        val imgEl = selectFirst("img")
        val inlineStyle = selectFirst("[style*='background']")?.attr("style") ?: this.attr("style")
        
        var poster = inlineStyle?.let { 
            Regex("""url\((["']?)(.*?)\1\)""").find(it)?.groupValues?.get(2) 
        } ?: imgEl?.attr("data-src")
          ?: imgEl?.attr("data-original")
          ?: imgEl?.attr("data-lazy")
          ?: imgEl?.attr("src")

        if (poster != null) {
            if (poster.startsWith("//")) poster = "https:$poster"
            if (!poster.startsWith("data:")) {
                poster = fixUrl(poster)
            } else {
                poster = null
            }
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document
        val title = document.selectFirst("h1.entry-title, h1, h2, title")?.text()?.trim() ?: "Mrds66 Video"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { fixUrl(it) }

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".entry-content")?.text()?.trim()
        
        val recommendations = document.select(targetSelectors).mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var found = false

        val rawHtml = document.toString()
        val streamRegex = Regex("""["'](https?://[^"'\s>]+\.(?:m3u8|mp4)[^"'\s>]*)["']""")
        streamRegex.findAll(rawHtml).map { it.groupValues[1] }.distinct().forEach { streamUrl ->
            val cleanUrl = fixUrl(streamUrl)
            val isM3u8 = cleanUrl.contains(".m3u8")
            callback(
                newExtractorLink(
                    source = "Mrds66 Core",
                    name = if (isM3u8) "HLS Stream" else "MP4 Video",
                    url = cleanUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
            found = true
        }

        document.select("video source, video").forEach { videoTag ->
            val src = videoTag.attr("src").ifEmpty { videoTag.attr("data-src") }
            if (src.isNotEmpty() && !src.startsWith("blob:")) {
                val absoluteUrl = fixUrl(src)
                val isM3u8 = absoluteUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = "HTML5 Player",
                        name = "Direct Stream",
                        url = absoluteUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                found = true
            }
        }

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty() && !src.contains("addtoany")) {
                val absoluteUrl = fixUrl(src)
                if (loadExtractor(absoluteUrl, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        return found
    }
}