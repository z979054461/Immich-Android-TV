package nl.giejay.android.tv.immich.search

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import nl.giejay.android.tv.immich.R
import java.time.LocalDate

/** No keyboard or touch-only calendar. Every date adjustment is an ordinary D-pad button. */
internal class TvDateSelector(context: Context, initial: LocalDate) : LinearLayout(context) {
    var date: LocalDate = initial
        private set
    private val values = mutableListOf<TextView>()
    private val plus = mutableListOf<Button>()
    private val minus = mutableListOf<Button>()
    val applyButton = searchButton(context, context.getString(R.string.search_use_date), "date_apply")
    val clearButton = searchButton(context, context.getString(R.string.search_clear_date), "date_clear")
    val cancelButton = searchButton(context, context.getString(android.R.string.cancel), "date_cancel")

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        setPadding(dp(20), dp(12), dp(20), dp(12))
        addView(TextView(context).apply {
            setText(R.string.search_date_remote_hint)
            isSaveEnabled = false
        })
        val fields = LinearLayout(context).apply { orientation = HORIZONTAL; isSaveEnabled = false }
        val labels = listOf(R.string.search_year, R.string.search_month, R.string.search_day)
        labels.forEachIndexed { index, label ->
            val column = LinearLayout(context).apply { orientation = VERTICAL; isSaveEnabled = false }
            val add = searchButton(context, context.getString(label) + " +", "date_plus_$index")
            val remove = searchButton(context, context.getString(label) + " −", "date_minus_$index")
            val value = TextView(context).apply {
                textSize = 24f; gravity = Gravity.CENTER; isSaveEnabled = false; tag = "date_value_$index"
            }
            add.setOnClickListener { adjust(index, 1) }
            remove.setOnClickListener { adjust(index, -1) }
            plus += add; minus += remove; values += value
            column.addView(add, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
            column.addView(value, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
            column.addView(remove, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
            fields.addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(fields)
        val footer = LinearLayout(context).apply { isSaveEnabled = false }
        listOf(applyButton, clearButton, cancelButton).forEach { footer.addView(it, LayoutParams(0, dp(52), 1f)) }
        addView(footer)
        for (index in 0..2) {
            plus[index].nextFocusDownId = minus[index].id
            minus[index].nextFocusUpId = plus[index].id
            minus[index].nextFocusDownId = listOf(applyButton, clearButton, cancelButton)[index].id
            listOf(applyButton, clearButton, cancelButton)[index].nextFocusUpId = minus[index].id
            if (index > 0) { plus[index].nextFocusLeftId = plus[index - 1].id; minus[index].nextFocusLeftId = minus[index - 1].id }
            if (index < 2) { plus[index].nextFocusRightId = plus[index + 1].id; minus[index].nextFocusRightId = minus[index + 1].id }
        }
        render()
    }

    private fun adjust(field: Int, delta: Long) {
        date = when (field) {
            0 -> date.plusYears(delta)
            1 -> date.plusMonths(delta)
            else -> date.plusDays(delta)
        }.coerceIn(LocalDate.of(1, 1, 1), LocalDate.of(9998, 12, 31))
        render()
    }
    private fun render() {
        values[0].text = date.year.toString()
        values[1].text = date.monthValue.toString().padStart(2, '0')
        values[2].text = date.dayOfMonth.toString().padStart(2, '0')
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal fun searchButton(context: Context, label: String, viewTag: String) = Button(context).apply {
    id = View.generateViewId()
    text = label
    tag = viewTag
    textSize = 15f
    isAllCaps = false
    isFocusable = true
    isSaveEnabled = false
    background = context.getDrawable(R.drawable.tv_menu_focus)
    setTextColor(context.getColor(R.color.screen_white))
}
