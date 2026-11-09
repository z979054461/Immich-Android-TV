package nl.giejay.android.tv.immich.api

import nl.giejay.android.tv.immich.security.PrivateThumbnailCache
import nl.giejay.android.tv.immich.security.ThumbnailKey
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.io.FilterInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable media model. Construct at bind time, never resolve a replacement account
 * when the queued load eventually executes. No shared Glide disk/memory cache is used.
 */
class PrivateThumbnailRequest private constructor(
    val session: PrivateThumbnailCache.Session,
    val key: ThumbnailKey,
    private val network: ThumbnailNetworkSession,
    private val url: HttpUrl
) {
    // Each UI bind creates a distinct model. Freeze its first decoder evidence so a late
    // failure can never be retargeted to a later storage generation/replacement image.
    private val decoderEntry = AtomicReference<PrivateThumbnailCache.EntryToken?>()
    internal fun recordEntry(entry: PrivateThumbnailCache.EntryToken?) {
        if (entry != null) decoderEntry.compareAndSet(null, entry)
    }
    internal fun failedEntry(): PrivateThumbnailCache.EntryToken? = decoderEntry.get()

    /** A new cancellable network request for this model's exact captured session. */
    fun source(): PrivateThumbnailCache.Source = object : PrivateThumbnailCache.Source {
        private val cancelled = AtomicBoolean(false)
        @Volatile private var call: Call? = null

        override fun open(): PrivateThumbnailCache.Response {
            session.requireCurrent()
            if (cancelled.get()) throw IOException("Thumbnail request cancelled")
            val pending = network.newCall(Request.Builder().url(url).build())
            call = pending.call
            if (cancelled.get()) pending.call.cancel()
            try {
                session.requireCurrent()
                val response = pending.call.execute()
                try {
                    session.requireCurrent()
                    val body = response.body ?: throw IOException("Empty thumbnail response")
                    val stream = object : FilterInputStream(body.byteStream()) {
                        override fun close() {
                            try { response.close() } finally {
                                call = null
                                network.finished(pending)
                            }
                        }
                    }
                    return PrivateThumbnailCache.Response(
                        response.code, body.contentType()?.toString(), body.contentLength(), stream
                    )
                } catch (failure: Exception) {
                    response.close()
                    throw failure
                }
            } catch (failure: Exception) {
                call = null
                network.finished(pending)
                throw failure
            }
        }

        override fun cancel() {
            cancelled.set(true)
            call?.cancel()
        }
    }

    override fun toString() = "PrivateThumbnailRequest(redacted)"

    companion object {
        /**
         * Reject originals, playback, arbitrary URLs/query parameters, encoded paths and
         * duplicate parameters. Version is trusted metadata supplied by a caller, not a URL
         * override. All requests also pass the existing origin, API-prefix and redirect guard.
         */
        internal fun fromUrl(
            config: ApiClientConfig,
            profileId: UUID,
            session: PrivateThumbnailCache.Session,
            network: ThumbnailNetworkSession,
            value: String,
            version: String = ""
        ): PrivateThumbnailRequest? {
            if (!session.matches(config, profileId) || !network.matches(config)) return null
            val url = value.toHttpUrlOrNull() ?: return null
            val key = cacheKey(config.hostName, url, version) ?: return null
            return PrivateThumbnailRequest(session, key, network, url)
        }

        internal fun cacheKey(server: String, url: HttpUrl, version: String = ""): ThumbnailKey? {
            if (version.length > 128) return null
            try {
                ServerPolicy.requireAllowed(server, url)
                val base = ServerPolicy.apiBase(server).encodedPath
                val relative = url.encodedPath.removePrefix(base)
                val segments = relative.split('/')
                if (segments.size != 3 || segments[2] != "thumbnail") return null
                // Reject UUID parser abbreviations and percent-encoded delimiters/IDs.
                val id = UUID.fromString(segments[1])
                if (!id.toString().equals(segments[1], ignoreCase = true)) return null
                return when (segments[0]) {
                    "assets" -> {
                        if (url.queryParameterNames.any { it != "size" && it != "edited" }) return null
                        if (url.queryParameterValues("size").size != 1 ||
                            url.queryParameterValues("edited").size > 1) return null
                        val size = when (url.queryParameter("size")) {
                            "thumbnail" -> ThumbnailKey.Size.THUMBNAIL
                            "preview" -> ThumbnailKey.Size.PREVIEW
                            else -> return null
                        }
                        val edited = when (url.queryParameter("edited")) {
                            null, "false" -> false
                            "true" -> true
                            else -> return null
                        }
                        ThumbnailKey.asset(id, size, edited, version)
                    }
                    "people" -> {
                        if (url.query != null) return null
                        ThumbnailKey.person(id, version)
                    }
                    else -> null
                }
            } catch (_: IOException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
    }
}
