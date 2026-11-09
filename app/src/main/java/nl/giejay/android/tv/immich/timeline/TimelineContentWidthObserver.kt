package nl.giejay.android.tv.immich.timeline

import android.view.View

/** Never fabricate a 1-pixel grid before layout; reflow when the host changes its actual width. */
internal fun View.observeTimelineContentWidth(onWidthChanged: (Int) -> Unit): View.OnLayoutChangeListener {
    var lastWidth = 0
    fun publishMeasuredWidth() {
        val contentWidth = width - paddingStart - paddingEnd
        if (contentWidth <= 0 || contentWidth == lastWidth) return
        lastWidth = contentWidth
        onWidthChanged(contentWidth)
    }
    val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishMeasuredWidth() }
    addOnLayoutChangeListener(listener)
    if (isLaidOut) publishMeasuredWidth()
    return listener
}
