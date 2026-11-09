package nl.giejay.android.tv.immich.shared

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.security.PrivateThumbnailCache
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual Glide decoder -> ImageView delivery using a generated, valid 2x2 PNG. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ImmichApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateThumbnailDecodeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var controller: ActivityController<Activity>
    private lateinit var image: ImageView
    private val id = "00000000-0000-0000-0000-000000000001"
    private val url get() = server.url("/api/assets/$id/thumbnail?size=thumbnail").toString()
    // Synthetic solid RGB(10,180,50), produced with a PNG encoder, not JPEG magic bytes.
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGPk2mLEwMDAxMDAwMDAAAALQgD0VhPFZAAAAABJRU5ErkJggg=="
    )

    @Before fun setup() {
        PreferenceManager.lock()
        PrivateThumbnails.init(context, PrivateThumbnailCache.Capacity.MIB_128)
        server = MockWebServer().apply { start() }
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        image = ImageView(controller.get())
        controller.get().setContentView(image)
        PreferenceManager.activateProfile(id, ApiClientConfig(server.url("/").toString(), "synthetic-key", false, false))
    }

    @After fun cleanup() {
        Glide.with(controller.get()).clear(image)
        PreferenceManager.lock()
        controller.pause().stop().destroy()
        server.shutdown()
    }

    private fun enqueuePng() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
    }

    private fun bindAndDecode(): Boolean {
        val completed = AtomicReference<Boolean?>()
        Glide.with(controller.get())
            .load(PrivateThumbnails.model(url))
            .override(8, 8)
            .listener(PrivateThumbnails.guard())
            .addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    completed.set(false)
                    return false
                }
                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    completed.set(true)
                    return false
                }
            }).into(image)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (completed.get() == null && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        assertNotNull("Glide did not finish decoding the synthetic response", completed.get())
        return completed.get()!!
    }

    @Test fun realPngReachesImageViewAndDecodesAgainFromEncryptedCache() {
        enqueuePng()
        assertTrue(bindAndDecode())
        assertTrue(image.drawable is BitmapDrawable)
        assertEquals(Color.rgb(10, 180, 50), (image.drawable as BitmapDrawable).bitmap.getPixel(0, 0))
        Glide.with(controller.get()).clear(image)
        assertTrue(bindAndDecode())
        assertTrue(image.drawable is BitmapDrawable)
        assertEquals(1, server.requestCount)
    }

    @Test fun legalJpegHeaderButInvalidImageIsEvictedAndNextBindUsesNetworkOnce() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg")
            .setBody(Buffer().write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0, 1, 2))))
        assertFalse(bindAndDecode())
        assertEquals(1, server.requestCount) // Decoder failure does not trigger an automatic retry.
        enqueuePng()
        assertTrue(bindAndDecode())
        assertTrue(image.drawable is BitmapDrawable)
        assertEquals(2, server.requestCount)
        Glide.with(controller.get()).clear(image)
        assertTrue(bindAndDecode())
        assertEquals(2, server.requestCount)
    }
}
