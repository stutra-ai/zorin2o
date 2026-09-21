package com.dune

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONArray

class ZorinMissAV : MainAPI() {
    override var mainUrl = "https://missav.live"
    override var name = "ZorinMissAV"
    override val hasMainPage = true
    override var lang = "jp"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    val subtitleCatUrl = "https://www.subtitlecat.com"

    override val mainPage = mainPageOf(
        "$mainUrl/dm169/en/weekly-hot?sort=weekly_views" to "Weekly Hot",
        "$mainUrl/dm263/en/monthly-hot?sort=views" to "Monthly Hot",
        "$mainUrl/en/new?sort=published_at" to "Newly Added",
        "$mainUrl/en/english-subtitle" to "English Subtitles",
        "$mainUrl/dm628/en/uncensored-leak" to "Uncensored Leak",
        "$mainUrl/dm150/en/fc2" to "FC2",
        "$mainUrl/dm35/en/madou" to "Madou",
        "$mainUrl/en/klive" to "K-Live",
        "$mainUrl/en/clive" to "C-Live",
        "$mainUrl/dm29/en/tokyohot" to "Tokyo Hot",
        "$mainUrl/dm1198483/en/heyzo" to "HEYZO",
        "$mainUrl/dm2469695/en/1pondo" to "1pondo",
        "$mainUrl/dm3959622/en/caribbeancom" to "Caribbeancom",
        "$mainUrl/dm48032/en/caribbeancompr" to "Caribbeancom Premium",
        "$mainUrl/dm3710098/en/10musume" to "10musume",
        "$mainUrl/dm1342558/en/pacopacomama" to "Pacopacomama",
        "$mainUrl/dm136/en/gachinco" to "Gachinco",
        "$mainUrl/dm29/en/xxxav" to "XXX-AV",
        "$mainUrl/dm24/en/marriedslash" to "Married Slash",
        "$mainUrl/dm20/en/naughty4610" to "Naughty 4610",
        "$mainUrl/dm22/en/naughty0930" to "Naughty 0930"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${separator}page=$page"

        val document = app.get(url).document

        val home = document.select("div.grid.grid-cols-2 > div, div.thumbnail.group")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true
                )
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = if (tagName() == "a") this else selectFirst("a[href*='/en/'], a[href*='/dm']") ?: return null
        val rawUrl = link.attr("abs:href").ifEmpty { link.attr("href") }
        if (rawUrl.isBlank()) return null
        val url = fixUrlNull(rawUrl.substringBefore("#")) ?: return null

        val imgElement = selectFirst("img") ?: link.selectFirst("img") ?: return null
        val posterUrl = fixUrlNull(
            imgElement.attr("abs:data-src").ifEmpty { imgElement.attr("data-src") }
                .ifEmpty { imgElement.attr("abs:src") }.ifEmpty { imgElement.attr("src") }
        ) ?: return null

        val altText = imgElement.attr("alt").takeIf { it.isNotBlank() } ?: link.attr("alt").takeIf { it.isNotBlank() }
        val baseTitle = selectFirst("div.my-2 a, div.title a, a.text-secondary, div.truncate, .truncate, div.mt-1")?.text()?.trim()
            .takeIf { !it.isNullOrBlank() && !it.contains("Chinese subtitle") }
            ?: altText
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").substringBefore("#")

        val blacklist = listOf("Recent update", "Contact", "Support", "DMCA", "Home")
        if (blacklist.any { baseTitle.equals(it, ignoreCase = true) }) return null

        val isUncensored = (link.attr("alt") + link.attr("href") + this.outerHtml())
            .contains(Regex("uncensored[-_ ]?leak", RegexOption.IGNORE_CASE))

        val title = if (isUncensored && !baseTitle.startsWith("Uncensored - ", ignoreCase = true))
            "Uncensored - $baseTitle" else baseTitle

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) {
            "${mainUrl}/en/search/${query}"
        } else {
            "${mainUrl}/en/search/${query}?page=$page"
        }

        val document = app.get(url).document

        val aramaCevap =
            document.select("div.grid.grid-cols-2 > div").mapNotNull { it.toMainPageResult() }

        return newSearchResponseList(aramaCevap, hasNext = true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-base")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val year = document.selectFirst("time")?.text()?.split("-")?.firstOrNull()?.toIntOrNull()

        val tags = document.select("div.text-secondary:contains(genre) a").map { it.text().trim() }
        val actresses = document.select("div.text-secondary:contains(actress) a").map { Actor(it.text().trim()) }

        // Fetch exact Recombee sidebar recommendations via a headless background WebView
        val recommendations = fetchWebViewRecommendations(url)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.year = year
            this.tags = tags
            addActors(actresses)
            this.recommendations = recommendations
        }
    }

    private suspend fun fetchWebViewRecommendations(targetUrl: String): List<SearchResponse> = suspendCancellableCoroutine { continuation ->
        Handler(Looper.getMainLooper()).post {
            val context = com.lagradost.cloudstream3.Common.context ?: run {
                continuation.resume(emptyList())
                return@post
            }
            
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.blockNetworkImage = true // Speeds up loading by skipping images

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Give Recombee 3 seconds to inject the recommendation cards into the DOM
                    Handler(Looper.getMainLooper()).postDelayed({
                        webView.evaluateJavascript(
                            """
                            (function() {
                                const cards = document.querySelectorAll('div.hidden.lg\\:flex div.thumbnail.group');
                                const results = [];
                                cards.forEach(c => {
                                    const a = c.querySelector('a');
                                    const img = c.querySelector('img');
                                    if (a && img) {
                                        results.push({
                                            url: a.href,
                                            poster: img.dataset.src || img.src || '',
                                            title: img.alt || ''
                                        });
                                    }
                                });
                                return JSON.stringify(results);
                            })();
                            """.trimIndent()
                        ) { jsonString ->
                            webView.destroy()
                            val list = mutableListOf<SearchResponse>()
                            try {
                                val cleanedJson = jsonString?.let { 
                                    if (it.startsWith("\"") && it.endsWith("\"")) org.json.JSONTokener(it).nextValue() as? String else it 
                                } ?: "[]"
                                
                                val jsonArray = JSONArray(cleanedJson)
                                for (i in 0 until jsonArray.length()) {
                                    val obj = jsonArray.getJSONObject(i)
                                    val recUrl = obj.optString("url")
                                    val recPoster = obj.optString("poster")
                                    val recTitle = obj.optString("title").ifEmpty { recUrl.substringAfterLast("/") }

                                    if (recUrl.isNotEmpty()) {
                                        list.add(
                                            newMovieSearchResponse(recTitle, recUrl, TvType.NSFW) {
                                                this.posterUrl = recPoster
                                            }
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Log.d("ZorinMissAV", "Error parsing WebView recommendations: ${e.message}")
                            }
                            continuation.resume(list)
                        }
                    }, 3000)
                }
            }
            webView.loadUrl(targetUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        getAndUnpack(response.text).let { unpacked ->
            val playlistId = """/([a-f0-9\-]{36})/""".toRegex().find(unpacked)?.groupValues?.get(1)

            if (playlistId != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "MissAV",
                        name = "MissAV",
                        url = "https://surrit.com/$playlistId/playlist.m3u8",
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf("Referer" to "$mainUrl/")
                    }
                )
            }
        }

        try {
            val doc = response.document
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
            val javCode = "([a-zA-Z]+-\\d+)".toRegex().find(title)?.groups?.get(1)?.value
            if(!javCode.isNullOrEmpty())
            {
                val query = "$subtitleCatUrl/index.php?search=$javCode"
                val subDoc = app.get(query, timeout = 15).document
                val subList = subDoc.select("td a")
                for(item in subList)
                {
                    if(item.text().contains(javCode,ignoreCase = true))
                    {
                        val fullUrl = "$subtitleCatUrl/${item.attr("href")}"
                        val pDoc = app.get(fullUrl, timeout = 10).document
                        val sList = pDoc.select(".col-md-6.col-lg-4")
                        for(item in sList)
                        {
                            try {
                                val language = item.select(".sub-single span:nth-child(2)").text()
                                val text = item.select(".sub-single span:nth-child(3) a")
                                if(text != null && text.size > 0 && text[0].text() == "Download")
                                {
                                    val url = "$subtitleCatUrl${text[0].attr("href")}"
                                    subtitleCallback.invoke(
                                        SubtitleFile(
                                            language.replace("\uD83D\uDC4D \uD83D\uDC4E",""),  
                                            url   
                                        )
                                    )
                                }
                            } catch (e: Exception) { }
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        return true
    }
}