package nl.giejay.android.tv.immich.shared

import android.content.Context
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.PrivateThumbnailRequest
import nl.giejay.android.tv.immich.api.ServerPolicy
import nl.giejay.android.tv.immich.api.ThumbnailNetworkSession
import nl.giejay.android.tv.immich.security.PrivateThumbnailCache
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import java.util.UUID
import java.util.concurrent.Executors

/** One controller and one active media session for the whole process. */
object PrivateThumbnails {
    private val cleanup = Executors.newSingleThreadExecutor { work ->
        Thread(work, "private-thumbnail-cleanup").apply { isDaemon = true }
    }
    @Volatile private var cache: PrivateThumbnailCache? = null
    private data class ActiveSession(
        val media: PrivateThumbnailCache.Session, val network: ThumbnailNetworkSession
    )
    @Volatile private var active: ActiveSession? = null

    fun init(context: Context, capacity: PrivateThumbnailCache.Capacity) {
        revoke()
        cache = PrivateThumbnailCache(context.cacheDir, cleanup, capacity)
    }

    fun begin(config: ApiClientConfig, profileId: String) {
        val controller = cache ?: return
        val profile = runCatching { UUID.fromString(profileId) }.getOrNull() ?: return
        revoke()
        val media = controller.beginSession(config, profile,
            PrivateThumbnailCache.serverFingerprint(ServerPolicy.normalizeServer(config.hostName))) {
            config === PreferenceManager.connectionSnapshot() && profileId == PreferenceManager.activeProfileId
        }
        active = ActiveSession(media, ThumbnailNetworkSession(config))
    }

    fun revoke() {
        val retired = active
        active = null
        cache?.revoke()
        retired?.network?.revoke()
    }

    fun setCapacity(capacity: PrivateThumbnailCache.Capacity) { cache?.setCapacity(capacity) }

    fun clear(completed: (Boolean) -> Unit) {
        val controller = cache ?: return completed(false)
        controller.clear { completed(it) }
    }

    /** Call while binding the view, not inside a delayed network callback. */
    fun model(url: String?, version: String = ""): PrivateThumbnailRequest? {
        if (url == null) return null
        val config = PreferenceManager.connectionSnapshot()
        val profile = PreferenceManager.activeProfileId?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        } ?: return null
        val session = active ?: return null
        return PrivateThumbnailRequest.fromUrl(config, profile, session.media, session.network, url, version)
    }

    internal fun load(model: PrivateThumbnailRequest, source: PrivateThumbnailCache.Source): PrivateThumbnailCache.Lease {
        val controller = cache ?: throw java.io.IOException("Thumbnail session unavailable")
        return controller.load(model.session, model.key, source)
    }

    /** A decoded result cannot cross lock/account/background boundaries on its way to a target. */
    fun <T : Any> guard(): RequestListener<T> = object : RequestListener<T> {
        override fun onLoadFailed(
            e: GlideException?, model: Any?, target: Target<T>, isFirstResource: Boolean
        ): Boolean {
            (model as? PrivateThumbnailRequest)?.failedEntry()?.let { cache?.reject(it) }
            // Register only this attempt's rejection here; file I/O runs in the background.
            // Retry happens on the next bind, never automatically from this callback.
            return false
        }

        override fun onResourceReady(
            resource: T, model: Any, target: Target<T>, dataSource: DataSource, isFirstResource: Boolean
        ): Boolean {
            val request = model as? PrivateThumbnailRequest ?: return true
            request.session.deliverIfCurrent { target.onResourceReady(resource, null) }
            // We delivered the target under the session gate, or deliberately suppressed it.
            return true
        }
    }
}
