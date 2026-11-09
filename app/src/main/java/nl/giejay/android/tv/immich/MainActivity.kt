package nl.giejay.android.tv.immich

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.leanback.app.BackgroundManager
import nl.giejay.mediaslider.viewmodel.MediaSliderViewModel
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.NavGraph
import androidx.navigation.fragment.NavHostFragment
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.MetaDataScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.security.LauncherEntryPolicy
import nl.giejay.android.tv.immich.shared.viewmodel.KeyEventsViewModel
import nl.giejay.mediaslider.adapter.AlignOption
import timber.log.Timber

/**
 * FragmentActivity that displays the various fragments
 */
class MainActivity : FragmentActivity() {
    private lateinit var keyEventsModel: KeyEventsViewModel
    private lateinit var navGraph: NavGraph
    private lateinit var navController: NavController
    private var foreground = false
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) PreferenceManager.expireBackgroundGrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never restore authenticated fragments/back stack from process or activity recreation.
        PreferenceManager.lock()
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        Timber.i("Booting main activity")

        setContentView(R.layout.activity_main)
        // Do not wake the TV just to clean up. When it wakes naturally, discard expired
        // credentials even if the app remains in the background and its Handler timer was paused.
        ContextCompat.registerReceiver(this, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        keyEventsModel = ViewModelProvider(this)[KeyEventsViewModel::class.java]

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        navGraph = navController.navInflater.inflate(R.navigation.nav_graph)

        if(!PreferenceManager.hasMetaDataForScreen(MetaDataScreen.VIEWER, AlignOption.RIGHT) || !PreferenceManager.hasMetaDataForScreen(MetaDataScreen.SCREENSAVER, AlignOption.RIGHT)){
            // first time using the customizer, do a migration
            val viewMetaDataFromOldPrefs = PreferenceManager.getViewMetaDataFromOldPrefs()
            val screenSaverMetaDataFromOldPrefs = PreferenceManager.getScreenSaverMetaDataFromOldPrefs()
            PreferenceManager.saveMetaData(AlignOption.RIGHT, MetaDataScreen.VIEWER, viewMetaDataFromOldPrefs)
            PreferenceManager.saveMetaData(AlignOption.RIGHT, MetaDataScreen.SCREENSAVER, screenSaverMetaDataFromOldPrefs)
        }

        loadStartingPage()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!LauncherEntryPolicy.permitsGrace(intent)) {
            PreferenceManager.lock()
            if (foreground) lockAndShowProfiles()
        } else if (foreground && !PreferenceManager.isLoggedId()) {
            // onNewIntent can arrive either side of onStart. A consumed grant is never renewed.
            showForegroundEntry()
        } else if (foreground && (PreferenceManager.activeGraceMinutes() ?: 0) == 0) {
            lockAndShowProfiles()
        }
    }

    override fun onStop() {
        // Hide pixels before any background work; only a private in-memory grant may remain.
        foreground = false
        findViewById<View>(R.id.nav_host_fragment).visibility = View.INVISIBLE
        PreferenceManager.suspendForBackground()
        ViewModelProvider(this)[MediaSliderViewModel::class.java].configuration = null
        BackgroundManager.getInstance(this).drawable = null
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (::navController.isInitialized) showForegroundEntry()
    }

    private fun showForegroundEntry() {
        val container = findViewById<View>(R.id.nav_host_fragment)
        container.visibility = View.INVISIBLE
        if (!PreferenceManager.isLoggedId()) {
            if (LauncherEntryPolicy.permitsGrace(intent) && PreferenceManager.resumeFromBackground()) {
                showUnlockedHome()
            } else {
                lockAndShowProfiles()
            }
        }
        // Never briefly reveal the old hidden graph or its pending photo/video results.
        (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
            .childFragmentManager.executePendingTransactions()
        container.visibility = View.VISIBLE
    }

    fun lockAndShowProfiles() {
        PreferenceManager.lock()
        loadStartingPage()
        // Detach old media screens before clearing their activity-scoped models.
        (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
            .childFragmentManager.executePendingTransactions()
        ViewModelProvider(this)[MediaSliderViewModel::class.java].configuration = null
        BackgroundManager.getInstance(this).drawable = null
    }

    fun showUnlockedHome() {
        if (!PreferenceManager.isLoggedId()) return
        navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        navGraph.setStartDestination(R.id.homeFragment)
        navController.graph = navGraph
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        keyEventsModel.postKeyEvent(event)
        return super.onKeyDown(keyCode, event)
    }

    private fun loadStartingPage() {
        navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        navGraph.setStartDestination(R.id.profileFragment)

        // Set the graph to trigger loading the start destination
        navController.graph = navGraph
    }

    override fun onDestroy() {
        // Conservatively require a PIN after Activity reconstruction as well as process restart.
        PreferenceManager.lock()
        runCatching { unregisterReceiver(screenOnReceiver) }
        super.onDestroy()
        Timber.i("Main activity got destroyed")
    }
}
