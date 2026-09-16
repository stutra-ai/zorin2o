package com.dune

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class Mrds66Plugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Mrds66())
        // Register common extractors if needed (DPlayer usually doesn't need specific extractors unless it points to a real host)
        // registerExtractorAPI(YourExtractorHere())
    }
}