package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Locale

class PinayCum : MainAPI() {
    override var mainUrl = "https://pinaycumvids.lol"
    override var name = "PinayCum"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "tl"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Videos",
    )

    private val targetSelectors = ".video-block, .col-md-3, .thumb-block, .item, .post, div:has(a[href*='watch.php?id='])"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/?page=$page"
        val document = app.get(url, referer = mainUrl).document
        
        var items = document.select(targetSelectors).mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }

        if (items.isEmpty()) {
            items = document.select("a[href*='watch.php?id=']").mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(request.name, items, hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = if (page <= 1) "$mainUrl/?s=$query" else "$mainUrl/?s=$query&page=$page"
        val document = app.get(url, referer = mainUrl).document
        
        var results = document.select(targetSelectors).mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }

        if (results.isEmpty()) {
            results = document.select("a[href*='watch.php?id=']").mapNotNull { it.toSearchResult() }
        }

        return newSearchResponseList(results, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.tagName() == "a") this else this.selectFirst("a[href*='watch.php?id=']")
        val href = fixUrlNull(anchor?.attr("href")) ?: return null
        
        val primaryTitle = selectFirst("h6.vid-title strong, .vid-title, h3, h4, .title")?.text()?.trim()
        val anchorTitle = anchor?.text()?.trim()

        fun isValidTitle(text: String?): Boolean {
            if (text.isNullOrEmpty()) return false
            if (text.matches(Regex("""^\d{4}$"""))) return false
            if (text.contains(Regex("""(?i)\b(years? ago|months? ago|days? ago|views?)\b"""))) return false
            return true
        }

        val title = when {
            isValidTitle(primaryTitle) -> primaryTitle!!
            isValidTitle(anchorTitle) -> anchorTitle!!
            !anchorTitle.isNullOrEmpty() -> anchorTitle
            !primaryTitle.isNullOrEmpty() -> primaryTitle
            else -> return null
        }

        val imgEl = selectFirst("img")
        val inlineStyle = selectFirst("[style*='background']")?.attr("style") ?: this.attr("style")
        
        var poster = inlineStyle?.let { 
            Regex("""url\((["']?)(.*?)\1\)""").find(it)?.groupValues?.get(2) 
        } ?: imgEl?.attr("data-webp")
          ?: imgEl?.attr("data-src")
          ?: imgEl?.attr("data-original")
          ?: imgEl?.attr("data-thumb")
          ?: imgEl?.attr("src")

        if (poster != null && (poster.contains("style-853x480.png") || poster.contains("assets/img"))) {
            poster = "https://pinaycumvids.lol/contents/videos_screenshots/preview.mp4.jpg"
        }

        if (poster != null) {
            if (poster.startsWith("//")) poster = "https:$poster"
            poster = fixUrl(poster)
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document
        val title = document.selectFirst("h4, h1, title")?.text()?.trim() ?: "Pinay Video"

        val styleAttr = document.selectFirst("div#preroll-overlay")?.attr("style")
        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: styleAttr?.let { Regex("""url\((["']?)(.*?)\1\)""").find(it)?.groupValues?.get(2) }
            ?: document.selectFirst("img")?.attr("src")

        if (poster != null) poster = fixUrl(poster)

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
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
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var found = false
        val processedUrls = mutableSetOf<String>()

        suspend fun extractLinks(embedUrl: String): Boolean {
            val cleanEmbed = embedUrl.replace("/d/", "/e/").replace("/f/", "/e/")
            if (!processedUrls.add(cleanEmbed)) return false
            
            return try {
                if (loadExtractor(cleanEmbed, data, subtitleCallback, callback)) {
                    return true
                }

                val embedResponse = app.get(
                    cleanEmbed, 
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Referer" to mainUrl
                    )
                ).text

                val streamUrl = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(embedResponse)?.groupValues?.get(1)
                    ?: Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""").find(embedResponse)?.groupValues?.get(1)

                if (streamUrl != null) {
                    val isM3u8 = streamUrl.contains(".m3u8")
                    callback(
                        newExtractorLink(
                            source = "Direct Stream",
                            name = "Backup Direct",
                            url = streamUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }

        // 1. Parse player selection buttons matching your HTML structure (`a.show_more`)
        document.select("a.show_more[href*='id=']").forEach { element ->
            val href = element.attr("href")
            val id = Regex("""id=([^&]+)""").find(href)?.groupValues?.get(1)
            val server = Regex("""s=([^&]+)""").find(href)?.groupValues?.get(1)

            if (!id.isNullOrEmpty()) {
                val targetEmbed = when (server?.lowercase(Locale.ROOT)) {
                    "lulustream", "lulu" -> "https://lulustream.com/e/$id"
                    "streamruby", "ruby" -> "https://streamruby.com/e/$id"
                    "doodstream", "dood" -> "https://doodstream.com/e/$id"
                    else -> "https://vidwara.fit/e/$id"
                }
                if (extractLinks(targetEmbed)) found = true
            }
        }

        // 2. Fallback: Parse parameters directly from current URL if no container buttons matched
        if (!found) {
            val currentId = Regex("""id=([^&]+)""").find(data)?.groupValues?.get(1)
            val currentServer = Regex("""s=([^&]+)""").find(data)?.groupValues?.get(1)
            if (!currentId.isNullOrEmpty()) {
                val fallbackEmbed = when (currentServer?.lowercase(Locale.ROOT)) {
                    "lulustream", "lulu" -> "https://lulustream.com/e/$currentId"
                    "streamruby", "ruby" -> "https://streamruby.com/e/$currentId"
                    "doodstream", "dood" -> "https://doodstream.com/e/$currentId"
                    else -> "https://vidwara.fit/e/$currentId"
                }
                if (extractLinks(fallbackEmbed)) found = true
            }
        }

        return found
    }
}