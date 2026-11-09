package nl.giejay.android.tv.immich.search

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TvDateSelectorTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun key(picker: TvDateSelector, tag: String) = picker.findViewWithTag<Button>(tag)

    @Test fun `remote has explicit date columns and a route to apply clear and cancel`() {
        val picker = TvDateSelector(context, LocalDate.of(2026, 11, 8))
        assertEquals(key(picker, "date_plus_1").id, key(picker, "date_plus_0").nextFocusRightId)
        assertEquals(key(picker, "date_minus_0").id, key(picker, "date_plus_0").nextFocusDownId)
        assertEquals(key(picker, "date_plus_2").id, key(picker, "date_minus_2").nextFocusUpId)
        assertEquals(picker.applyButton.id, key(picker, "date_minus_0").nextFocusDownId)
        assertEquals(picker.clearButton.id, key(picker, "date_minus_1").nextFocusDownId)
        assertEquals(picker.cancelButton.id, key(picker, "date_minus_2").nextFocusDownId)
        fun inspect(view: View) {
            assertFalse(view is EditText)
            if (view is TextView || view is ViewGroup) assertFalse(view.isSaveEnabled)
            if (view is ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
        }
        inspect(picker)
    }

    @Test fun `date buttons handle month boundaries and leap years without invalid dates`() {
        val picker = TvDateSelector(context, LocalDate.of(2024, 2, 29))
        key(picker, "date_plus_0").performClick()
        assertEquals(LocalDate.of(2025, 2, 28), picker.date)
        key(picker, "date_plus_2").performClick()
        assertEquals(LocalDate.of(2025, 3, 1), picker.date)
        key(picker, "date_minus_1").performClick()
        assertEquals(LocalDate.of(2025, 2, 1), picker.date)
        assertEquals("02", picker.findViewWithTag<TextView>("date_value_1").text.toString())
    }

    @Test fun `D pad center activates only the focused date action and never commits implicitly`() {
        val picker = TvDateSelector(context, LocalDate.of(2026, 11, 8))
        var applied = 0
        var cancelled = 0
        picker.applyButton.setOnClickListener { applied++ }
        picker.cancelButton.setOnClickListener { cancelled++ }
        val plus = key(picker, "date_plus_2")
        plus.requestFocus()
        plus.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        plus.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(LocalDate.of(2026, 11, 9), picker.date)
        assertEquals(0, applied)
        picker.cancelButton.performClick()
        assertEquals(1, cancelled)
        assertEquals(0, applied)
        picker.applyButton.performClick()
        assertEquals(1, applied)
    }

    @Test fun `repeated adjustments and no date choice remain independent from apply`() {
        val picker = TvDateSelector(context, LocalDate.of(2026, 11, 8))
        repeat(3) { key(picker, "date_plus_1").performClick() }
        assertEquals(LocalDate.of(2027, 2, 8), picker.date)
        var cleared = false
        picker.clearButton.setOnClickListener { cleared = true }
        picker.clearButton.performClick()
        assertTrue(cleared)
        assertEquals(LocalDate.of(2027, 2, 8), picker.date)
    }
}
