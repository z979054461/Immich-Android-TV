package nl.giejay.android.tv.immich.shared

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.engine.cache.MemoryCacheAdapter
import com.bumptech.glide.load.engine.cache.DiskCacheAdapter
import com.bumptech.glide.load.engine.cache.DiskCache
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPoolAdapter
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import nl.giejay.android.tv.immich.api.ApiClientFactory
import java.io.InputStream

@GlideModule
class CustomGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // URL-only caches can mix accounts sharing a server. No persistent or memory cache,
        // even if an individual presenter requests DiskCacheStrategy.ALL.
        builder.setDiskCache(DiskCache.Factory { DiskCacheAdapter() })
            .setMemoryCache(MemoryCacheAdapter())
            .setBitmapPool(BitmapPoolAdapter())
            .setDefaultRequestOptions(RequestOptions().diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true))
    }
    override fun isManifestParsingEnabled() = false
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // Always guarded, including startup, logout and atomic server changes.
        registry.replace(GlideUrl::class.java, InputStream::class.java,
            OkHttpUrlLoader.Factory(ApiClientFactory.forImages()))
    }
}
