package nl.giejay.android.tv.immich.shared

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import nl.giejay.android.tv.immich.api.PrivateThumbnailRequest
import nl.giejay.android.tv.immich.security.PrivateThumbnailCache
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivateThumbnailModelLoader : ModelLoader<PrivateThumbnailRequest, InputStream> {
    override fun handles(model: PrivateThumbnailRequest) = true

    override fun buildLoadData(model: PrivateThumbnailRequest, width: Int, height: Int, options: Options) =
        // Model equality is object identity. Even in-flight Glide jobs cannot join another
        // account/session. Global Glide disk/memory/bitmap caches remain disabled.
        ModelLoader.LoadData(ObjectKey(model), Fetcher(model))

    internal class Fetcher(private val model: PrivateThumbnailRequest) : DataFetcher<InputStream> {
        private val source = model.source()
        private val cancelled = AtomicBoolean(false)
        @Volatile private var stream: PrivateThumbnailCache.Lease? = null

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                if (cancelled.get()) throw IOException("Thumbnail request cancelled")
                val loaded = PrivateThumbnails.load(model, source)
                model.recordEntry(loaded.entryToken)
                stream = loaded
                if (cancelled.get()) { loaded.close(); return }
                loaded.requireCurrent()
                callback.onDataReady(loaded)
            } catch (failure: IOException) {
                cleanup()
                if (!cancelled.get()) callback.onLoadFailed(failure)
            }
        }

        override fun cleanup() { stream?.close(); stream = null }
        override fun cancel() { cancelled.set(true); source.cancel(); cleanup() }
        override fun getDataClass(): Class<InputStream> = InputStream::class.java
        override fun getDataSource(): DataSource = DataSource.LOCAL
    }

    class Factory : ModelLoaderFactory<PrivateThumbnailRequest, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory) = PrivateThumbnailModelLoader()
        override fun teardown() = Unit
    }
}
