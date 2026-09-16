package com.dune

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJsonArray
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI

class Mrds66 : MainAPI() {

    override var mainUrl = "https://www.mrds66.com"
    override var name = "Mrds66"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to "$mainUrl/",
        "Sec-Ch-Ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        mainUrl to "首页",
        "$mainUrl/category/mrds/" to "每日大赛",
        "$mainUrl/category/ztds/" to "主题大赛",
        "$mainUrl/category/rstt/" to "热搜吃瓜",
        "$mainUrl/category/xazd/" to "校园学生",
        "$mainUrl/category/blyp/" to "必撸大赛",
        "$mainUrl/category/fctg/" to "反差泄密",
        "$mainUrl/category/mhds/" to "网红黑料",
        "$mainUrl/category/lqdp/" to "猎奇重口",
        "$mainUrl/category/jdsj/" to "AV看片",
        "$mainUrl/category/mxwh/" to "明星大赛",
        "$mainUrl/category/smdh/" to "动漫之家",
        "$mainUrl/category/dypd/" to "影视国漫",
        "$mainUrl/category/mtds/" to "cos写真",
        "$mainUrl/category/ysds/" to "声控ASMR",
        "$mainUrl/category/czds/" to "寸止挑战",
        "$mainUrl/category/hjds/" to "混剪PMV",
        "$mainUrl/category/tgds/" to "原创投稿",
        "$mainUrl/category/omjp/" to "欧美精品",
        "$mainUrl/category/qwcs/" to "全网参赛",
        "$mainUrl/category/aijc/" to "AI剧场"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "${request.data}/" else "${request.data}page/$page/"
        
        Log.d("Mrds66", "Fetching page: $url")

        val document = try {
            app.get(url, headers = mainHeaders).document
        } catch (e: Exception) {
            Log.e("Mrds66", "Page fetch failed: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        // Homepage cards selector (based on structure analysis, likely a grid or list)
        // Since we didn't get the exact class from the script output, we try common ZblogPHP patterns
        val items = document.select(
            "div.loglist div.item, div.post, article.post, div.video-block, div.card, div.list-item"
        )

        val home = items.mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Try to find the link and title
        val linkElement = this.selectFirst("a, a[href]")
        if (linkElement == null) return null

        val href = linkElement.attr("href").ifBlank { return null }
        
        // Skip if it's not a valid video archive link
        if (!href.contains("/archives/") && !href.contains("/category/") && !href.contains("/tag/")) return null

        val title = this.selectFirst("h1, h2, h3, .post-title, .title")?.text()?.trim()
            ?: linkElement.attr("title").ifBlank { linkElement.text() }?.trim()
            ?: return null

        // Thumbnail: Try img src or data-src
        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.let {
            fixUrlNull(it.attr("data-original") ?: it.attr("data-src") ?: it.attr("src"))
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/search/$query/page/$page/"
        Log.d("Mrds66", "Search URL: $url")

        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.loglist div.item, div.post, article.post")
        
        val results = items.mapNotNull { it.toSearchResponse() }
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        // Title
        val title = document.selectFirst("h1.post-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        // Thumbnail: Extract from Schema.org JSON-LD
        val posterUrl = document.select("script[type='application/ld+json']").mapNotNull { script ->
            try {
                val json = JSONObject(script.text())
                val videoObj = json.getJSONObject("mainEntity").getJSONObject("video")
                videoObj.optString("thumbnailUrl", "").ifBlank { null }
            } catch (e: Exception) {
                null
            }
        }.firstOrNull() ?: document.selectFirst("img")?.let {
            fixUrlNull(it.attr("data-original") ?: it.attr("src"))
        }

        // Description
        val description = document.selectFirst("div.post-content")?.text()?.trim() ?: ""

        // Meta
        val yearText = document.selectFirst("time")?.text()?.trim()?.substringAfter(" ")
            ?.substringBefore("年")?.toIntOrNull()

        val tags = document.select("a[href*='/tag/']").map { it.text().trim() }

        // Recommendations (Next/Prev)
        val recommendations = document.select("a[href*='/archives/']")
            .mapNotNull { a ->
                val title = a.text().trim()
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = null
                }
            }
            .take(10)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
            this.plot = description
            this.year = yearText
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Mrds66", "Loading links for: $data")

        try {
            // Fetch the video page again to extract DPlayer config
            val res = app.get(data, headers = mainHeaders)
            val html = res.text

            // Strategy 1: Extract DPlayer config from script tags
            // DPlayer usually initializes like: new DPlayer({video: {url: '...'}})
            val dplayerScript = Regex("""new DPlayer\(\{[^}]*video[^}]*\{[^}]*url[^}]*['"]([^'"]+)['"]""", RegexOption.DOTALL)
                .find(html)
                ?.groupValues?.get(1)

            if (dplayerScript != null) {
                Log.d("Mrds66", "Found DPlayer URL: $dplayerScript")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Mrds66 Stream",
                        url = dplayerScript,
                        type = if (dplayerScript.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.headers = mainHeaders
                    }
                )
                return true
            }

            // Strategy 2: Look for direct video URLs in the page (fallback)
            val regex = Regex("""https?://[^"'<>]+\.(?:m3u8|mp4|flv)[^"'<>]*""")
            val urls = regex.findAll(html).map { it.value }.distinct()
            
            urls.forEach { url ->
                if (!url.contains("blob:") && !url.contains("advert")) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Mrds66 Direct",
                            url = url,
                            type = if (url.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.headers = mainHeaders
                        }
                    )
                }
            }

            // Strategy 3: If still no stream, try to load the page's main video embed
            // (Some sites use a hidden iframe or an API)
            if (urls.isEmpty()) {
                Log.w("Mrds66", "No stream found via regex. Trying to inspect iframe or other embeds...")
                // If the site uses a generic player, we might need to inspect the network request.
                // For now, we return false to indicate failure.
            }

            return true
        } catch (e: Exception) {
            Log.e("Mrds66", "Error loading links: ${e.message}")
            e.printStackTrace()
        }

        return false
    }
}