package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log

class Mrds66 : MainAPI() {
    override var mainUrl = "https://www.mrds66.com"
    override var name = "MeiriDasai"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
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
        "$mainUrl/category/mtds/" to "COS写真",
        "$mainUrl/category/ysds/" to "声控ASMR",
        "$mainUrl/category/czds/" to "寸止挑战",
        "$mainUrl/category/hjds/" to "混剪PMV",
        "$mainUrl/category/tgds/" to "原创投稿",
        "$mainUrl/category/omjp/" to "欧美精品",
        "$mainUrl/category/qwcs/" to "全网参赛",
        "$mainUrl/category/aijc/" to "AI剧场"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "\({request.data.removeSuffix("/")}/page/\)page/"
        }

        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("article, div.post, div.post-box, div.inside-article").filter { element ->
            !element.hasClass("ad-item") && (element.selectFirst("a")?.attr("href")?.contains("/archives/") == true)
        }

        val home = items.mapNotNull { it.toSearchResponse() }
        val hasNext = home.isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a[href*=\"/archives/\"]") ?: this.selectFirst("a")
        val href = fixUrlNull(linkElement?.attr("href")) ?: return null

        val imgElement = this.selectFirst("img")
        val title = imgElement?.attr("alt")?.trim()?.ifBlank { null }
            ?: linkElement?.attr("title")?.trim()?.ifBlank { null }
            ?: linkElement?.text()?.trim()?.ifBlank { null }
            ?: this.selectFirst("h2, h3, h1")?.text()?.trim()
            ?: return null

        if (title.contains("loadBannerDirect", ignoreCase = true) || title.length < 2) return null

        val posterUrl = fixUrlNull(imgElement?.attr("src") ?: imgElement?.attr("data-src") ?: imgElement?.attr("data-lazy-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "\(mainUrl/page/\)page/?s=$query"

        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("article, div.post, div.post-box, div.inside-article").filter { element ->
            !element.hasClass("ad-item")
        }

        val results = items.mapNotNull { it.toSearchResponse() }
        val hasNext = results.isNotEmpty()

        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h1.entry-title, h1.tit1, h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("div.entry-content img, div.large-screenshot img, .post-thumbnail img")?.attr("src"))
        val description = document.select("div.entry-content p, div.wp-content p").joinToString(" ") { it.text() }.ifBlank { title }

        val tags = document.select(".entry-meta a[rel=tag], li.w1 a[rel=tag], .tags a").mapNotNull { it.text().trim() }
        val recommendations = document.select("article, div.post").mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mainHeaders
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title = this.selectFirst("a img")?.attr("alt")?.trim() ?: this.selectFirst("a")?.text()?.trim()
        if (title.isNullOrBlank() || title.contains("loadBannerDirect")) return null

        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mainHeaders).text
        val doc = org.jsoup.Jsoup.parse(document)

        val iframeSrcs = doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
        for (iframeUrl in iframeSrcs) {
            if (!iframeUrl.contains("addtoany.com")) {
                loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
            }
        }

        val sourceRegex = Regex("[\"'](https?://[^\"']+\\.(m3u8|mp4)[^\"']*)[\"']")
        sourceRegex.findAll(document).forEach { match ->
            val mediaUrl = match.groupValues[1]
            val type = if (mediaUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = mediaUrl,
                    type = type
                ) {
                    this.referer = mainUrl
                }
            )
        }

        return true
    }
}