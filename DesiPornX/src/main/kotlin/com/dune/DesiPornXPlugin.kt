package com.dune

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DesiPornXPlugin: Plugin() {
    override fun load() {
        registerMainAPI(DesiPornX())
        registerExtractorAPI(DesiPornXExtractor())
    }
}