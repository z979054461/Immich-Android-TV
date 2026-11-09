package nl.giejay.android.tv.immich.timeline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Clear a load gate even after cancellation, but never alter a replacement view's gate. */
internal suspend fun <T : Any> withTimelineViewLoad(
    ownerView: T,
    currentView: () -> T?,
    load: suspend () -> Unit,
    finished: () -> Unit
) {
    try {
        load()
    } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            if (currentView() === ownerView) finished()
        }
    }
}
