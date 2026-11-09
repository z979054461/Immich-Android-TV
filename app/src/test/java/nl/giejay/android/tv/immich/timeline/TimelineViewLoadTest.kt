package nl.giejay.android.tv.immich.timeline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewLoadTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `successful month completion clears the current view gate once`() = runTest {
        val view = Any()
        var completions = 0
        withTimelineViewLoad(view, { view }, load = {}, finished = { completions++ })
        assertEquals(1, completions)
    }

    @Test fun `cancelled in-flight month still clears its current view gate`() = runTest {
        val view = Any()
        val started = CompletableDeferred<Unit>()
        var loading = true
        val job = launch {
            withTimelineViewLoad(view, { view }, load = {
                started.complete(Unit)
                awaitCancellation()
            }, finished = { loading = false })
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(loading)
    }

    @Test fun `late cancellation from a destroyed view cannot clear the replacement gate`() = runTest {
        val oldView = Any()
        var currentView = oldView
        val started = CompletableDeferred<Unit>()
        var replacementLoading = true
        val job = launch {
            withTimelineViewLoad(oldView, { currentView }, load = {
                started.complete(Unit)
                awaitCancellation()
            }, finished = { replacementLoading = false })
        }
        started.await()
        currentView = Any()
        job.cancelAndJoin()
        assertTrue(replacementLoading)
    }

    @Test fun `cleared view does not receive a late load completion`() = runTest {
        val oldView = Any()
        var completions = 0
        withTimelineViewLoad(oldView, { null }, load = {}, finished = { completions++ })
        assertEquals(0, completions)
    }
}
