package com.dune

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Locale

class Mrds66 : MainAPI() {
    override var mainUrl = "https://www.mrds66.com"
    override var name = "MRDS66"
    override var lang = "zh"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "首页",
        "$mainUrl/category/mrds/" to "每日大赛",
        "$mainUrl/category/ztds/" to "主题大赛",
        "$mainUrl/category/rstt/" to "热搜吃瓜",
        "$mainUrl/category/xazd/" to "校园学生",
        "$mainUrl/category/blyp/" to "必撸大赛",
        "$mainUrl/category/fctg/" to "反差泄密",
        "$mainUrl/category/mhds/" to "网红黑料",
        "$mainUrl/category/lqdp/" to "猎奇重口",
        "$mainUrl/category/jdsj/" to "AV看片"
    )

    private val articleUrlRegex = Regex(
        """/archives/\d+/?""",
        RegexOption.IGNORE_CASE
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data.removeSuffix("/")

        val url = when {
            page <= 1 -> request.data
            baseUrl.endsWith("/category/mrds") ||
            baseUrl.endsWith("/category/ztds") ||
            baseUrl.endsWith("/category/rstt") ||
            baseUrl.endsWith("/category/xazd") ||
            baseUrl.endsWith("/category/blyp") ||
            baseUrl.endsWith("/category/fctg") ||
            baseUrl.endsWith("/category/mhds") ||
            baseUrl.endsWith("/category/lqdp") ||
            baseUrl.endsWith("/category/jdsj") ->
                "\(baseUrl/page/\)page/"
            else ->
                "\(baseUrl/page/\)page/"
        }

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        val results = extractArticles(document)

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {
        val archiveUrl = if (page <= 1) {
            "$mainUrl/archives.html"
        } else {
            "\(mainUrl/archives.html?page=\)page"
        }

        val document = app.get(
            archiveUrl,
            referer = mainUrl
        ).document

        val results = extractArticles(document).filter {
            it.name.contains(query, ignoreCase = true)
        }

        return newSearchResponseList(
            results,
            hasNext = results.isNotEmpty()
        )
    }

    private fun extractArticles(document: org.jsoup.nodes.Document): List {
        val selectors = listOf(
            "article",
            ".post",
            ".post-item",
            ".article",
            ".article-item",
            ".item",
            ".list-item",
            ".excerpt",
            ".card",
            "a[href*='/archives/']"
        )

        val elements = selectors
            .flatMap { selector -> document.select(selector) }
            .distinctBy { element ->
                element.selectFirst("a[href*='/archives/']")
                    ?.absUrl("href")
                    ?: element.absUrl("href")
            }

        return elements.mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = when {
            tagName() == "a" && attr("href").matches(articleUrlRegex) ->
                this

            else ->
                selectFirst("a[href*='/archives/']")
        } ?: return null

        val url = anchor.absUrl("href").ifBlank {
            fixUrlNull(anchor.attr("href")) ?: return null
        }

        if (!articleUrlRegex.containsMatchIn(url)) return null

        val title = listOf(
            selectFirst("h1")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst("h4")?.text(),
            selectFirst(".title")?.text(),
            selectFirst(".post-title")?.text(),
            anchor.attr("title"),
            anchor.text()
        )
            .map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() }
            ?: return null

        val image = selectFirst("img")
        var poster = image?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-original")
                ?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-lazy-src")
                ?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")
                ?.takeIf { it.isNotBlank() }

        if (!poster.isNullOrBlank()) {
            poster = fixUrl(poster)
        }

        return this@Mrds66.newMovieSearchResponse(
            title = title,
            url = url,
            type = TvType.NSFW
        ) {
            posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            referer = mainUrl
        ).document

        val title = listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("h2")?.text(),
            document.selectFirst("title")?.text()
        )
            .map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() }
            ?: "MRDS66 视频"

        val poster = document.selectFirst(
            "meta[property='og:image']"
        )?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        val description = document.selectFirst(
            "meta[property='og:description']"
        )?.attr("content")
            ?.trim()

        val recommendations = extractArticles(document)
            .filter { it.url != url }
            .distinctBy { it.url }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = url
        ) {
            posterUrl = poster
            plot = description
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(
            data,
            referer = mainUrl
        )

        val html = response.text
        val document = response.document

        val streamUrls = linkedSetOf()

        fun addCandidate(value: String?) {
            if (value.isNullOrBlank()) return

            var candidate = value
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&", "&")
                .trim()

            candidate = URLDecoder.decode(candidate, Charsets.UTF_8.name())

            if (
                candidate.startsWith("http") &&
                candidate.contains(".m3u8", ignoreCase = true)
            ) {
                streamUrls += candidate
            }
        }

        val directM3u8Regex = Regex(
            """https?://[^"'`\\\s<>]+\.m3u8(?:\?[^"'`\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        directM3u8Regex.findAll(html).forEach {
            addCandidate(it.value)
        }

        val configRegex = Regex(
            """(?:file|url|src|source|video|playlist|m3u8)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL
            )
        )

        configRegex.findAll(html).forEach {
            addCandidate(it.groupValues[1])
        }

        document.select("*").forEach { element ->
            element.attributes().forEach { attribute ->
                if (
                    attribute.value.contains(".m3u8", ignoreCase = true)
                ) {
                    directM3u8Regex.findAll(attribute.value).forEach {
                        addCandidate(it.value)
                    }
                    addCandidate(attribute.value)
                }
            }
        }

        if (streamUrls.isEmpty()) {
            return false
        }

        streamUrls.forEachIndexed { index, streamUrl ->
            callback(
                newExtractorLink(
                    source = name,
                    name = if (index == 0) "MRDS66 HLS" else "MRDS66 HLS $index",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    quality = Qualities.Unknown.value
                    this.referer = data
                }
            )
        }

        return true
    }
}