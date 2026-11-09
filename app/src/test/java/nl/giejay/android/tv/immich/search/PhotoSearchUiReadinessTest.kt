package nl.giejay.android.tv.immich.search

import org.junit.Assert.*
import org.junit.Test

class PhotoSearchUiReadinessTest {
    @Test fun `host bound after view creation receives ordered ready notifications once`() {
        val readiness = PhotoSearchHostReadiness()
        val view = Any()
        val host = Any()
        val calls = mutableListOf<String>()
        readiness.beginView(view)
        fun notify(currentHost: Any?) = readiness.notify(view, currentHost, { true },
            { calls += "view" }, { calls += "data" }, { calls += "title" })
        notify(null)
        assertTrue(calls.isEmpty())
        notify(host)
        notify(host)
        assertEquals(listOf("view", "data", "title"), calls)
    }

    @Test fun `synchronous callbacks cannot reenter any notification stage`() {
        val readiness = PhotoSearchHostReadiness()
        val view = Any()
        val host = Any()
        val calls = mutableListOf<String>()
        readiness.beginView(view)
        fun reenter() = readiness.notify(view, host, { true },
            { calls += "duplicate-view" }, { calls += "duplicate-data" }, { calls += "duplicate-title" })
        readiness.notify(view, host, { true },
            { calls += "view"; reenter() },
            { calls += "data"; reenter() },
            { calls += "title"; reenter() })
        assertEquals(listOf("view", "data", "title"), calls)
    }

    @Test fun `destroy during view callback prevents data title and later stale tasks`() {
        val readiness = PhotoSearchHostReadiness()
        val view = Any()
        val host = Any()
        val calls = mutableListOf<String>()
        readiness.beginView(view)
        readiness.notify(view, host, { true },
            { calls += "view"; readiness.destroyView(view) },
            { calls += "stale-data" }, { calls += "stale-title" })
        readiness.notify(view, host, { true }, { calls += "late-view" }, { calls += "late-data" }, {})
        assertEquals(listOf("view"), calls)
        assertFalse(readiness.isCurrentView(view))
    }

    @Test fun `destroy during data callback prevents a later title callback`() {
        val readiness = PhotoSearchHostReadiness()
        val view = Any()
        val calls = mutableListOf<String>()
        readiness.beginView(view)
        readiness.notify(view, Any(), { true },
            { calls += "view" }, { calls += "data"; readiness.destroyView(view) }, { calls += "stale-title" })
        assertEquals(listOf("view", "data"), calls)
    }

    @Test fun `replacement host during a synchronous callback is deferred and old host stops`() {
        val readiness = PhotoSearchHostReadiness()
        val view = Any()
        val first = Any()
        val second = Any()
        var current = first
        val calls = mutableListOf<String>()
        readiness.beginView(view)
        readiness.notify(view, first, { current === first }, {
            calls += "first-view"
            current = second
            readiness.notify(view, second, { current === second },
                { calls += "reentrant-view" }, { calls += "reentrant-data" }, {})
        }, { calls += "stale-data" }, { calls += "stale-title" })
        readiness.notify(view, second, { current === second },
            { calls += "second-view" }, { calls += "second-data" }, { calls += "second-title" })
        assertEquals(listOf("first-view", "second-view", "second-data", "second-title"), calls)
    }

    @Test fun `recreated view can notify same host but old scheduled callback stays inert`() {
        val readiness = PhotoSearchHostReadiness()
        val old = Any()
        val fresh = Any()
        val host = Any()
        val calls = mutableListOf<String>()
        readiness.beginView(old)
        readiness.notify(old, host, { true }, { calls += "old-view" }, { calls += "old-data" }, {})
        readiness.destroyView(old)
        readiness.beginView(fresh)
        readiness.notify(old, host, { true }, { calls += "stale-view" }, { calls += "stale-data" }, {})
        readiness.destroyView(old) // A stale destroy must not invalidate the new screen.
        readiness.notify(fresh, host, { true }, { calls += "new-view" }, { calls += "new-data" }, {})
        assertEquals(listOf("old-view", "old-data", "new-view", "new-data"), calls)
    }

    @Test fun `callback failure permits a later guarded retry`() {
        val readiness = PhotoSearchHostReadiness()
        val view = Any()
        val host = Any()
        val calls = mutableListOf<String>()
        readiness.beginView(view)
        assertTrue(runCatching {
            readiness.notify(view, host, { true }, { error("synthetic failure") }, {}, {})
        }.isFailure)
        readiness.notify(view, host, { true }, { calls += "view" }, { calls += "data" }, {})
        assertEquals(listOf("view", "data"), calls)
    }

    @Test fun `first results position first item only after commit and empty pages do not consume it`() {
        val selection = PhotoSearchResultSelection()
        val view = Any()
        selection.beginView(view)
        selection.beginQuery(view, 1)
        assertNull(selection.positionAfterCommit(view, 1, emptyList(), null))
        assertNull(selection.positionAfterCommit(view, 1, emptyList(), null))
        assertEquals(0, selection.positionAfterCommit(view, 1, listOf("A", "B"), null))
        assertNull(selection.positionAfterCommit(view, 1, listOf("A", "B", "C"), "B"))
    }

    @Test fun `returning screen positions remembered asset once without assuming first item`() {
        val selection = PhotoSearchResultSelection()
        val view = Any()
        selection.beginView(view)
        selection.beginQuery(view, 8)
        assertEquals(2, selection.positionAfterCommit(view, 8, listOf("A", "B", "C"), "C"))
        assertNull(selection.positionAfterCommit(view, 8, listOf("A", "B", "C"), "A"))
    }

    @Test fun `changed query rejects old focus callbacks and initializes fresh results`() {
        val selection = PhotoSearchResultSelection()
        val view = Any()
        selection.beginView(view)
        selection.beginQuery(view, 1)
        selection.beginQuery(view, 2)
        assertNull(selection.positionAfterCommit(view, 1, listOf("old-A", "old-B"), "old-B"))
        assertEquals(0, selection.positionAfterCommit(view, 2, listOf("new-A", "new-B"), null))
        // A late callback must not reset the currently positioned query.
        assertNull(selection.positionAfterCommit(view, 1, listOf("old-A", "old-B"), "old-B"))
        assertNull(selection.positionAfterCommit(view, 2, listOf("new-A", "new-B", "new-C"), null))
    }

    @Test fun `destroyed and replaced views cannot consume a newer view focus restore`() {
        val selection = PhotoSearchResultSelection()
        val old = Any()
        val fresh = Any()
        selection.beginView(old)
        selection.beginQuery(old, 1)
        selection.destroyView(old)
        assertNull(selection.positionAfterCommit(old, 1, listOf("A", "B"), "B"))
        selection.beginView(fresh)
        selection.beginQuery(fresh, 1)
        selection.destroyView(old)
        assertNull(selection.positionAfterCommit(old, 1, listOf("A", "B"), "B"))
        assertEquals(1, selection.positionAfterCommit(fresh, 1, listOf("A", "B"), "B"))
    }

    @Test fun `continue remains reachable with empty pages until cursor is exhausted`() {
        assertTrue(PhotoSearchResultSelection.showContinue(started = true, canLoadMore = true))
        assertFalse(PhotoSearchResultSelection.showContinue(started = true, canLoadMore = false))
        assertFalse(PhotoSearchResultSelection.showContinue(started = false, canLoadMore = true))
    }
}
