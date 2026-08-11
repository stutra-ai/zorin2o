package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DesiPornX : MainAPI() {
    override var mainUrl = "https://desipornx.org"
    override var name = "DesiPornX"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    private fun String.fixUrl(): String {
        return when {
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$mainUrl$this"
            else -> this
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".item-title a, h3 a, a.title") ?: return null
        val title = titleElement.attr("title").ifBlank { titleElement.text() }
        val href = titleElement.attr("href")?.fixUrl() ?: return null

        val imgTag = this.selectFirst("img")
        val posterUrl = (imgTag?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgTag?.attr("src")?.takeIf { it.isNotBlank() })?.fixUrl()

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            if (!posterUrl.isNullOrBlank()) {
                this.posterHeaders = mapOf("Referer" to mainUrl)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageNumber = if (page > 1) "/page/$page/" else ""
        val document = app.get("$mainUrl$pageNumber").document
        val items = document.select("div.item, div.video-item, article").mapNotNull { it.toSearchResponse() }

        val homePages = mutableListOf(HomePageList("Latest Videos", items))

        val categories = listOf(
            Pair("Desi", "/category/desi/"),
            Pair("Hindi", "/category/hindi/"),
            Pair("MMS", "/category/mms/"),
            Pair("Bhabhi", "/category/bhabhi/"),
            Pair("Auntie", "/category/auntie/")
        )

        categories.forEach { (catName, catHref) ->
            val catDocs = app.get("$mainUrl$catHref").document
            val catItems = catDocs.select("div.item, div.video-item, article").take(10).mapNotNull { it.toSearchResponse() }
            if (catItems.isNotEmpty()) {
                homePages.add(HomePageList(catName, catItems))
            }
        }

        val hasNextPage = document.selectFirst("a.next, .pagination-next, link[rel=next]") != null
        return newHomePageResponse(homePages, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.item, div.video-item, article").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val d = app.get(url).document
        val title = d.selectFirst("h1.entry-title, h1.video-title, h2.title")?.text()?.trim()
            ?: d.selectFirst("meta[property=og:title]")?.attr("content") ?: return null

        val poster = d.selectFirst("meta[property=og:image]")?.attr("content")?.fixUrl()
        val description = d.selectFirst("meta[name=description]")?.attr("content")
            ?: d.selectFirst("div.entry-content, div.video-description")?.text()

        val tags = d.select("div.tags a, .video-tags a").map { it.text() }.filter { it.isNotBlank() }
        
        val recs = d.select("div.item, div.related-item, article").mapNotNull { it.toSearchResponse() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            if (!this.posterUrl.isNullOrBlank()) this.posterHeaders = mapOf("Referer" to mainUrl)
            this.plot = description
            this.tags = tags
            this.recommendations = recs
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Find iframe sources or direct video player links
        val iframeSrc = document.selectFirst("iframe")?.attr("src")?.fixUrl()
        if (!iframeSrc.isNullOrBlank()) {
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }

        // Check for direct source elements inside video tags
        document.select("video source").forEach { source ->
            val src = source.attr("src").fixUrl()
            if (src.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = src,
                        quality = Qualities.Unknown.value
                    )
                )
            }
        }

        return true
    }
}