package nl.giejay.android.tv.immich.auth

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TvPinPadTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun key(pad: TvPinPad, tag: String) = pad.findViewWithTag<Button>(tag)
    private fun digit(pad: TvPinPad, value: Char) { key(pad, "pin_digit_$value").performClick() }
    private fun input(pad: TvPinPad, pin: String) { pin.forEach { digit(pad, it) } }
    private fun mask(pad: TvPinPad) = pad.findViewWithTag<TextView>("pin_masked").text.toString()

    @Test fun `three column layout has deterministic remote focus and no editable PIN field`() {
        val pad = TvPinPad(context, automatic = true)
        val grid = (0 until pad.childCount).map { pad.getChildAt(it) }.filterIsInstance<GridLayout>().single()
        assertEquals(3, grid.columnCount)
        assertEquals(12, grid.childCount)
        assertEquals(key(pad, "pin_digit_2").id, key(pad, "pin_digit_1").nextFocusRightId)
        assertEquals(key(pad, "pin_digit_4").id, key(pad, "pin_digit_1").nextFocusDownId)
        assertEquals(key(pad, "pin_digit_8").id, key(pad, "pin_digit_0").nextFocusUpId)
        val cancel = Button(context).apply { id = View.generateViewId() }
        pad.connectCancelButton(cancel)
        assertEquals(cancel.id, key(pad, "pin_digit_0").nextFocusDownId)
        assertEquals(key(pad, "pin_digit_0").id, cancel.nextFocusUpId)
        fun noSavedInput(view: View) {
            assertFalse(view is android.widget.EditText)
            if (view is Button || view is TextView || view === pad) assertFalse(view.isSaveEnabled)
            if (view is ViewGroup) for (i in 0 until view.childCount) noSavedInput(view.getChildAt(i))
        }
        noSavedInput(pad)
    }

    @Test fun `delete replaces only last digit and fourth digit automatically submits once`() {
        val pad = TvPinPad(context, automatic = true)
        val submitted = mutableListOf<String>()
        pad.onSubmit = { submitted += it; pad.setBusy(true, "Verifying") }
        input(pad, "019")
        key(pad, "pin_delete").performClick()
        assertEquals(2, mask(pad).count { it == '●' })
        input(pad, "23")
        assertEquals(listOf("0123"), submitted)
        assertFalse(mask(pad).contains('●'))
        input(pad, "9999")
        key(pad, "pin_confirm").performClick()
        assertEquals(listOf("0123"), submitted)
        assertFalse(key(pad, "pin_delete").isEnabled)
        assertFalse(key(pad, "pin_digit_1").isEnabled)
        assertEquals("Verifying", pad.findViewWithTag<TextView>("pin_status").text.toString())
    }

    @Test fun `legacy keypad never automatically verifies a four digit prefix`() {
        val pad = TvPinPad(context, automatic = false)
        val submitted = mutableListOf<String>()
        pad.onSubmit = { submitted += it }
        input(pad, "0123")
        assertTrue(submitted.isEmpty())
        input(pad, "45678901")
        assertTrue(submitted.isEmpty())
        assertEquals(12, mask(pad).count { it == '●' })
        key(pad, "pin_confirm").performClick()
        assertEquals(listOf("012345678901"), submitted)
        assertFalse(mask(pad).contains('●'))
    }

    @Test fun `hardware digits delete numpad enter and held keys are handled without IME`() {
        val pad = TvPinPad(context, automatic = false)
        val submitted = mutableListOf<String>()
        pad.onSubmit = { submitted += it }
        fun down(code: Int) = pad.handleKey(code, KeyEvent(KeyEvent.ACTION_DOWN, code))
        assertTrue(down(KeyEvent.KEYCODE_0))
        assertTrue(pad.handleKey(KeyEvent.KEYCODE_0, KeyEvent(0L, 1L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 2)))
        down(KeyEvent.KEYCODE_9)
        down(KeyEvent.KEYCODE_DEL)
        down(KeyEvent.KEYCODE_NUMPAD_1)
        down(KeyEvent.KEYCODE_NUMPAD_2)
        down(KeyEvent.KEYCODE_NUMPAD_3)
        assertEquals(4, mask(pad).count { it == '●' })
        assertTrue(submitted.isEmpty())
        down(KeyEvent.KEYCODE_NUMPAD_ENTER)
        assertEquals(listOf("0123"), submitted)
        assertFalse(pad.handleKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertFalse(pad.handleKey(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        pad.setBusy(true, "Verifying")
        down(KeyEvent.KEYCODE_7)
        assertFalse(mask(pad).contains('●'))
    }

    @Test fun `clearing or failing resets input rather than restoring secret digits`() {
        val pad = TvPinPad(context, automatic = true)
        input(pad, "012")
        pad.clear()
        assertFalse(mask(pad).contains('●'))
        pad.setBusy(true, "Verifying")
        pad.setBusy(false)
        pad.showError("Try again")
        val submitted = mutableListOf<String>()
        pad.onSubmit = { submitted += it }
        input(pad, "9876")
        assertEquals(listOf("9876"), submitted)
        assertFalse(mask(pad).contains('●'))
    }
}
