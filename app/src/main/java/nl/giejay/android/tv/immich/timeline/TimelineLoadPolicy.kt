package nl.giejay.android.tv.immich.timeline

/** Paging is driven by the visible/focused row, never the number of dates in a dense month. */
internal object TimelineLoadPolicy {
    fun nearEnd(lastVisible: Int, focusedPosition: Int, itemCount: Int, rowsAhead: Int = 4): Boolean {
        if (itemCount <= 0) return false
        val threshold = (itemCount - 1 - rowsAhead.coerceAtLeast(0)).coerceAtLeast(0)
        fun near(position: Int) = position in threshold until itemCount
        return near(lastVisible) || near(focusedPosition)
    }
}
