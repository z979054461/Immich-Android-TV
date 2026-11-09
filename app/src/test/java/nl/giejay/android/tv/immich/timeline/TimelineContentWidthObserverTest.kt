package nl.giejay.android.tv.immich.timeline

import android.app.Application
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TimelineContentWidthObserverTest {
    private fun view() = View(ApplicationProvider.getApplicationContext<Context>()).apply {
        setPaddingRelative(40, 0, 64, 0)
    }

    @Test fun `unmeasured view is not reduced to a one pixel timeline`() {
        val view = view()
        val widths = mutableListOf<Int>()
        view.observeTimelineContentWidth(widths::add)
        assertTrue(widths.isEmpty())
        view.layout(0, 0, 1920, 1080)
        assertEquals(listOf(1816), widths)
    }

    @Test fun `later host resize recomputes the usable width`() {
        val view = view()
        val widths = mutableListOf<Int>()
        view.observeTimelineContentWidth(widths::add)
        view.layout(0, 0, 1920, 1080)
        view.layout(0, 0, 1280, 720)
        view.layout(0, 0, 1280, 800)
        assertEquals(listOf(1816, 1176), widths)
    }

    @Test fun `already measured view reports immediately and ignores collapsed width`() {
        val view = view()
        view.layout(0, 0, 1280, 720)
        val widths = mutableListOf<Int>()
        view.observeTimelineContentWidth(widths::add)
        view.layout(0, 0, 0, 0)
        view.layout(0, 0, 1920, 1080)
        assertEquals(listOf(1176, 1816), widths)
    }

    @Test fun `destroyed view listener is removable`() {
        val view = view()
        val widths = mutableListOf<Int>()
        val listener = view.observeTimelineContentWidth(widths::add)
        view.removeOnLayoutChangeListener(listener)
        view.layout(0, 0, 1920, 1080)
        assertTrue(widths.isEmpty())
    }
}
