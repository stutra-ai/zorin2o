package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Locale

class PinayCum : MainAPI() {
    override var mainUrl = "https://pinaycumvid.xyz"
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
            val videoId = Regex("""id=(\d+)""").find(href)?.groupValues?.get(1)
            poster = videoId?.let {
                "https://pinaycumvid.xyz/contents/videos_screenshots/${it.toInt() / 1000 * 1000}/$it/preview.mp4.jpg"
            }
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
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var found = false
        val processedUrls = mutableSetOf<String>()

        // Core extraction function to scrape the direct stream file inside target players
        suspend fun extractDirectStream(embedUrl: String, sourceName: String): Boolean {
            val cleanEmbed = embedUrl.replace("/d/", "/e/").replace("/f/", "/e/")
            if (!processedUrls.add(cleanEmbed)) return false
            
            return try {
                if (cleanEmbed.contains("dood") || cleanEmbed.contains("ds2play")) {
                    return loadExtractor(cleanEmbed, data, subtitleCallback, callback)
                }

                val embedResponse = app.get(cleanEmbed, referer = mainUrl).text
                val streamUrl = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(embedResponse)?.groupValues?.get(1)
                
                if (streamUrl != null) {
                    val isM3u8 = streamUrl.contains(".m3u8")
                    callback(
                        newExtractorLink(
                            source = sourceName,
                            name = "$sourceName Manual Direct",
                            url = streamUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                    true
                } else {
                    loadExtractor(cleanEmbed, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) { 
                false 
            }
        }

        // Strategy 1: Find the explicit download button address if visible in source
        document.select("a[href*='vidarax.cc/d/']").forEach { element ->
            val href = element.attr("href")
            if (extractDirectStream(fixUrl(href), "Vidara Download")) found = true
        }

        // Strategy 2: Generate direct player domain links by reading parameters inside button attributes
        document.select("a[href*='id='][href*='s=']").forEach { element ->
            val href = element.attr("href")
            val id = Regex("""id=([^&]+)""").find(href)?.groupValues?.get(1)
            val server = Regex("""s=([^&]+)""").find(href)?.groupValues?.get(1)

            if (!id.isNullOrEmpty() && !server.isNullOrEmpty()) {
                // Generate absolute player destinations bypassing site query parameters
                val targetEmbed = when (server.lowercase(Locale.ROOT)) {
                    "vidara" -> "https://vidarax.cc/e/$id"
                    "lulustream" -> "https://lulustream.com/e/$id"
                    "streamruby" -> "https://streamruby.com/e/$id"
                    "doodstream" -> "https://doodstream.com/e/$id"
                    else -> null
                }
                
                if (targetEmbed != null) {
                    val capitalizedServer = server.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                    if (extractDirectStream(targetEmbed, capitalizedServer)) {
                        found = true
                    }
                }
            }
        }

        // Strategy 3: Dynamic fallback if button rules fail
        if (!found) {
            val rawHtml = document.toString()
            Regex("""https?://[^"'\s>]+(?:vidara|lulu|ruby|dood|ds2play)[^"'\s>]+""").findAll(rawHtml).map { it.value }.forEach { url ->
                if (extractDirectStream(url, "Backup Server")) found = true
            }
        }

        return found
    }
}