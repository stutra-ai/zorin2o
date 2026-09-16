package com.mrds66

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class Mrds66Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Mrds66())
    }
}