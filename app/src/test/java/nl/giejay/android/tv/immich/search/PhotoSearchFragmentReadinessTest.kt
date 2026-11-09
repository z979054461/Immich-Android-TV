package nl.giejay.android.tv.immich.search

import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.BrowseSupportFragment
import kotlinx.coroutines.CompletableDeferred
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDate

/** Real fragment/posted callback integration. No search is issued unless a synthetic source is
 * installed; empty result pages do not bind Glide thumbnails or access any external server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ImmichApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PhotoSearchFragmentReadinessTest {
    private var controller: ActivityController<FragmentActivity>? = null

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        PreferenceManager.lock()
        ShadowLooper.idleMainLooper()
    }

    private fun launch(): Pair<FragmentActivity, PhotoSearchFragment> {
        PreferenceManager.javaClass.getDeclaredField("activeConnection").apply { isAccessible = true }
            .set(PreferenceManager, ApiClientConfig("https://synthetic.invalid", "synthetic-test-key", false, false))
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java)
        controller = activityController
        activityController.get().setTheme(R.style.AppTheme)
        activityController.create().start().resume().visible()
        val activity = activityController.get()
        val container = FrameLayout(activity).apply { id = View.generateViewId() }
        activity.setContentView(container)
        val fragment = PhotoSearchFragment()
        activity.supportFragmentManager.beginTransaction().add(container.id, fragment).commitNow()
        ShadowLooper.idleMainLooper()
        return activity to fragment
    }

    /** Leanback's package-private setter takes a concrete private host, not the public interface.
     * The typed Mockito double lets us exercise the exact get-adapter-then-bind-host ordering. */
    private fun bindHost(
        fragment: PhotoSearchFragment,
        onCall: (String) -> Unit
    ) {
        val adapter = fragment.getMainFragmentAdapter() // Schedules the after-bind readiness attempt.
        val field = BrowseSupportFragment.MainFragmentAdapter::class.java.getDeclaredField("mFragmentHost").apply { isAccessible = true }
        val host = Mockito.mock(field.type, org.mockito.stubbing.Answer<Any?> { invocation ->
            when (invocation.method.name) {
                "notifyViewCreated" -> onCall("view")
                "notifyDataReady" -> onCall("data")
                "showTitleView" -> onCall("title")
            }
            null
        })
        field.set(adapter, host)
    }

    @Test fun `host bound after resume is notified from the adapter getter without new API data`() {
        val (_, fragment) = launch()
        val calls = mutableListOf<String>()
        bindHost(fragment) { calls += it }
        assertTrue(calls.isEmpty())
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("view", "data", "title"), calls)
        fragment.getMainFragmentAdapter()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("view", "data", "title"), calls)
    }

    @Test fun `synchronous host callback and another posted attempt still notify once`() {
        val (_, fragment) = launch()
        val calls = mutableListOf<String>()
        bindHost(fragment) {
            calls += it
            fragment.getMainFragmentAdapter()
        }
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("view", "data", "title"), calls)
    }

    @Test fun `destroy before posted readiness leaves the detached host untouched`() {
        val (activity, fragment) = launch()
        val calls = mutableListOf<String>()
        bindHost(fragment) { calls += it }
        activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        ShadowLooper.idleMainLooper()
        assertTrue(calls.isEmpty())
        assertNull(fragment.view)
    }

    @Test fun `destroy from notifyViewCreated never calls old host data or title`() {
        val (activity, fragment) = launch()
        val calls = mutableListOf<String>()
        bindHost(fragment) {
            calls += it
            if (it == "view") activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        }
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("view"), calls)
        assertNull(fragment.view)
    }

    @Test fun `empty cursor page keeps Continue focusable while busy and exhausted page returns to Search`() {
        val (_, fragment) = launch()
        val first = CompletableDeferred<PhotoSearchPage>()
        val second = CompletableDeferred<PhotoSearchPage>()
        val pages = ArrayDeque(listOf(first, second))
        val model = PhotoSearchFragment::class.java.getDeclaredField("model").apply { isAccessible = true }
            .get(fragment) as PhotoSearchViewModel
        PhotoSearchViewModel::class.java.getDeclaredField("source").apply { isAccessible = true }
            .set(model, object : PhotoSearchSource {
                override suspend fun search(query: PhotoSearchQuery, cursor: String?): PhotoSearchPage = pages.removeFirst().await()
                override suspend fun people(page: Int): PhotoPeoplePage = error("People were not requested")
            })
        model.setQuery(PhotoSearchQuery(start = LocalDate.of(2026, 1, 1)))
        ShadowLooper.idleMainLooper()
        val search = fragment.requireView().findViewWithTag<Button>("search_run")
        val more = fragment.requireView().findViewWithTag<Button>("search_more")
        search.requestFocus()
        search.performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(model.state.value.loading)
        assertTrue(search.isEnabled)
        first.complete(PhotoSearchPage(emptyList(), "opaque-next"))
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, more.visibility)
        assertTrue(more.isEnabled)
        assertEquals(more.id, search.nextFocusDownId)
        more.requestFocus()
        more.performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(model.state.value.loading)
        assertTrue(more.isEnabled)
        assertTrue(more.hasFocus())
        second.complete(PhotoSearchPage(emptyList(), null))
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, more.visibility)
        assertTrue(search.hasFocus())
    }
}
