package nl.giejay.android.tv.immich.shared.util

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.right
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import nl.giejay.android.tv.immich.api.model.Asset
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CompletableDeferred
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AssetDetailCacheTest {
    private lateinit var original: ApiClientConfig
    private fun publish(config: ApiClientConfig) {
        PreferenceManager.javaClass.getDeclaredField("activeConnection").apply { isAccessible = true }
            .set(PreferenceManager, config)
    }
    @Before fun startSession() {
        original = ApiClientConfig.fromPrefs()
        publish(ApiClientConfig("https://immich.example.test", "test-key", false, false))
    }


    @After
    fun tearDown() {
        AssetDetailCache.clear()
        publish(original)
    }

    @Test
    fun `concurrent get coalesces to a single fetch`() = runBlocking {
        val fetches = AtomicInteger(0)
        val asset = sampleAsset("a1")
        AssetDetailCache.fetchAsset = { _, id ->
            fetches.incrementAndGet()
            delay(50)
            asset.right()
        }

        val first = async { AssetDetailCache.get("a1") }
        val second = async { AssetDetailCache.get("a1") }

        assertSame(first.await(), second.await())
        assertEquals(1, fetches.get())
        assertEquals(asset, first.await().getOrElse { null })
    }

    @Test
    fun `second get after completion hits LRU without refetch`() = runBlocking {
        val fetches = AtomicInteger(0)
        AssetDetailCache.fetchAsset = { _, id ->
            fetches.incrementAndGet()
            sampleAsset(id).right()
        }

        AssetDetailCache.get("b1")
        AssetDetailCache.get("b1")

        assertEquals(1, fetches.get())
    }

    @Test fun `same credentials in a new session cannot reuse old cache`() = runBlocking {
        val fetches = AtomicInteger()
        AssetDetailCache.fetchAsset = { _, id -> fetches.incrementAndGet(); sampleAsset(id).right() }
        AssetDetailCache.get("shared-id")
        val old = ApiClientConfig.fromPrefs()
        publish(old.copy())
        AssetDetailCache.get("shared-id")
        assertEquals(2, fetches.get())
        assertTrue(AssetDetailCache.get("shared-id", old) is Either.Left)
    }

    @Test fun `late old profile response is discarded`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        AssetDetailCache.fetchAsset = { _, id ->
            started.complete(Unit)
            finish.await()
            sampleAsset(id).right()
        }
        val oldResult = async { AssetDetailCache.get("shared-id") }
        started.await()
        publish(ApiClientConfig.fromPrefs().copy(apiKey = "other-profile-key"))
        finish.complete(Unit)
        assertTrue(oldResult.await() is Either.Left)
    }

    @Test fun `locked session never reads cached metadata`() = runBlocking {
        AssetDetailCache.fetchAsset = { _, id -> sampleAsset(id).right() }
        AssetDetailCache.get("shared-id")
        publish(ApiClientConfig("", "", false, false))
        assertTrue(AssetDetailCache.get("shared-id") is Either.Left)
    }

    private fun sampleAsset(id: String) = Asset(
        id = id,
        type = "IMAGE",
        deviceAssetId = null,
        exifInfo = null,
        fileCreatedAt = null,
        fileModifiedAt = null,
        albumName = null,
        people = null,
        tags = null,
        originalPath = null,
        originalFileName = null,
        isFavorite = false
    )
}
