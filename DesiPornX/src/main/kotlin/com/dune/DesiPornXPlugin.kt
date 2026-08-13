// DesiPornXPlugin.kt
package com.dune

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DesiPornXPlugin : Plugin() {
    override fun load() {
        registerMainAPI(DesiPornX())
    }
}