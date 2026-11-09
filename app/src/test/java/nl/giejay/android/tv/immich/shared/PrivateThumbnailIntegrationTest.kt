package nl.giejay.android.tv.immich.shared

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.RadioButton
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.request.target.Target
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.PrivateThumbnailRequest
import nl.giejay.android.tv.immich.security.PrivateThumbnailCache
import nl.giejay.android.tv.immich.settings.ThumbnailCacheSettingsDialog
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch

/** Real loopback HTTP and production model/fetcher/session hooks; never accesses a NAS. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ImmichApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateThumbnailIntegrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var otherServer: MockWebServer
    private val profileA = "00000000-0000-0000-0000-000000000001"
    private val profileB = "00000000-0000-0000-0000-000000000002"
    private val assetId = "00000000-0000-0000-0000-000000000003"
    private val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 7, 8, 9)
    private val path get() = "/api/assets/$assetId/thumbnail?size=thumbnail&edited=false"
    private val url get() = server.url(path).toString()

    @Before fun setup() {
        PreferenceManager.lock()
        PreferenceManager.sharedPreference.edit().remove("private_thumbnail_cache_mib").commit()
        PrivateThumbnails.init(context, PrivateThumbnailCache.Capacity.MIB_128)
        val profiles = JSONArray().put(JSONObject().put("id", profileA).put("name", "Synthetic A")
            .put("sessionRevision", "revision-a").put("pinGraceMinutes", 5))
            .put(JSONObject().put("id", profileB).put("name", "Synthetic B").put("sessionRevision", "revision-b"))
        context.getSharedPreferences("private_profiles", Context.MODE_PRIVATE).edit()
            .putString("profiles_v1", profiles.toString()).commit()
        server = MockWebServer().apply { start() }
        otherServer = MockWebServer().apply { start() }
    }

    @After fun cleanup() {
        PreferenceManager.lock()
        server.shutdown()
        otherServer.shutdown()
        PreferenceManager.sharedPreference.edit().remove("private_thumbnail_cache_mib").commit()
    }

    private fun activate(id: String = profileA, key: String = "synthetic-key-a") {
        PreferenceManager.activateProfile(id, ApiClientConfig(server.url("/").toString().trimEnd('/'), key, false, false))
    }

    private fun enqueue(bytes: ByteArray = image) {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes)))
    }

    private fun fetch(model: PrivateThumbnailRequest): Pair<ByteArray?, Exception?> {
        var bytes: ByteArray? = null
        var error: Exception? = null
        val fetcher = PrivateThumbnailModelLoader.Fetcher(model)
        fetcher.loadData(Priority.NORMAL, object : DataFetcher.DataCallback<InputStream> {
            override fun onDataReady(data: InputStream?) { bytes = data!!.use { it.readBytes() } }
            override fun onLoadFailed(e: Exception) { error = e }
        })
        fetcher.cleanup()
        return bytes to error
    }

    @Test fun cacheUsesCapturedCredentialsAndSharedGlideCachesStayUnnecessary() {
        activate()
        enqueue()
        val first = fetch(PrivateThumbnails.model(url)!!)
        assertArrayEquals(image, first.first)
        assertNull(first.second)
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(listOf("synthetic-key-a"), recorded.headers.values("x-api-key"))
        assertEquals(path, recorded.path)
        assertArrayEquals(image, fetch(PrivateThumbnails.model(url)!!).first)
        assertEquals(1, server.requestCount)
    }

    @Test fun twoDifferentThumbnailsReuseOneConnectionEvenWithDiskCacheOff() {
        activate()
        PreferenceManager.setThumbnailCacheCapacity(profileA, PrivateThumbnailCache.Capacity.OFF)
        val captured = PrivateThumbnails.model(url)!!
        enqueue()
        assertArrayEquals(image, fetch(captured).first)
        val anotherUrl = server.url(path.replace(assetId, "00000000-0000-0000-0000-000000000004")).toString()
        enqueue()
        assertArrayEquals(image, fetch(PrivateThumbnails.model(anotherUrl)!!).first)
        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        val second = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertNotEquals(first.path, second.path)
        assertEquals(0, first.sequenceNumber)
        assertEquals(1, second.sequenceNumber)
        assertEquals("synthetic-key-a", second.getHeader("x-api-key"))
        activate(profileB, "synthetic-key-b")
        assertTrue(fetch(captured).second is IOException)
        enqueue()
        assertArrayEquals(image, fetch(PrivateThumbnails.model(url)!!).first)
        val next = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(0, next.sequenceNumber)
        assertEquals("synthetic-key-b", next.getHeader("x-api-key"))
        assertEquals(3, server.requestCount)
    }

    @Test fun switchingCancelsOldInFlightBodyAndCannotDeliverItsResult() {
        activate()
        val captured = PrivateThumbnails.model(url)!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val normalDispatcher = server.dispatcher
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(image))
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val old = executor.submit<Pair<ByteArray?, Exception?>> { fetch(captured) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            activate(profileB, "synthetic-key-b")
            val rejected = old.get(3, TimeUnit.SECONDS)
            assertNull(rejected.first)
            assertTrue(rejected.second is IOException)
            server.dispatcher = normalDispatcher
            release.countDown()
            enqueue()
            assertArrayEquals(image, fetch(PrivateThumbnails.model(url)!!).first)
            val next = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("synthetic-key-b", next.getHeader("x-api-key"))
            assertEquals(0, next.sequenceNumber)
        } finally {
            server.dispatcher = normalDispatcher
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun switchingSameServerNeverReusesAnotherProfilesImageOrCapturedModel() {
        activate()
        val captured = PrivateThumbnails.model(url)!!
        enqueue()
        assertArrayEquals(image, fetch(captured).first)
        activate(profileB, "synthetic-key-b")
        val rejected = fetch(captured)
        assertNull(rejected.first)
        assertTrue(rejected.second is IOException)
        val nextImage = image + byteArrayOf(42)
        enqueue(nextImage)
        assertArrayEquals(nextImage, fetch(PrivateThumbnails.model(url)!!).first)
        server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("synthetic-key-b", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("x-api-key"))
        assertEquals(2, server.requestCount)
    }

    @Test fun backgroundEvenWithinGraceRevokesStreamsAndRequiresFreshNetwork() {
        activate()
        val model = PrivateThumbnails.model(url)!!
        val original = PreferenceManager.connectionSnapshot()
        enqueue()
        val held = PrivateThumbnails.load(model, model.source())
        assertEquals(255, held.read())
        PreferenceManager.suspendForBackground()
        assertFalse(model.session.isCurrent)
        assertNull(PrivateThumbnails.model(url))
        assertThrows(IOException::class.java) { held.read() }
        assertTrue(PreferenceManager.resumeFromBackground())
        assertNotSame(original, PreferenceManager.connectionSnapshot())
        assertTrue(fetch(model).second is IOException)
        val next = PrivateThumbnails.model(url)!!
        assertNotSame(model.session, next.session)
        enqueue()
        assertArrayEquals(image, fetch(next).first)
        assertEquals(2, server.requestCount)
    }

    @Test fun repeatedActivationOfSameConfigStillCreatesNewIdentityAndSession() {
        val supplied = ApiClientConfig(server.url("/").toString(), "synthetic", false, false)
        PreferenceManager.activateProfile(profileA, supplied)
        val first = PreferenceManager.connectionSnapshot()
        val model = PrivateThumbnails.model(url)!!
        PreferenceManager.lock()
        PreferenceManager.activateProfile(profileA, supplied)
        assertNotSame(first, PreferenceManager.connectionSnapshot())
        assertFalse(model.session.isCurrent)
        assertTrue(PrivateThumbnails.model(url)!!.session.isCurrent)
    }

    @Test fun originalsVideosAndArbitraryUrlsNeverEnterCacheLoader() {
        activate()
        listOf("/api/assets/$assetId/original", "/api/assets/$assetId/video/playback", "/api/albums",
            "/api/assets/$assetId/thumbnail?size=original").forEach {
            assertNull(PrivateThumbnails.model(server.url(it).toString()))
        }
        assertNull(PrivateThumbnails.model(otherServer.url(path).toString()))
        assertEquals(0, server.requestCount)
        assertEquals(0, otherServer.requestCount)
    }

    @Test fun redirectsErrorsAndWrongTypesNeverBecomeCacheHits() {
        activate()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", otherServer.url(path)))
        assertTrue(fetch(PrivateThumbnails.model(url)!!).second is IOException)
        assertEquals(0, otherServer.requestCount)
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "image/jpeg")
            .setBody(Buffer().write(image)))
        assertTrue(fetch(PrivateThumbnails.model(url)!!).second is IOException)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
        assertTrue(fetch(PrivateThumbnails.model(url)!!).second is IOException)
        enqueue()
        assertArrayEquals(image, fetch(PrivateThumbnails.model(url)!!).first)
        assertEquals(4, server.requestCount)
    }

    @Test fun offContinuesNormalNetworkAndRejectsStaleSettingWrites() {
        activate()
        val config = PreferenceManager.connectionSnapshot()
        val bound = PrivateThumbnails.model(url)!!
        assertEquals(PrivateThumbnailCache.Capacity.MIB_128, PreferenceManager.thumbnailCacheCapacity())
        PreferenceManager.setThumbnailCacheCapacity(profileA, PrivateThumbnailCache.Capacity.OFF)
        assertSame(config, PreferenceManager.connectionSnapshot())
        assertTrue(bound.session.isCurrent)
        repeat(2) {
            enqueue()
            assertArrayEquals(image, fetch(bound).first)
        }
        assertEquals(2, server.requestCount)
        PreferenceManager.setThumbnailCacheCapacity(profileA, PrivateThumbnailCache.Capacity.MIB_64)
        enqueue()
        assertArrayEquals(image, fetch(bound).first)
        assertArrayEquals(image, fetch(bound).first)
        assertEquals(3, server.requestCount)
        activate(profileB)
        assertThrows(IllegalStateException::class.java) {
            PreferenceManager.setThumbnailCacheCapacity(profileA, PrivateThumbnailCache.Capacity.MIB_256)
        }
        assertEquals(PrivateThumbnailCache.Capacity.MIB_64, PreferenceManager.thumbnailCacheCapacity())
    }

    @Test fun lateDecodedResultCannotReachTargetAfterLockOrSwitch() {
        activate()
        val model = PrivateThumbnails.model(url)!!
        val listener = PrivateThumbnails.guard<String>()
        val target = mock<Target<String>>()
        assertTrue(listener.onResourceReady("decoded", model, target, DataSource.LOCAL, true))
        verify(target).onResourceReady("decoded", null)
        clearInvocations(target)
        PreferenceManager.lock()
        assertTrue(listener.onResourceReady("old decoded", model, target, DataSource.LOCAL, true))
        verifyNoInteractions(target)
        activate(profileB)
        assertTrue(listener.onResourceReady("old decoded", model, target, DataSource.LOCAL, true))
        verifyNoInteractions(target)
    }

    @Test fun cancelledFetcherDoesNotDeliverOrStartNetwork() {
        activate()
        val fetcher = PrivateThumbnailModelLoader.Fetcher(PrivateThumbnails.model(url)!!)
        fetcher.cancel()
        val callback = mock<DataFetcher.DataCallback<InputStream>>()
        fetcher.loadData(Priority.NORMAL, callback)
        verifyNoInteractions(callback)
        assertEquals(0, server.requestCount)
    }

    @Test fun settingsHaveOnlyFourSafeLimitsAndStaleDialogCannotChangeThem() {
        activate()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val dialog = ThumbnailCacheSettingsDialog.show(activity.get(), profileA)!!
            val decor = dialog.window!!.decorView
            val off = decor.findViewWithTag<RadioButton>("thumbnail_cache_choice_OFF")
            val selected = decor.findViewWithTag<RadioButton>("thumbnail_cache_choice_MIB_128")
            assertTrue(selected.isChecked)
            off.performClick()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(PrivateThumbnailCache.Capacity.OFF, PreferenceManager.thumbnailCacheCapacity())
            assertFalse(dialog.isShowing)
            val stale = ThumbnailCacheSettingsDialog.show(activity.get(), profileA)!!
            stale.window!!.decorView.findViewWithTag<RadioButton>("thumbnail_cache_choice_MIB_256").performClick()
            activate(profileB)
            stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(PrivateThumbnailCache.Capacity.OFF, PreferenceManager.thumbnailCacheCapacity())
            stale.dismiss()
            assertEquals(4, PrivateThumbnailCache.Capacity.values().size)
            assertEquals(PrivateThumbnailCache.Capacity.MIB_128, PrivateThumbnailCache.Capacity.fromMiB(999))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun clearButtonKeepsCapacitySessionAndDialogThenLoadsAgain() {
        activate()
        val model = PrivateThumbnails.model(url)!!
        val connection = PreferenceManager.connectionSnapshot()
        enqueue()
        assertArrayEquals(image, fetch(model).first)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val dialog = ThumbnailCacheSettingsDialog.show(activity.get(), profileA)!!
            // Unsaved radio selection must not be applied by the separate clear action.
            dialog.window!!.decorView.findViewWithTag<RadioButton>("thumbnail_cache_choice_MIB_64").performClick()
            val clear = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            clear.performClick()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!clear.isEnabled && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(10)
            }
            ShadowLooper.idleMainLooper()
            assertTrue(clear.isEnabled)
            assertTrue(dialog.isShowing)
            assertEquals(context.getString(nl.giejay.android.tv.immich.R.string.thumbnail_cache_cleared),
                ShadowToast.getTextOfLatestToast())
            assertEquals(PrivateThumbnailCache.Capacity.MIB_128, PreferenceManager.thumbnailCacheCapacity())
            assertSame(connection, PreferenceManager.connectionSnapshot())
            assertTrue(model.session.isCurrent)
            enqueue()
            assertArrayEquals(image, fetch(model).first)
            assertArrayEquals(image, fetch(model).first)
            assertEquals(2, server.requestCount)
            dialog.dismiss()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun staleClearDialogCannotClearAnotherProfilesCache() {
        activate()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val stale = ThumbnailCacheSettingsDialog.show(activity.get(), profileA)!!
            val oldConnection = PreferenceManager.connectionSnapshot()
            activate(profileB, "synthetic-key-b")
            val model = PrivateThumbnails.model(url)!!
            enqueue()
            assertArrayEquals(image, fetch(model).first)
            stale.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertEquals(context.getString(nl.giejay.android.tv.immich.R.string.thumbnail_cache_clear_failed),
                ShadowToast.getTextOfLatestToast())
            assertThrows(IllegalStateException::class.java) {
                PreferenceManager.clearThumbnailCache(profileA, oldConnection) { fail("Stale clear must not run") }
            }
            enqueue() // A broken clear would make this a second request rather than hang the test.
            assertArrayEquals(image, fetch(model).first)
            assertEquals(1, server.requestCount)
            assertTrue(model.session.isCurrent)
            stale.dismiss()
        } finally { activity.pause().stop().destroy() }
    }
}
