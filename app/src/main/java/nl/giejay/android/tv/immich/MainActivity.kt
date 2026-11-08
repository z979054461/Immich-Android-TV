package nl.giejay.android.tv.immich

import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never restore authenticated fragments/back stack from process or activity recreation.
        PreferenceManager.lock()
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        Timber.i("Booting main activity")

        setContentView(R.layout.activity_main)

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
        lockAndShowProfiles()
    }

    override fun onStop() {
        // Stop network and obscure pixels immediately, before any later resume callbacks.
        PreferenceManager.lock()
        findViewById<View>(R.id.nav_host_fragment).visibility = View.INVISIBLE
        ViewModelProvider(this)[MediaSliderViewModel::class.java].configuration = null
        BackgroundManager.getInstance(this).drawable = null
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (::navController.isInitialized && !PreferenceManager.isLoggedId()) lockAndShowProfiles()
        findViewById<View>(R.id.nav_host_fragment).visibility = View.VISIBLE
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
        super.onDestroy()
        Timber.i("Main activity got destroyed")
    }
}
