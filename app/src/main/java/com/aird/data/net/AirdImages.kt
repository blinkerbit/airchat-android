package com.aird.data.net

import android.content.Context
import android.os.Build
import coil.ComponentRegistry
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.aird.session.AirdHttp

/** One image pipeline for the whole app: shared memory, a 512 MB disk cache and the session client. */
object AirdImages {
    @Volatile private var animated: ImageLoader? = null
    @Volatile private var still: ImageLoader? = null

    fun loader(context: Context): ImageLoader {
        animated?.let { return it }
        return synchronized(this) {
            animated ?: build(context.applicationContext).also { animated = it }
        }
    }

    /** Same caches, no GIF animation. For thumbnails in long lists. */
    fun stillLoader(context: Context): ImageLoader {
        still?.let { return it }
        return synchronized(this) {
            still ?: loader(context).newBuilder().components(ComponentRegistry()).build().also { still = it }
        }
    }

    private fun build(app: Context): ImageLoader = ImageLoader.Builder(app)
        .okHttpClient(AirdHttp.client)
        .memoryCache {
            MemoryCache.Builder(app).maxSizePercent(0.25).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(app.cacheDir.resolve("images"))
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(150)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .build()
}
