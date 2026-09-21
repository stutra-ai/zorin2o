package com.dune

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ZorinMissAVPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ZorinMissAV())
    }
}