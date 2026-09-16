package com.dune

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class Mrds66Plugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Mrds66())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Voe())
        registerExtractorAPI(Streamwish())
        registerExtractorAPI(DoodStream())
        registerExtractorAPI(VidHidePro())
        registerExtractorAPI(CloudWish())
    }
}