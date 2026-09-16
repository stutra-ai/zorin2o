package com.dune

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

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
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
        "Sec-Ch-Ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Upgrade-Insecure-Requests" to "1"
    )

    // NOTE: some slugs (blyp -> /archives/37/) are posts, not categories.
    // getMainPage already falls back to card-scraping when there is no grid, so this is safe.
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

    // ---------- homepage ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // ZblogPHP pagination: /page/N/. Category URLs already end in "/", page 1 == category root.
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/$page/".let { s ->
                if (request.data.endsWith("/")) "${request.data}page/$page/" else s
            }
        }

        Log.d("kraptor_$name", "MainPage URL: $url")

        val document = try {
            app.get(url, headers = mainHeaders).document
        } catch (e: Exception) {
            Log.e("kraptor_$name", "MainPage failed: ${e.message}")
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)
        }

        // Listing pages use a different markup than the single post page (article.post),
        // so we cast a wide net and de-dupe below.
        val items = document.select(
            "#post-list > *, .loglist > *, .post-list > *, .index-list > *, " +
                ".list > article, .excerpt, .post-item, .log-list li, article"
        )

        val home = LinkedHashSet<SearchResponse>()
        items.forEach { el ->
            el.toSearchResponse()?.let { home.add(it) }
            // Some themes wrap <article> inside a container div; try children too.
            el.children().forEach { child ->
                child.toSearchResponse()?.let { home.add(it) }
            }
        }

        val list = home.toList()
        return newHomePageResponse(
            list = HomePageList(request.name, list, isHorizontalImages = true),
            hasNext = list.isNotEmpty()
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("h2 a, h3 a, .post-title a, h1 a, a[href]") ?: return null

        val hrefRaw = linkElement.attr("href")
        if (!hrefRaw.contains("/archives/")) return null          // only real video posts
        val href = fixUrlNull(hrefRaw) ?: return null

        val title = this.selectFirst(".post-title, h2, h3")?.text()?.trim()?.ifBlank { null }
            ?: linkElement.attr("title").trim().ifBlank { null }
            ?: linkElement.text().trim().ifBlank { null }
            ?: return null

        val img = this.selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mainHeaders
        }
    }

    // ---------- search ----------

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // Two known forms: schema.org advertises /search/{term}/, the <form> uses ?s=.
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "$mainUrl/search/$encoded/page/$page/",
            "$mainUrl/search/$encoded/",
            "$mainUrl/?s=$encoded&page=$page"
        )

        val found = LinkedHashSet<SearchResponse>()

        for (u in urls) {
            try {
                val doc = app.get(u, headers = mainHeaders).document
                doc.select("article, .excerpt, .post-item, .loglist > *")
                    .mapNotNull { it.toSearchResponse() }
                    .forEach { found.add(it) }
                if (found.isNotEmpty()) break
            } catch (e: Exception) {
                Log.d("kraptor_$name", "search failed on $u : ${e.message}")
            }
        }

        val results = found.toList()
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h1.post-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        // Schema.org JSON-LD carries the reliable thumbnail + duration + keywords.
        val ldText = document.select("script[type=application/ld+json]").joinToString("\n") { it.data() }

        val poster = Regex("\"thumbnailUrl\"\\s*:\\s*\"([^\"]+)\"").find(ldText)?.groupValues?.get(1)
            ?.let { fixUrlNull(it) }
            ?: Regex("\"image\"\\s*:\\s*\"([^\"]+)\"").find(document.html())?.groupValues?.get(1)
                ?.let { fixUrlNull(it) }

        val description = document.selectFirst(".post-content p")?.text()?.trim()?.ifBlank { null }
            ?: Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(ldText)?.groupValues?.get(1)
            ?: ""

        val year = Regex("\"datePublished\"\\s*:\\s*\"(20\\d{2})").find(ldText)?.groupValues?.get(1)
            ?.toIntOrNull()
            ?: document.selectFirst(".post-meta time")?.text()?.trim()?.let {
                Regex("(20\\d{2})").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        val tags = document.select("a[href*=/tag/]").map { it.text().trim() }
            .filter { it.isNotBlank() && it != "关键词：" }
            .distinct()

        val actors = document.select(".post-meta a[href*=/author/]")
            .map { Actor(it.text().trim()) }
            .filter { it.name.isNotBlank() }

        // Prev/next links make decent recommendations.
        val recs = LinkedHashSet<SearchResponse>()
        document.select("a[href*=/archives/]").forEach { a ->
            val t = a.text().replace(Regex("^\\s*(下一篇|上一篇)[:：]?\\s*"), "").trim()
            if (t.length < 4) return@forEach
            val h = fixUrlNull(a.attr("href")) ?: return@forEach
            recs.add(newMovieSearchResponse(t, h, TvType.NSFW))
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mainHeaders
            this.plot = description
            this.year = year
            this.tags = tags
            this.recommendations = recs.take(30)
            addActors(actors)
        }
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val html = try {
            app.get(data, headers = mainHeaders).text
        } catch (e: Exception) {
            Log.e("kraptor_$name", "loadLinks fetch failed: ${e.message}")
            return false
        }

        val streams = LinkedHashSet<String>()

        // 1. DPlayer init: video:{url:'...'} or url:function(){return "..."}
        Regex("""(?:video|url)\s*:\s*(?:function\s*\(\s*\)\s*\{\s*return\s*)?["'](https?://[^"']+)["']""")
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach { streams.add(it) }

        // 2. Hls.js / plyr / videojs style assignments
        Regex("""\.src\(\s*["'](https?://[^"']+)["']""")
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach { streams.add(it) }

        // 3. Any raw media file referenced anywhere in the document
        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|flv)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.value }
            .forEach { streams.add(it) }

        val clean = streams.filter { u ->
            u.isNotBlank() &&
                !u.startsWith("blob:") &&
                !u.contains("advert", true) &&
                !u.contains("/ads/", true) &&
                !u.contains("analytics")
        }

        Log.d("kraptor_$name", "candidate streams: $clean")

        if (clean.isEmpty()) {
            // The page you dumped used a blob: URL, i.e. the stream is fetched by JS at runtime.
            // If this branch triggers consistently, the URL comes from an XHR/signature endpoint
            // and needs a network-tab capture to implement.
            Log.w("kraptor_$name", "No static stream URL found for $data")
            return false
        }

        clean.forEach { streamUrl ->
            val type = when {
                streamUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                streamUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = type
                ) {
                    this.referer = data
                    this.headers = mainHeaders
                }
            )
        }

        return true
    }
}