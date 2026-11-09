package nl.giejay.android.tv.immich

import android.app.AlertDialog
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.leanback.app.BackgroundManager
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ApplicationProvider
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.ApiClientFactory
import nl.giejay.android.tv.immich.auth.ProfileFragment
import nl.giejay.android.tv.immich.security.ProfileStore
import nl.giejay.android.tv.immich.settings.PinGraceSettingsDialog
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.viewmodel.MediaSliderViewModel
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real lifecycle + production session guards, with synthetic Home and no external connections. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ImmichApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityGraceTest {
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var originalStore: ProfileStore
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("private_profiles", Context.MODE_PRIVATE)
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)

    @Before fun setup() {
        InstalledReceiverPermission.grant()
        prefs.edit().clear().commit()
        originalStore = PreferenceManager.profiles
        PreferenceManager.lock()
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        setStore(originalStore)
        PreferenceManager.lock()
    }

    private fun setStore(store: ProfileStore) {
        PreferenceManager.javaClass.getDeclaredField("profiles").apply { isAccessible = true }.set(PreferenceManager, store)
    }

    private fun seed(id: String = "a", minutes: Int? = null) {
        val rows = JSONArray(prefs.getString("profiles_v1", "[]"))
        val row = JSONObject().put("id", id).put("name", "Synthetic $id").put("sessionRevision", "revision-$id")
        if (minutes != null) row.put("pinGraceMinutes", minutes)
        rows.put(row)
        prefs.edit().putString("profiles_v1", rows.toString()).commit()
    }

    private fun host(activity: MainActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

    private fun settle(activity: MainActivity) {
        ShadowLooper.idleMainLooper()
        activity.supportFragmentManager.executePendingTransactions()
        host(activity).childFragmentManager.executePendingTransactions()
        ShadowLooper.idleMainLooper()
    }

    private fun launch(state: Bundle? = null): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java, launcher())
        controller = created
        created.create(state).start().resume().visible()
        val activity = created.get()
        settle(activity)
        val manager = host(activity).childFragmentManager
        val original = manager.fragmentFactory
        val homeName = (host(activity).navController.graph.findNode(R.id.homeFragment) as FragmentNavigator.Destination).className
        manager.fragmentFactory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
                if (className == homeName) SyntheticPhotoFragment() else original.instantiate(classLoader, className)
        }
        return activity
    }

    private fun unlock(activity: MainActivity, id: String = "a"): ApiClientConfig {
        val connection = ApiClientConfig("https://synthetic.invalid", "synthetic-key-$id", false, false)
        PreferenceManager.activateProfile(id, connection)
        activity.showUnlockedHome()
        settle(activity)
        ViewModelProvider(activity)[MediaSliderViewModel::class.java].configuration = Mockito.mock(MediaSliderConfiguration::class.java)
        BackgroundManager.getInstance(activity).apply { if (!isAttached) attach(activity.window); drawable = ColorDrawable(Color.RED) }
        return connection
    }

    private fun background(activity: MainActivity) {
        controller!!.pause().stop()
        assertFalse(PreferenceManager.isLoggedId())
        assertNull(PreferenceManager.activeProfileId)
        assertEquals("", PreferenceManager.connectionSnapshot().apiKey)
        assertEquals("", PreferenceManager.connectionSnapshot().hostName)
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.nav_host_fragment).visibility)
        assertNull(ViewModelProvider(activity)[MediaSliderViewModel::class.java].configuration)
        assertNull(BackgroundManager.getInstance(activity).drawable)
    }

    private fun reopen(activity: MainActivity) {
        controller!!.restart().start().resume().visible()
        settle(activity)
    }

    private fun assertLocked(activity: MainActivity) {
        settle(activity)
        assertFalse(PreferenceManager.isLoggedId())
        assertEquals(R.id.profileFragment, host(activity).navController.currentDestination?.id)
        assertTrue(host(activity).childFragmentManager.primaryNavigationFragment is ProfileFragment)
        assertFalse(host(activity).navController.popBackStack())
    }

    @Test fun defaultFiveMinutesResumesSameAccountWithFreshIdentityAndNoOldMedia() {
        seed()
        val activity = launch()
        val original = unlock(activity)
        assertEquals(5, PreferenceManager.activeGraceMinutes())
        background(activity)
        ShadowSystemClock.advanceBy(Duration.ofMillis(299_999))
        reopen(activity)
        assertTrue(PreferenceManager.isLoggedId())
        assertEquals("a", PreferenceManager.activeProfileId)
        assertEquals(original, PreferenceManager.connectionSnapshot())
        assertNotSame(original, PreferenceManager.connectionSnapshot())
        assertEquals(R.id.homeFragment, host(activity).navController.currentDestination?.id)
        assertTrue(host(activity).childFragmentManager.primaryNavigationFragment is SyntheticPhotoFragment)
        assertNull(ViewModelProvider(activity)[MediaSliderViewModel::class.java].configuration)
        assertNull(BackgroundManager.getInstance(activity).drawable)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.nav_host_fragment).visibility)
        // The old cleanup timer cannot later lock an active foreground session.
        ShadowLooper.idleMainLooper(5, TimeUnit.MINUTES)
        assertTrue(PreferenceManager.isLoggedId())
    }

    @Test fun exactDeadlineRequiresPinEvenBeforeTheCleanupCallbackRuns() {
        seed(minutes = 1)
        val activity = launch()
        unlock(activity)
        background(activity)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        reopen(activity)
        assertLocked(activity)
    }

    @Test fun screenWakeDropsExpiredCredentialsBeforeAnyForegroundResumeOrQueuedTimer() {
        seed(minutes = 1)
        val activity = launch()
        unlock(activity)
        background(activity)
        val lazyController = PreferenceManager.javaClass.getDeclaredField("pinGrace\$delegate")
            .apply { isAccessible = true }.get(PreferenceManager) as Lazy<*>
        val grace = lazyController.value!!
        val pending = grace.javaClass.getDeclaredField("pending").apply { isAccessible = true }
        assertNotNull(pending.get(grace))
        // The main looper is paused: elapsed expiry is reached without running its queued timer,
        // as after deep sleep. Deliver the system wake signal directly before any public grant read.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        val receiver = MainActivity::class.java.getDeclaredField("screenOnReceiver").apply { isAccessible = true }
            .get(activity) as BroadcastReceiver
        // Even direct invocation with an unrelated action must not operate on the grant.
        receiver.onReceive(activity, Intent("synthetic.untrusted.SCREEN_ON"))
        assertNotNull(pending.get(grace))
        receiver.onReceive(activity, Intent(Intent.ACTION_SCREEN_ON))
        assertNull(pending.get(grace))
        assertFalse(PreferenceManager.isLoggedId())
        reopen(activity)
        assertLocked(activity)
    }

    @Test fun immediateModeAndRepeatedBackgroundDoNotCreateOrExtendGrace() {
        seed(minutes = 0)
        val activity = launch()
        unlock(activity)
        background(activity)
        PreferenceManager.suspendForBackground()
        reopen(activity)
        assertLocked(activity)
        originalStore.setGraceMinutes("a", 5)
        unlock(activity)
        background(activity)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
        PreferenceManager.suspendForBackground()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        reopen(activity)
        assertLocked(activity)
    }

    @Test fun desktopIconReturnAcceptsPlainLauncherBeforeOrAfterOnStartWithoutRenewal() {
        seed()
        val activity = launch()
        val original = unlock(activity)
        background(activity)
        controller!!.newIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        assertFalse(PreferenceManager.isLoggedId())
        reopen(activity)
        val resumed = PreferenceManager.connectionSnapshot()
        assertNotSame(original, resumed)
        controller!!.newIntent(launcher())
        settle(activity)
        assertSame(resumed, PreferenceManager.connectionSnapshot())
        assertTrue(PreferenceManager.isLoggedId())
    }

    @Test fun unexpectedIntentRevokesGrantAndLaterLauncherCannotRestoreIt() {
        seed()
        val activity = launch()
        unlock(activity)
        background(activity)
        controller!!.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://synthetic.invalid/private")))
        controller!!.newIntent(launcher())
        reopen(activity)
        assertLocked(activity)
    }

    @Test fun manualLockOrAccountSwitchPreventsOldAccountReturn() {
        seed("a")
        seed("b", 0)
        val activity = launch()
        unlock(activity, "a")
        activity.lockAndShowProfiles()
        background(activity)
        reopen(activity)
        assertLocked(activity)
        unlock(activity, "a")
        background(activity)
        // Switching requires an independent successful PIN flow, represented by this synthetic activation.
        PreferenceManager.activateProfile("b", ApiClientConfig("https://synthetic.invalid", "synthetic-key-b", false, false))
        PreferenceManager.suspendForBackground()
        reopen(activity)
        assertLocked(activity)
        assertFalse(PreferenceManager.resumeFromBackground())
    }

    @Test fun deletionOrChangedThenRestoredPolicyInvalidatesExistingGrant() {
        seed()
        val activity = launch()
        unlock(activity)
        background(activity)
        originalStore.setGraceMinutes("a", 1)
        originalStore.setGraceMinutes("a", 5)
        reopen(activity)
        assertLocked(activity)
        unlock(activity)
        background(activity)
        PreferenceManager.removeProfile("a")
        reopen(activity)
        assertLocked(activity)
        assertTrue(originalStore.profiles().isEmpty())
    }

    @Test fun startingPinChangeClearsGrantWithoutReactivatingItsResult() {
        seed()
        val activity = launch()
        unlock(activity)
        background(activity)
        val store = Mockito.mock(ProfileStore::class.java)
        setStore(store)
        try {
            assertNull(PreferenceManager.changeProfilePin("a", "0123", "9876"))
        } finally { setStore(originalStore) }
        reopen(activity)
        assertLocked(activity)
    }

    @Test fun savedStateAndActivityRecreationCannotAuthorizeAResume() {
        seed()
        val activity = launch()
        unlock(activity)
        val state = Bundle()
        controller!!.saveInstanceState(state)
        background(activity)
        controller!!.destroy()
        controller = null
        val recreated = launch(state)
        assertLocked(recreated)
    }

    @Test fun processInitializationClearsGraceInsteadOfReadingAnUnlockToken() {
        seed()
        val activity = launch()
        unlock(activity)
        background(activity)
        PreferenceManager.init(context)
        reopen(activity)
        assertLocked(activity)
        assertEquals(setOf("profiles_v1"), prefs.all.keys)
        assertFalse(prefs.getString("profiles_v1", "")!!.contains("synthetic-key-a"))
    }

    @Test fun settingsDefaultToFiveMinutesApplyOnlyToActiveAccountAndRejectStaleDialog() {
        seed("a")
        seed("b")
        val activity = launch()
        unlock(activity)
        val dialog = PinGraceSettingsDialog.show(activity, "a")!!
        assertTrue(dialog.window!!.decorView.findViewWithTag<RadioButton>("pin_grace_choice_5").isChecked)
        dialog.window!!.decorView.findViewWithTag<RadioButton>("pin_grace_choice_1").performClick()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, originalStore.sessionPolicy("a")!!.graceMinutes)
        assertEquals(5, originalStore.sessionPolicy("b")!!.graceMinutes)
        val stale = PinGraceSettingsDialog.show(activity, "a")!!
        unlock(activity, "b")
        stale.window!!.decorView.findViewWithTag<RadioButton>("pin_grace_choice_15").performClick()
        stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, originalStore.sessionPolicy("a")!!.graceMinutes)
        assertEquals(5, originalStore.sessionPolicy("b")!!.graceMinutes)
        stale.dismiss()
    }

    @Test fun backgroundCannotFetchImagesAndOldResponseCannotCrossGraceResume() {
        seed()
        val activity = launch()
        val original = unlock(activity)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val request = Request.Builder().url("https://synthetic.invalid/api/assets").build()
        val oldClient = ApiClientFactory.forSession(original).newBuilder().addInterceptor { chain ->
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            // Synthetic response interceptor: no DNS, socket, NAS or external service.
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Synthetic").body("private result".toResponseBody()).build()
        }.build()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val oldResponse = executor.submit<Boolean> { oldClient.newCall(request).execute().use { true } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            background(activity)
            assertThrows(IOException::class.java) { ApiClientFactory.forImages().newCall(request).execute().close() }
            reopen(activity)
            assertNotSame(original, PreferenceManager.connectionSnapshot())
            release.countDown()
            val failure = assertThrows(ExecutionException::class.java) { oldResponse.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is IOException)
            assertThrows(IOException::class.java) { oldClient.newCall(request).execute().close() }
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
