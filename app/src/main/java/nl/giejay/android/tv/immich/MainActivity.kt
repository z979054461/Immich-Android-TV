package nl.giejay.android.tv.immich

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
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
        super.onCreate(savedInstanceState)

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

        // Preserve navigation on process recreation. External intents never carry server or asset data.
        if (savedInstanceState == null) {
            loadStartingPage()
        }
    }

    // Reopening the launcher returns to the normal authenticated entry point.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadStartingPage()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        keyEventsModel.postKeyEvent(event)
        return super.onKeyDown(keyCode, event)
    }

    private fun loadStartingPage() {
        if (!PreferenceManager.isLoggedId()) {
            Timber.i("Start page is authentication")
            navGraph.setStartDestination(R.id.authFragment)
        } else {
            Timber.i("Start page is home")
            navGraph.setStartDestination(R.id.homeFragment)
        }

        // Set the graph to trigger loading the start destination
        navController.graph = navGraph
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("Main activity got destroyed")
    }
}
