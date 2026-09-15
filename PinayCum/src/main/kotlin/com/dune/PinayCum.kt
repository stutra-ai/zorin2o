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
                // 1. Try built-in Cloudstream extractors first
                if (loadExtractor(cleanEmbed, data, subtitleCallback, callback)) {
                    return true
                }

                // 2. Fetch the embed page text with full browser headers
                val embedResponse = app.get(
                    cleanEmbed, 
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Referer" to mainUrl,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                    )
                ).text

                // 3. Check if the embed page itself contains an iframe pointing to the actual video player
                val embedDoc = org.jsoup.Jsoup.parse(embedResponse)
                val innerIframe = embedDoc.selectFirst("iframe")?.attr("src")
                if (!innerIframe.isNullOrEmpty() && innerIframe != cleanEmbed) {
                    val innerUrl = fixUrl(innerIframe)
                    if (loadExtractor(innerUrl, data, subtitleCallback, callback)) {
                        return true
                    }
                }

                // 4. Multiple regex patterns to catch various stream formats (.m3u8, .mp4, file fields)
                val streamUrlPatterns = listOf(
                    Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                    Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                    Regex("""source\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                    Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""")
                ]

                var streamUrl: String? = null
                for (pattern in streamUrlPatterns) {
                    val match = pattern.find(embedResponse)?.groupValues?.get(1)
                    if (!match.isNullOrEmpty()) {
                        streamUrl = match
                        break
                    }
                }

                // 5. If packed JS (eval(function(...))), try to unpack or search inside script tags
                if (streamUrl == null) {
                    embedDoc.select("script").forEach { script ->
                        val scriptText = script.html()
                        if (scriptText.contains("eval(function")) {
                            try {
                                val unpacked = getAndUnpack(scriptText)
                                for (pattern in streamUrlPatterns) {
                                    val match = pattern.find(unpacked)?.groupValues?.get(1)
                                    if (!match.isNullOrEmpty()) {
                                        streamUrl = match
                                        break
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                if (!streamUrl.isNullOrEmpty()) {
                    val fixedStreamUrl = if (streamUrl.startsWith("//")) "https:$streamUrl" else streamUrl
                    val isM3u8 = fixedStreamUrl.contains(".m3u8") || fixedStreamUrl.contains("m3u8")
                    
                    callback(
                        newExtractorLink(
                            source = "Direct Stream",
                            name = "Main Stream",
                            url = fixedStreamUrl,
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