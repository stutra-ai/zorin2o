package com.dune

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class XbazzPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Xbazz())
    }
}