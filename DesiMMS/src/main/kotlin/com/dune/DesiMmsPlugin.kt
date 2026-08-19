@CloudstreamPlugin
class DesiMmsPlugin : Plugin() {
    companion object {
        lateinit var appContext: Context
            private set
    }

    var activity: AppCompatActivity? = null
    lateinit var imageLoader: ImageLoader
        private set

    override fun load(context: Context) {
        appContext = context
        activity = context as AppCompatActivity

        // Initialize Coil ImageLoader matching your optimized configuration block
        imageLoader = ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("desimms_image_cache")).maxSizeBytes(512L * 1024 * 1024).build() }
            .allowConversionToBitmap(true)
            .build()

        registerMainAPI(DesiMms(this))
        registerExtractorAPI(BunkrExtractor())
    }

    fun loadGallery(title: String, images: List<String>) {
        if (images.isEmpty()) return
        val act = activity ?: return

        Handler(Looper.getMainLooper()).post {
            try {
                // Uses your pre-existing GalleryFragment & ZoomHelper setup
                val frag = GalleryFragment(this, title, images)
                frag.show(act.supportFragmentManager, "DesiMmsGallery")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}