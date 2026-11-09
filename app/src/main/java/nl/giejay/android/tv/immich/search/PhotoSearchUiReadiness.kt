package nl.giejay.android.tv.immich.search

/** One ordered notification pair per actual host and view. Main-thread only. */
internal class PhotoSearchHostReadiness {
    private var activeView: Any? = null
    private var notifiedHost: Any? = null
    private var notifying = false
    private var revision = 0L

    fun beginView(view: Any) {
        revision++
        activeView = view
        notifiedHost = null
    }

    fun destroyView(view: Any) {
        if (activeView !== view) return
        revision++
        activeView = null
        notifiedHost = null
    }

    fun isCurrentView(view: Any): Boolean = activeView === view

    fun notify(
        view: Any,
        host: Any?,
        isCurrentHost: () -> Boolean,
        viewCreated: () -> Unit,
        dataReady: () -> Unit,
        hideTitle: () -> Unit
    ) {
        if (activeView !== view || host == null || notifying || !isCurrentHost() || notifiedHost === host) return
        val requestRevision = revision
        fun current() = activeView === view && revision == requestRevision && isCurrentHost()
        // Reserve before invoking external code: Leanback may synchronously call us again.
        notifiedHost = host
        notifying = true
        try {
            viewCreated()
            if (!current()) return
            dataReady()
            if (!current()) return
            hideTitle()
        } catch (failure: Throwable) {
            if (activeView === view && revision == requestRevision && notifiedHost === host) notifiedHost = null
            throw failure
        } finally {
            if (!current() && activeView === view && revision == requestRevision && notifiedHost === host) notifiedHost = null
            notifying = false
        }
    }
}

/** Deferred grid positioning never owns focus. Only one current view/query may consume it. */
internal class PhotoSearchResultSelection {
    private var activeView: Any? = null
    private var queryGeneration: Long? = null
    private var positioned = false

    fun beginView(view: Any) {
        activeView = view
        queryGeneration = null
        positioned = false
    }

    fun beginQuery(view: Any, generation: Long) {
        if (activeView !== view || queryGeneration == generation) return
        queryGeneration = generation
        positioned = false
    }

    fun destroyView(view: Any) {
        if (activeView !== view) return
        activeView = null
        queryGeneration = null
        positioned = false
    }

    fun positionAfterCommit(view: Any, generation: Long, ids: List<String>, rememberedId: String?): Int? {
        if (activeView !== view || generation != queryGeneration || positioned || ids.isEmpty()) return null
        positioned = true
        return ids.indexOf(rememberedId).takeIf { it >= 0 } ?: 0
    }

    companion object {
        /** An empty or duplicate-only page with a next cursor must keep Continue reachable. */
        fun showContinue(started: Boolean, canLoadMore: Boolean): Boolean = started && canLoadMore
    }
}
