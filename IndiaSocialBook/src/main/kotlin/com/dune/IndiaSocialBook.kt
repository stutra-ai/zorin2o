package com.dune

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import org.jsoup.nodes.Element

class IndiaSocialBook : MainAPI() {
    override var mainUrl = "https://indiasocialbook.com/videos"
    override var name = "IndiaSocialBook"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.Video)

    private val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/videos/" to "Videos"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }

        val document = app.get(url, headers = mainHeaders).document
        val items = document.select("div.thumb-block, article, div.item, div.post-box")
        val home = items.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h3 a, h2 a, a.title, .entry-header a") ?: return null
        val title = titleElement.text().trim().ifBlank {
            titleElement.attr("title").trim()
        }.ifBlank { return null }

        val href = fixUrlNull(titleElement.attr("href")) ?: return null

        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-original")
                ?: imgElement?.attr("src")
                ?: imgElement?.attr("data-src")
                ?: imgElement?.attr("data-lazy-src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = "https://indiasocialbook.com/videos/page/$page/?s=$query"
        val document = app.get(searchUrl, headers = mainHeaders).document
        val items = document.select("div.thumb-block, article, div.item, div.post-box")
        val results = items.mapNotNull { it.toSearchResult() }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Unknown Title"
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.post-thumbnail img, .entry-content img")?.attr("src")
        )
        val description = document.selectFirst("div.entry-content, .description")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.posterHeaders = mainHeaders
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("IndiaSocialBook", "Loading Links for URL: $data")

        return safeApiCall {
            val res = app.get(data, headers = mainHeaders)
            val document = res.document

            // Process direct video links
            document.select("video source, audio source, .post-thumbnail video").forEach { element ->
                val src = element.attr("src").ifEmpty { element.attr("data-src") }
                if (src.isNotBlank()) {
                    val fixedUrl = fixUrl(src)
                    if (fixedUrl.endsWith(".mp4", true) || fixedUrl.contains(".m3u8")) {
                        callback.invoke(
                            newExtractorLink(
                                source = this@IndiaSocialBook.name,
                                name = this@IndiaSocialBook.name,
                                url = fixedUrl,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value
                            )
                        )
                    }
                }
            }

            // Process iframe links
            document.select("iframe, embed, object").forEach { element ->
                val src = element.attr("src").ifEmpty { element.attr("data-src") }
                if (src.isNotBlank()) {
                    val fixedUrl = fixUrl(src)
                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                }
            }

            // Process script links
            document.select("script").forEach { script ->
                val scriptText = script.data()
                val regexMatches = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4|mkv)|https?://(?:www\.)?(?:streamwish|vidhide|dood|filemoon|voe|streamtape|lulustream)[^\s"'<>]+""").findAll(scriptText)
                for (match in regexMatches) {
                    val scriptLink = match.value
                    Log.d("IndiaSocialBook", "Discovered Script Regex link: $scriptLink")
                    if (scriptLink.endsWith(".mp4", true) || scriptLink.contains(".m3u8")) {
                        callback.invoke(
                            newExtractorLink(
                                source = this@IndiaSocialBook.name,
                                name = this@IndiaSocialBook.name,
                                url = scriptLink,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value
                            )
                        )
                    } else {
                        loadExtractor(scriptLink, data, subtitleCallback, callback)
                    }
                }
            }

            true
        } ?: false
    }
}
