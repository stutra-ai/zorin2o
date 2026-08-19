package com.kraptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import coil3.ImageLoader
import coil3.bitmapFactoryExifOrientationStrategy
import coil3.bitmapFactoryMaxParallelism
import coil3.decode.BitmapFactoryDecoder
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.allowConversionToBitmap
import coil3.request.maxBitmapSize
import coil3.size.Size
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DesiMmsPlugin : Plugin() {

    companion object {
        lateinit var appContext: Context
            private set
    }

    var activity: AppCompatActivity? = null
    lateinit var imageLoader: ImageLoader
        private set

    @SuppressLint("SuspiciousIndentation")
    override fun load(context: Context) {
        appContext = context
        activity = context as AppCompatActivity

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        imageLoader = ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("desimms_image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .maxBitmapSize(Size(screenWidth, screenHeight * 2))
            .bitmapFactoryMaxParallelism(2)
            .bitmapFactoryExifOrientationStrategy(coil3.decode.ExifOrientationStrategy.RESPECT_PERFORMANCE)
            .allowConversionToBitmap(true)
            .components {
                add(BitmapFactoryDecoder.Factory())
                add(Interceptor { chain ->
                    val headers = NetworkHeaders.Builder()
                        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .add("Referer", "https://desimms.net/")
                        .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                        .build()
                    chain.withRequest(chain.request.newBuilder().httpHeaders(headers).build()).proceed()
                })
            }
            .build()

        registerMainAPI(DesiMms(this))
        registerExtractorAPI(BunkrExtractor())
        registerExtractorAPI(CDNBunkrExtractor())
        registerExtractorAPI(BunkrCrExtractor())
    }

    fun loadGallery(title: String, images: List<String>) {
        if (images.isEmpty()) return
        val act = activity ?: return

        Handler(Looper.getMainLooper()).post {
            try {
                // Uses the matching GalleryFragment & ZoomHelper architecture
                val frag = GalleryFragment(this, title, images)
                frag.show(act.supportFragmentManager, "DesiMmsGallery")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}