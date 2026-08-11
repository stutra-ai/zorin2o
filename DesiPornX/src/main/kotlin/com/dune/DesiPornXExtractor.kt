package com.dune

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class DesiPornXExtractor : ExtractorApi() {
    override val name = "DesiPornXEmbed"
    override val mainUrl = "https://desipornx.org"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).text

        // Regex pattern matcher for MP4/HLS streams embedded in scripts or player variables
        val mp4Regex = Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""")
        mp4Regex.findAll(document).forEach { match ->
            val videoUrl = match.groupValues[1].replace("\\/", "/")
            callback(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    quality = Qualities.Unknown.value
                )
            )
        }
    }
}