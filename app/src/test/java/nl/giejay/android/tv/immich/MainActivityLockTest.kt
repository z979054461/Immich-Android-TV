package nl.giejay.android.tv.immich

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BackgroundManager
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ApplicationProvider
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.auth.ProfileFragment
import nl.giejay.android.tv.immich.security.ProfileStore
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.viewmodel.MediaSliderViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Activity lifecycle/navigation tests only. Synthetic home never creates a network client. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ImmichApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityLockTest {
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var originalStore: ProfileStore

    @Before fun clearProfiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("private_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        originalStore = PreferenceManager.profiles
        PreferenceManager.lock()
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        setStore(originalStore)
        PreferenceManager.lock()
    }

    private fun launch(saved: Bundle? = null): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java)
        controller = created
        created.create(saved).start().resume().visible()
        settle(created.get())
        return created.get()
    }

    private fun host(activity: MainActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

    private fun settle(activity: MainActivity) {
        ShadowLooper.idleMainLooper()
        activity.supportFragmentManager.executePendingTransactions()
        host(activity).childFragmentManager.executePendingTransactions()
        ShadowLooper.idleMainLooper()
    }

    private fun publish(config: ApiClientConfig) {
        PreferenceManager.javaClass.getDeclaredField("activeConnection").apply { isAccessible = true }
            .set(PreferenceManager, config)
    }

    private fun setStore(store: ProfileStore) {
        PreferenceManager.javaClass.getDeclaredField("profiles").apply { isAccessible = true }
            .set(PreferenceManager, store)
    }

    private fun syntheticUnlockedScreen(activity: MainActivity) {
        publish(ApiClientConfig("https://synthetic.invalid", "synthetic-test-key", false, false))
        val nav = host(activity).navController
        // Exercise real NavHost back-stack saving/clearing without loading a real home/photo screen.
        (nav.graph.findNode(R.id.homeFragment) as FragmentNavigator.Destination)
            .setClassName(SyntheticPhotoFragment::class.java.name)
        nav.navigate(R.id.homeFragment)
        settle(activity)
        assertTrue(PreferenceManager.isLoggedId())
        assertEquals(R.id.homeFragment, nav.currentDestination?.id)
        ViewModelProvider(activity)[MediaSliderViewModel::class.java].configuration =
            Mockito.mock(MediaSliderConfiguration::class.java)
        BackgroundManager.getInstance(activity).apply {
            if (!isAttached) attach(activity.window)
            drawable = ColorDrawable(Color.RED)
        }
    }

    private fun assertLockedPicker(activity: MainActivity) {
        settle(activity)
        assertFalse(PreferenceManager.isLoggedId())
        assertEquals("", PreferenceManager.connectionSnapshot().apiKey)
        assertEquals(R.id.profileFragment, host(activity).navController.currentDestination?.id)
        assertTrue(host(activity).childFragmentManager.primaryNavigationFragment is ProfileFragment)
        assertNull(ViewModelProvider(activity)[MediaSliderViewModel::class.java].configuration)
        assertNull(BackgroundManager.getInstance(activity).drawable)
    }

    @Test fun launchIsSecureAndNeverUsesPreexistingProcessCredentials() {
        publish(ApiClientConfig("https://synthetic.invalid", "synthetic-test-key", false, false))
        val activity = launch()
        assertLockedPicker(activity)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertEquals(255, activity.findViewById<View>(R.id.root).background.alpha)
    }

    @Test fun stopLocksAndHidesMediaThenReopenRequiresPinAgain() {
        val activity = launch()
        syntheticUnlockedScreen(activity)
        controller!!.pause().stop()
        assertFalse(PreferenceManager.isLoggedId())
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.nav_host_fragment).visibility)
        assertNull(ViewModelProvider(activity)[MediaSliderViewModel::class.java].configuration)
        assertNull(BackgroundManager.getInstance(activity).drawable)
        controller!!.restart().start().resume().visible()
        assertLockedPicker(activity)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.nav_host_fragment).visibility)
        assertFalse(host(activity).navController.popBackStack())
    }

    @Test fun launcherIntentDropsAuthenticatedBackStack() {
        val activity = launch()
        syntheticUnlockedScreen(activity)
        controller!!.newIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        assertLockedPicker(activity)
        assertFalse(host(activity).navController.popBackStack())
    }

    @Test fun savedAuthenticatedNavigationCannotRestoreAfterRecreation() {
        val old = launch()
        syntheticUnlockedScreen(old)
        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        controller = null
        // A previous in-process object must not make recreation an implicit unlock either.
        publish(ApiClientConfig("https://synthetic.invalid", "synthetic-test-key", false, false))
        val recreated = launch(state)
        assertLockedPicker(recreated)
        assertFalse(host(recreated).navController.popBackStack())
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    @Test fun cancellingPinDialogClearsInputAndDiscardsLateSuccessfulVerification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("private_profiles", Context.MODE_PRIVATE).edit()
            .putString("profiles_v1", JSONArray().put(JSONObject().put("id", "test-id").put("name", "Test User")).toString())
            .commit()
        val activity = launch()
        val profile = host(activity).childFragmentManager.primaryNavigationFragment as ProfileFragment
        descendants(profile.requireView()).filterIsInstance<Button>()
            .first { it.contentDescription == "Test User" }.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().first()
        input.setText("123456")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = Mockito.mock(ProfileStore::class.java)
        Mockito.`when`(store.unlock(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong()))
            .thenAnswer {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                ApiClientConfig("https://synthetic.invalid", "synthetic-test-key", false, false)
            }
        setStore(store)
        try {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            ShadowLooper.idleMainLooper()
            assertTrue("Verification should have started", entered.await(5, TimeUnit.SECONDS))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            ShadowLooper.idleMainLooper()
            assertFalse(dialog.isShowing)
            assertEquals("", input.text.toString())
            release.countDown()
            val busy = ProfileFragment::class.java.getDeclaredField("busy").apply { isAccessible = true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (busy.getBoolean(profile) && System.nanoTime() < deadline) {
                Thread.sleep(10)
                ShadowLooper.idleMainLooper()
            }
            assertFalse("Late verification completed", busy.getBoolean(profile))
            assertLockedPicker(activity)
        } finally {
            release.countDown()
            setStore(originalStore)
        }
    }
}

/** A harmless stand-in lets the real NavHost save an authenticated destination without HTTP. */
class SyntheticPhotoFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        TextView(requireContext()).apply { text = "Synthetic private screen" }
}
