package nl.giejay.android.tv.immich.timeline

/** Decisions shared by the real Fragment restore paths and synthetic regression tests. */
internal object TimelineRestorePolicy {
    fun hasFocusableContent(hasMemoryRow: Boolean, hasMosaicAsset: Boolean): Boolean =
        hasMemoryRow || hasMosaicAsset

    enum class MemoryAction { Focus, Wait, Forget }

    fun memoryAction(hasAdapterRow: Boolean, hasMemories: Boolean, memoriesReady: Boolean): MemoryAction = when {
        hasAdapterRow -> MemoryAction.Focus
        hasMemories || !memoriesReady -> MemoryAction.Wait
        else -> MemoryAction.Forget
    }

    fun shouldForgetMissingAsset(requestSucceeded: Boolean, assetFound: Boolean): Boolean =
        requestSucceeded && !assetFound
}

/** Notify each actual host once per view, including a host bound after onViewCreated. */
internal class TimelineHostReadiness {
    private var notifiedHost: Any? = null

    fun notify(host: Any?, viewCreated: () -> Unit, dataReady: () -> Unit) {
        if (host == null || host === notifiedHost) return
        // A host callback can synchronously trigger another readiness attempt.
        notifiedHost = host
        try {
            viewCreated()
            dataReady()
        } catch (failure: Throwable) {
            if (notifiedHost === host) notifiedHost = null
            throw failure
        }
    }

    fun reset() {
        notifiedHost = null
    }
}
