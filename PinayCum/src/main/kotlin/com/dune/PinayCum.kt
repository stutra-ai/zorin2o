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

        suspend fun fetchAndExtract(embedUrl: String, serverName: String): Boolean {
            val cleanUrl = fixUrl(embedUrl)
            if (!processedUrls.add(cleanUrl)) return false

            return try {
                val response = app.get(
                    cleanUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Referer" to mainUrl
                    )
                )
                
                val text = response.text

                val patterns = listOf(
                    Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']"""),
                    Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                    Regex("""src\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                    Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                )

                var streamUrl: String? = null
                for (regex in patterns) {
                    val match = regex.find(text)?.groupValues?.get(1)
                    if (!match.isNullOrEmpty()) {
                        streamUrl = match
                        break
                    }
                }

                if (!streamUrl.isNullOrEmpty()) {
                    val finalUrl = if (streamUrl.startsWith("//")) "https:$streamUrl" else streamUrl
                    val isM3u8 = finalUrl.contains(".m3u8") || finalUrl.contains("m3u8")

                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName Video",
                            url = finalUrl,
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

        // 1. Scrape standard iframes embedded on the page
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (!src.isNullOrEmpty() && !src.contains("ads")) {
                if (fetchAndExtract(src, "IFrame Player")) found = true
            }
        }

        // 2. Parse the specific server buttons matching your HTML (`a.show_more`)
        document.select("a.show_more[href*='id=']").forEach { element ->
            val href = element.attr("href")
            val id = Regex("""id=([^&]+)""").find(href)?.groupValues?.get(1)
            val server = Regex("""s=([^&]+)""").find(href)?.groupValues?.get(1) ?: "Vidara"

            if (!id.isNullOrEmpty()) {
                val targetEmbed = when (server.lowercase(Locale.ROOT)) {
                    "lulustream", "lulu" -> "https://lulustream.com/e/$id"
                    "streamruby", "ruby" -> "https://streamruby.com/e/$id"
                    "doodstream", "dood" -> "https://doodstream.com/e/$id"
                    else -> "https://vidwara.fit/e/$id"
                }
                val formattedName = if (server.isNotBlank()) server.replaceFirstChar { it.uppercase() } else "Vidara"
                if (fetchAndExtract(targetEmbed, formattedName)) found = true
            }
        }

        // 3. Fallback to active URL parameters if selectors fail
        if (!found) {
            val currentId = Regex("""id=([^&]+)""").find(data)?.groupValues?.get(1)
            val currentServer = Regex("""s=([^&]+)""").find(data)?.groupValues?.get(1) ?: "Vidara"
            if (!currentId.isNullOrEmpty()) {
                val fallbackEmbed = when (currentServer.lowercase(Locale.ROOT)) {
                    "lulustream", "lulu" -> "https://lulustream.com/e/$currentId"
                    "streamruby", "ruby" -> "https://streamruby.com/e/$currentId"
                    "doodstream", "dood" -> "https://doodstream.com/e/$currentId"
                    else -> "https://vidwara.fit/e/$currentId"
                }
                val formattedName = if (currentServer.isNotBlank()) currentServer.replaceFirstChar { it.uppercase() } else "Vidara"
                if (fetchAndExtract(fallbackEmbed, formattedName)) found = true
            }
        }

        return found
    }
}