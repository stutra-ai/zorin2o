package com.pinaycum

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class PinayCumPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PinayCum())
    }
}