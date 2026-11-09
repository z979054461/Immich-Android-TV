package nl.giejay.android.tv.immich.diagnostics

import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ImmichApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionDiagnosticsLifecycleTest {
    private lateinit var server: MockWebServer
    private var controller: ActivityController<FragmentActivity>? = null
    private lateinit var fragment: ConnectionDiagnosticsFragment
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val created = Robolectric.buildActivity(FragmentActivity::class.java)
        controller = created
        created.get().setTheme(R.style.AppTheme)
        created.create().start().resume().visible()
        val activity = created.get()
        val container = FrameLayout(activity).apply { id = View.generateViewId() }
        activity.setContentView(container)
        val session = ApiClientConfig(server.url("/").toString(), "synthetic-key", false, false)
        PreferenceManager.javaClass.getDeclaredField("activeConnection").apply { isAccessible = true }.set(PreferenceManager, session)
        fragment = ConnectionDiagnosticsFragment()
        activity.supportFragmentManager.beginTransaction().replace(container.id, fragment).commitNow()
        ShadowLooper.idleMainLooper()
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        PreferenceManager.lock()
        server.shutdown()
    }
    private fun model() = ViewModelProvider(fragment)[ConnectionDiagnosticsViewModel::class.java]

    @Test fun openingIsIdleAndBackgroundCancelsClearsAndDoesNotRestart() {
        assertEquals(0, server.requestCount)
        assertTrue(fragment.requireView().findViewById<Button>(R.id.diagnostics_start).isEnabled)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        fragment.requireView().findViewById<Button>(R.id.diagnostics_start).performClick()
        ShadowLooper.idleMainLooper()
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(DiagnosticRunState.RUNNING, model().state.value.runState)
        assertTrue(fragment.requireView().findViewById<Button>(R.id.diagnostics_cancel).isEnabled)
        controller!!.pause().stop()
        ShadowLooper.idleMainLooper()
        assertEquals(DiagnosticRunState.IDLE, model().state.value.runState)
        assertTrue(model().state.value.results.isEmpty())
        assertEquals("", fragment.requireView().findViewById<TextView>(R.id.diagnostics_results).text.toString())
        controller!!.restart().start().resume().visible()
        ShadowLooper.idleMainLooper()
        assertEquals(DiagnosticRunState.IDLE, model().state.value.runState)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancelAndLeavingTheScreenNeverPreserveResults() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        fragment.requireView().findViewById<Button>(R.id.diagnostics_start).performClick()
        ShadowLooper.idleMainLooper()
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        fragment.requireView().findViewById<Button>(R.id.diagnostics_cancel).performClick()
        ShadowLooper.idleMainLooper()
        val vm = model()
        assertEquals(DiagnosticRunState.CANCELLED, vm.state.value.runState)
        assertTrue(vm.state.value.results.isEmpty())
        controller!!.get().supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        ShadowLooper.idleMainLooper()
        assertTrue(vm.state.value.results.isEmpty())
        assertEquals(1, server.requestCount)
    }
}
