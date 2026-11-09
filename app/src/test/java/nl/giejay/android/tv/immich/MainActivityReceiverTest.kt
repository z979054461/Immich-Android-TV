package nl.giejay.android.tv.immich

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise both the signature-gated compatibility path and native non-exported flags. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = ImmichApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityReceiverTest {
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var receiverPermission: String
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setup() {
        receiverPermission = InstalledReceiverPermission.grant()
        app.getSharedPreferences("private_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        PreferenceManager.lock()
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        PreferenceManager.lock()
    }

    @Test fun receiverIsRestrictedToScreenOnAndUnregisteredOnDestroy() {
        val created = Robolectric.buildActivity(MainActivity::class.java,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        controller = created
        val activity = created.create().start().resume().get()
        val receiver = MainActivity::class.java.getDeclaredField("screenOnReceiver")
            .apply { isAccessible = true }.get(activity) as BroadcastReceiver
        val registration = shadowOf(app).registeredReceivers.single { it.broadcastReceiver === receiver }
        assertEquals(1, registration.intentFilter.countActions())
        assertEquals(Intent.ACTION_SCREEN_ON, registration.intentFilter.getAction(0))
        assertFalse(registration.intentFilter.hasAction("synthetic.untrusted.SCREEN_ON"))
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue(registration.flags and Context.RECEIVER_NOT_EXPORTED != 0)
            assertEquals(0, registration.flags and Context.RECEIVER_EXPORTED)
        } else {
            assertEquals(receiverPermission, registration.broadcastPermission)
        }
        created.pause().stop().destroy()
        controller = null
        assertFalse(shadowOf(app).registeredReceivers.any { it.broadcastReceiver === receiver })
    }
}
