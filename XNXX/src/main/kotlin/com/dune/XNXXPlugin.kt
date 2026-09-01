package com.dune

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XNXXPlugin: Plugin() {
    override fun load() {
        registerMainAPI(XNXX())
    }
}