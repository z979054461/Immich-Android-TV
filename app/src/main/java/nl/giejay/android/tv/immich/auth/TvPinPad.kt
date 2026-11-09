package nl.giejay.android.tv.immich.auth

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import nl.giejay.android.tv.immich.R

/** Remote-friendly, masked input. Digits are never put in saved view state or an IME. */
internal class TvPinPad(context: Context, private val automatic: Boolean) : LinearLayout(context) {
    private val digits = CharArray(if (automatic) 4 else 12)
    private var count = 0
    private var busy = false
    private val keys = mutableListOf<Button>()
    var onSubmit: ((String) -> Unit)? = null

    private val instruction = TextView(context).apply {
        textSize = 17f
        tag = "pin_instruction"
        isSaveEnabled = false
    }
    private val masked = TextView(context).apply {
        textSize = 28f
        gravity = Gravity.CENTER
        tag = "pin_masked"
        isSaveEnabled = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val progress = ProgressBar(context).apply { visibility = View.GONE }
    private val status = TextView(context).apply {
        textSize = 16f
        tag = "pin_status"
        isSaveEnabled = false
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        setPadding(dp(24), dp(8), dp(24), dp(8))
        addView(instruction)
        addView(masked, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(progress, LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
            addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val grid = GridLayout(context).apply { columnCount = 3; isSaveEnabled = false }
        val labels = (1..9).map { it.toString() } + listOf("⌫", "0", "✓")
        labels.forEachIndexed { index, label ->
            val key = Button(context).apply {
                id = View.generateViewId()
                text = label
                textSize = 25f
                isAllCaps = false
                isSaveEnabled = false
                isFocusable = true
                background = context.getDrawable(R.drawable.tv_menu_focus)
                setTextColor(context.getColor(R.color.screen_white))
                tag = when (index) { 9 -> "pin_delete"; 11 -> "pin_confirm"; else -> "pin_digit_$label" }
                contentDescription = when (index) {
                    9 -> context.getString(R.string.profiles_pin_delete)
                    11 -> context.getString(R.string.profiles_pin_confirm_action)
                    else -> label
                }
                layoutParams = GridLayout.LayoutParams(GridLayout.spec(index / 3), GridLayout.spec(index % 3, 1f)).apply {
                    width = 0
                    height = dp(56)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setOnClickListener {
                    when (index) {
                        9 -> deleteDigit()
                        11 -> submit()
                        else -> enterDigit(label.single())
                    }
                }
            }
            keys += key
            grid.addView(key)
        }
        keys.forEachIndexed { index, key ->
            key.nextFocusLeftId = keys[if (index % 3 == 0) index else index - 1].id
            key.nextFocusRightId = keys[if (index % 3 == 2) index else index + 1].id
            key.nextFocusUpId = keys[if (index < 3) index else index - 3].id
            if (index < 9) key.nextFocusDownId = keys[index + 3].id
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun setPrompt(text: String) {
        instruction.text = text
        status.text = ""
    }

    fun setBusy(value: Boolean, message: String = "") {
        busy = value
        status.text = message
        progress.visibility = if (value) View.VISIBLE else View.GONE
        render()
    }

    fun showError(message: String) {
        status.text = message
        focusFirstKey()
    }

    fun focusFirstKey() { keys.first().requestFocus() }

    fun connectCancelButton(cancel: Button) {
        for (index in 9..11) keys[index].nextFocusDownId = cancel.id
        cancel.nextFocusUpId = keys[10].id
    }

    fun clear() {
        digits.fill('\u0000')
        count = 0
        render()
    }

    private fun enterDigit(digit: Char) {
        if (busy || digit !in '0'..'9' || count == digits.size) return
        status.text = ""
        digits[count++] = digit
        render()
        if (automatic && count == digits.size) submit()
    }

    private fun deleteDigit() {
        if (busy || count == 0) return
        digits[--count] = '\u0000'
        status.text = ""
        render()
    }

    private fun submit() {
        if (busy || !canSubmit()) return
        val pin = String(digits, 0, count)
        clear()
        onSubmit?.invoke(pin)
    }

    private fun canSubmit() = count == 4 || (!automatic && count in 6..12)

    /** Numeric remotes/keyboards also work; D-pad Center/Enter remain normal focused-key clicks. */
    fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        val digit = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }
        if (digit == null && keyCode != KeyEvent.KEYCODE_DEL && keyCode != KeyEvent.KEYCODE_FORWARD_DEL && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when {
                digit != null -> enterDigit(digit)
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> submit()
                else -> deleteDigit()
            }
        }
        return true
    }

    private fun render() {
        masked.text = (0 until digits.size).joinToString(" ") { if (it < count) "●" else "○" }
        masked.contentDescription = context.getString(R.string.profiles_pin_digit_count, count)
        keys.forEachIndexed { index, key ->
            key.isEnabled = !busy && when (index) { 9 -> count > 0; 11 -> canSubmit(); else -> true }
        }
    }
}
