package nl.giejay.android.tv.immich.shared.util

import android.icu.util.Calendar
import arrow.core.Either
import arrow.core.Option
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.mediaslider.model.MetaDataProvider
import nl.giejay.mediaslider.model.MetaDataType
import timber.log.Timber

class AlbumMetaDataProvider(private val assetId: String) : MetaDataProvider {
    private val session = ApiClientConfig.fromPrefs()
    override suspend fun getValue(): String? {
        if (session !== ApiClientConfig.fromPrefs()) return null
        val map: Either<String, String> = ApiClient.getClient(session).listAlbums(Option.invoke(assetId)).map { albums ->
            albums.distinct().joinToString(", ") { it.albumName }
        }
        return if (session === ApiClientConfig.fromPrefs()) map.getOrNull() else null
    }
}

class DateMetaDataProvider() : MetaDataProvider {
    override suspend fun getValue(): String? {
        return formatAssetDate(Calendar.getInstance().time)
    }
}

/**
 * Lazy [GET /assets/{id}] metadata for slider details. Field projection is delegated to
 * [AssetMetaDataMapping] so new metadata types are defined in one place.
 */
class AssetDetailMetaDataProvider(
    private val assetId: String,
    private val field: MetaDataType
) : MetaDataProvider {
    private val session = ApiClientConfig.fromPrefs()

    override suspend fun getValue(): String? {
        return AssetDetailCache.get(assetId, session).fold(
            { error ->
                Timber.w("Failed to load asset $assetId for field $field: $error")
                null
            },
            { asset -> AssetMetaDataMapping.valueOf(asset, field) }
        )
    }
}

/**
 * Completed-result LRU plus in-flight [Deferred] coalescing so concurrent detail requests for
 * the same asset share one network call (Caffeine-style without a new dependency).
 */
object AssetDetailCache {
    private const val MAX_ENTRIES = 100
    private const val LOCKED = "Profile locked or changed"
    private val monitor = Any()
    private var cachedSession: ApiClientConfig? = null
    private val cache = object : LinkedHashMap<String, Asset>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Asset>?): Boolean =
            size > MAX_ENTRIES
    }
    private val inFlight = mutableMapOf<String, CompletableDeferred<Either<String, Asset>>>()
    private val defaultFetch: suspend (ApiClientConfig, String) -> Either<String, Asset> = { session, assetId ->
        ApiClient.getClient(session).getAsset(assetId)
    }

    /** Test seam. Production requests use the provider's captured, immutable session. */
    @Volatile
    var fetchAsset: suspend (ApiClientConfig, String) -> Either<String, Asset> = defaultFetch

    private fun isCurrent(session: ApiClientConfig) =
        session === ApiClientConfig.fromPrefs() && session.apiKey.isNotEmpty() && session.hostName.isNotEmpty()

    suspend fun get(assetId: String, session: ApiClientConfig = ApiClientConfig.fromPrefs()): Either<String, Asset> {
        val (deferred, isOwner) = synchronized(monitor) {
            if (!isCurrent(session)) return LOCKED.left()
            if (cachedSession !== session) {
                cache.clear()
                inFlight.clear()
                cachedSession = session
            }
            cache[assetId]?.let { return it.right() }
            inFlight[assetId]?.let { return@synchronized it to false }
            val created = CompletableDeferred<Either<String, Asset>>()
            inFlight[assetId] = created
            created to true
        }
        if (isOwner) {
            try {
                val result = fetchAsset(session, assetId)
                synchronized(monitor) {
                    if (cachedSession === session && isCurrent(session)) {
                        result.onRight { cache[assetId] = it }
                        deferred.complete(result)
                    } else {
                        deferred.complete(LOCKED.left())
                    }
                }
            } catch (cancelled: CancellationException) {
                deferred.cancel(cancelled)
                throw cancelled
            } catch (_: Exception) {
                deferred.complete("Could not load asset details".left())
            } finally {
                synchronized(monitor) {
                    if (inFlight[assetId] === deferred) inFlight.remove(assetId)
                }
            }
        }
        val result = deferred.await()
        return if (isCurrent(session)) result else LOCKED.left()
    }

    /** Called by lock/profile switch; stale in-flight results cannot refill this cache. */
    fun clearSession() = synchronized(monitor) {
        cachedSession = null
        cache.clear()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    /** Test helper. */
    fun clear() {
        clearSession()
        fetchAsset = defaultFetch
    }
}
