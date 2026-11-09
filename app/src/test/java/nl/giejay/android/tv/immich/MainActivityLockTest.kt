package nl.giejay.android.tv.immich

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.leanback.app.BackgroundManager
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ApplicationProvider
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.auth.ProfileFragment
import nl.giejay.android.tv.immich.auth.TvPinPad
import nl.giejay.android.tv.immich.security.ProfileStore
import nl.giejay.android.tv.immich.security.ProfileSummary
import nl.giejay.android.tv.immich.security.ProfileSessionPolicy
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_SET
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
import org.mockito.kotlin.eq
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        InstalledReceiverPermission.grant()
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("private_profiles", Context.MODE_PRIVATE).edit()
            .putString("profiles_v1", JSONArray().put(JSONObject().put("id", "immediate-id")
                .put("name", "Immediate Profile").put("pinGraceMinutes", 0)).toString()).commit()
        PreferenceManager.activateProfile("immediate-id", ApiClientConfig("https://synthetic.invalid", "synthetic-test-key", false, false))
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

    @Test fun screensaverSetupOnlyExplainsForegroundPreviewWithoutLaunchingSystemSettings() {
        val activity = launch()
        val navigation = host(activity).navController
        val originalDestination = navigation.currentDestination?.id
        assertNull(shadowOf(activity).nextStartedActivity)

        // Reopening and dismissing the help must not navigate or launch a system activity.
        repeat(2) {
            assertTrue(SCREENSAVER_SET.onClick(activity, navigation))
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)
            assertTrue(dialog.isShowing)
            assertEquals(activity.getString(R.string.screensaver_preview_help),
                dialog.findViewById<TextView>(android.R.id.message).text.toString())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(originalDestination, navigation.currentDestination?.id)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            settle(activity)
            assertFalse(dialog.isShowing)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
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

    private fun seedPicker(pinLength: Int? = null) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val row = JSONObject().put("id", "test-id").put("name", "Test User").put("pinGraceMinutes", 0)
        if (pinLength != null) row.put("pinLength", pinLength)
        context.getSharedPreferences("private_profiles", Context.MODE_PRIVATE).edit()
            .putString("profiles_v1", JSONArray().put(row).toString()).commit()
    }

    private fun profile(activity: MainActivity) =
        host(activity).childFragmentManager.primaryNavigationFragment as ProfileFragment

    private fun openPin(activity: MainActivity): AlertDialog {
        descendants(profile(activity).requireView()).filterIsInstance<Button>()
            .first { it.contentDescription == "Test User" }.performClick()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun pad(dialog: AlertDialog) = descendants(dialog.window!!.decorView).filterIsInstance<TvPinPad>().single()
    private fun key(dialog: AlertDialog, tag: String) = dialog.window!!.decorView.findViewWithTag<Button>(tag)
    private fun text(dialog: AlertDialog, tag: String) = dialog.window!!.decorView.findViewWithTag<TextView>(tag).text.toString()
    private fun digits(dialog: AlertDialog, pin: String) { pin.forEach { key(dialog, "pin_digit_$it").performClick() } }
    private fun assertCleared(dialog: AlertDialog) { assertFalse(text(dialog, "pin_masked").contains('●')) }

    private fun waitForPinWork(fragment: ProfileFragment) {
        val busy = ProfileFragment::class.java.getDeclaredField("busy").apply { isAccessible = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (busy.getBoolean(fragment) && System.nanoTime() < deadline) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
        }
        assertFalse("PIN work completed", busy.getBoolean(fragment))
    }

    private fun verifiedStore(pinLength: Int? = null): ProfileStore = Mockito.mock(ProfileStore::class.java).also { store ->
        Mockito.`when`(store.profiles()).thenReturn(listOf(ProfileSummary("test-id", "Test User", pinLength)))
        Mockito.`when`(store.sessionPolicy(Mockito.anyString())).thenReturn(ProfileSessionPolicy(0, "synthetic-revision"))
        Mockito.`when`(store.unlock(eq("test-id"), eq("123456"), Mockito.anyLong(), Mockito.anyLong()))
            .thenReturn(ApiClientConfig("https://synthetic.invalid", "synthetic-test-key", false, false))
        setStore(store)
    }

    private fun openMigration(activity: MainActivity): AlertDialog {
        val unlock = openPin(activity)
        digits(unlock, "123456")
        assertEquals(6, text(unlock, "pin_masked").count { it == '●' })
        key(unlock, "pin_confirm").performClick()
        waitForPinWork(profile(activity))
        val migration = ShadowAlertDialog.getLatestAlertDialog()
        assertNotSame(unlock, migration)
        assertFalse(unlock.isShowing)
        assertTrue(migration.isShowing)
        assertFalse(PreferenceManager.isLoggedId())
        assertTrue(migration.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertEquals(activity.getString(R.string.profiles_pin_migrate_choose), text(migration, "pin_instruction"))
        return migration
    }

    private fun replaceHomeWithSyntheticScreen(activity: MainActivity) {
        val manager = host(activity).childFragmentManager
        val originalFactory = manager.fragmentFactory
        val homeName = (host(activity).navController.graph.findNode(R.id.homeFragment) as FragmentNavigator.Destination).className
        manager.fragmentFactory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
                if (className == homeName) SyntheticPhotoFragment() else originalFactory.instantiate(classLoader, className)
        }
    }

    private fun enterNewPin(dialog: AlertDialog, pin: String = "0123", confirmation: String = pin) {
        digits(dialog, pin)
        assertEquals(dialog.context.getString(R.string.profiles_pin_migrate_confirm), text(dialog, "pin_instruction"))
        assertCleared(dialog)
        digits(dialog, confirmation)
    }

    @Test fun legacyPinNeverSubmitsTheFirstFourDigitsAndMigrationCanBeCancelled() {
        seedPicker()
        val activity = launch()
        val store = verifiedStore()
        val wrong = openPin(activity)
        digits(wrong, "1234")
        Mockito.verify(store, Mockito.never()).unlock(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong())
        key(wrong, "pin_confirm").performClick()
        waitForPinWork(profile(activity))
        assertSame(wrong, ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(wrong.isShowing)
        assertEquals(activity.getString(R.string.profiles_wrong_pin), text(wrong, "pin_status"))
        wrong.cancel()
        ShadowLooper.idleMainLooper()

        repeat(2) {
            val migration = openMigration(activity)
            enterNewPin(migration, "1234", "4321")
            assertEquals(activity.getString(R.string.profiles_pin_mismatch), text(migration, "pin_status"))
            assertEquals(activity.getString(R.string.profiles_pin_migrate_choose), text(migration, "pin_instruction"))
            assertFalse(PreferenceManager.isLoggedId())
            digits(migration, "0123")
            digits(migration, "01")
            if (it == 0) migration.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() else migration.cancel()
            ShadowLooper.idleMainLooper()
            assertFalse(migration.isShowing)
            assertCleared(migration)
            assertLockedPicker(activity)
        }
        Mockito.verify(store, Mockito.never()).changePin(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong())
        Mockito.verify(store, Mockito.times(2)).unlock(eq("test-id"), eq("123456"), Mockito.anyLong(), Mockito.anyLong())
    }

    @Test fun failedMigrationStaysLockedAndAllowsRetryWithFreshTwoStageInput() {
        seedPicker()
        val activity = launch()
        val store = verifiedStore()
        val migration = openMigration(activity)
        Mockito.`when`(store.changePin(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong()))
            .thenThrow(IllegalStateException("Synthetic unavailable storage"))
        repeat(2) {
            enterNewPin(migration)
            waitForPinWork(profile(activity))
            assertTrue(migration.isShowing)
            assertTrue(key(migration, "pin_digit_1").isEnabled)
            assertTrue(migration.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            assertCleared(migration)
            assertEquals(activity.getString(R.string.profiles_pin_save_failed), text(migration, "pin_status"))
            assertEquals(activity.getString(R.string.profiles_pin_migrate_choose), text(migration, "pin_instruction"))
            assertLockedPicker(activity)
        }
        Mockito.verify(store, Mockito.times(2)).changePin(eq("test-id"), eq("123456"), eq("0123"), Mockito.anyLong(), Mockito.anyLong())
        migration.cancel()
        ShadowLooper.idleMainLooper()
        assertCleared(openPin(activity))
    }

    @Test fun backgroundDuringMigrationClearsDigitsAndRequiresFullUnlockAgain() {
        seedPicker()
        val activity = launch()
        val store = verifiedStore()
        val migration = openMigration(activity)
        digits(migration, "0123")
        digits(migration, "01")
        controller!!.pause().stop()
        ShadowLooper.idleMainLooper()
        assertFalse(migration.isShowing)
        assertCleared(migration)
        assertFalse(PreferenceManager.isLoggedId())
        controller!!.restart().start().resume().visible()
        assertLockedPicker(activity)
        Mockito.verify(store, Mockito.never()).changePin(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong())
        assertCleared(openPin(activity))
    }

    @Test fun recreationCannotRestoreMigrationAuthorizationOrEnteredPins() {
        seedPicker()
        val activity = launch()
        val store = verifiedStore()
        val migration = openMigration(activity)
        digits(migration, "0123")
        digits(migration, "01")
        assertFalse(pad(migration).isSaveEnabled)
        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        controller = null
        ShadowLooper.idleMainLooper()
        assertFalse(migration.isShowing)
        assertCleared(migration)
        val recreated = launch(state)
        assertLockedPicker(recreated)
        assertCleared(openPin(recreated))
        Mockito.verify(store, Mockito.never()).changePin(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong())
    }

    @Test fun repeatedMigrationSubmitCannotChangeTwiceOrUnlockAfterBackground() {
        seedPicker()
        val activity = launch()
        val store = verifiedStore()
        val migration = openMigration(activity)
        val fragment = profile(activity)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.`when`(store.changePin(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong()))
            .thenAnswer {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                ApiClientConfig("https://synthetic.invalid", "synthetic-test-key", false, false)
            }
        try {
            enterNewPin(migration)
            assertEquals(activity.getString(R.string.profiles_pin_saving), text(migration, "pin_status"))
            assertFalse(key(migration, "pin_digit_1").isEnabled)
            assertFalse(migration.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            ShadowLooper.idleMainLooper()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            digits(migration, "01230123")
            key(migration, "pin_confirm").performClick()
            controller!!.pause().stop()
            release.countDown()
            waitForPinWork(fragment)
            assertFalse(migration.isShowing)
            assertFalse(PreferenceManager.isLoggedId())
            controller!!.restart().start().resume().visible()
            assertLockedPicker(activity)
            Mockito.verify(store, Mockito.times(1)).changePin(eq("test-id"), eq("123456"), eq("0123"), Mockito.anyLong(), Mockito.anyLong())
        } finally { release.countDown() }
    }

    @Test fun savedMigrationUnlocksOnlyAfterCommitAndNextLaunchStillNeedsPin() {
        seedPicker()
        val activity = launch()
        val store = verifiedStore()
        val nav = host(activity).navController
        replaceHomeWithSyntheticScreen(activity)
        val migration = openMigration(activity)
        val fragment = profile(activity)
        Mockito.`when`(store.changePin(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong()))
            .thenReturn(ApiClientConfig("https://synthetic.invalid", "synthetic-test-key", false, false))
        enterNewPin(migration)
        waitForPinWork(fragment)
        settle(activity)
        assertFalse(migration.isShowing)
        assertTrue(PreferenceManager.isLoggedId())
        assertEquals(R.id.homeFragment, nav.currentDestination?.id)
        assertCleared(migration)
        controller!!.pause().stop().restart().start().resume().visible()
        assertLockedPicker(activity)
    }

    @Test fun fourthDigitSubmitsOnceShowsImmediateFeedbackAndUnlocksWithoutAnotherClick() {
        seedPicker(pinLength = 4)
        val activity = launch()
        val store = verifiedStore(pinLength = 4)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.`when`(store.unlock(eq("test-id"), eq("0123"), Mockito.anyLong(), Mockito.anyLong()))
            .thenAnswer {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                ApiClientConfig("https://synthetic.invalid", "synthetic-test-key", false, false)
            }
        replaceHomeWithSyntheticScreen(activity)
        val fragment = profile(activity)
        val unlock = openPin(activity)
        try {
            digits(unlock, "012")
            Mockito.verify(store, Mockito.never()).unlock(eq("test-id"), eq("0123"), Mockito.anyLong(), Mockito.anyLong())
            digits(unlock, "3")
            // No looper advancement is needed for feedback: it is set before IO starts.
            assertEquals(activity.getString(R.string.profiles_pin_verifying), text(unlock, "pin_status"))
            assertFalse(key(unlock, "pin_digit_0").isEnabled)
            assertCleared(unlock)
            digits(unlock, "0123")
            pad(unlock).handleKey(KeyEvent.KEYCODE_4, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_4))
            ShadowLooper.idleMainLooper()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(PreferenceManager.isLoggedId())
            release.countDown()
            waitForPinWork(fragment)
            settle(activity)
            assertTrue(PreferenceManager.isLoggedId())
            assertFalse(unlock.isShowing)
            assertSame(unlock, ShadowAlertDialog.getLatestAlertDialog())
            assertEquals(R.id.homeFragment, host(activity).navController.currentDestination?.id)
            Mockito.verify(store, Mockito.times(1)).unlock(eq("test-id"), eq("0123"), Mockito.anyLong(), Mockito.anyLong())
            Mockito.verify(store, Mockito.never()).changePin(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong())
        } finally { release.countDown() }
    }

    @Test fun cancellingPinDialogClearsInputAndDiscardsLateSuccessfulVerification() {
        seedPicker(pinLength = 4)
        val activity = launch()
        val fragment = profile(activity)
        val store = verifiedStore(pinLength = 4)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.`when`(store.unlock(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong()))
            .thenAnswer {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                ApiClientConfig("https://synthetic.invalid", "synthetic-test-key", false, false)
            }
        val unlock = openPin(activity)
        assertTrue(unlock.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        try {
            digits(unlock, "0123")
            ShadowLooper.idleMainLooper()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            unlock.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            ShadowLooper.idleMainLooper()
            assertFalse(unlock.isShowing)
            assertCleared(unlock)
            release.countDown()
            waitForPinWork(fragment)
            assertLockedPicker(activity)
        } finally { release.countDown() }
    }

}

/** A harmless stand-in lets the real NavHost save an authenticated destination without HTTP. */
class SyntheticPhotoFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        TextView(requireContext()).apply { text = "Synthetic private screen" }
}
