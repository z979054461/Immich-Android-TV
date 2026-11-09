package nl.giejay.android.tv.immich.search

import androidx.recyclerview.widget.RecyclerView
import nl.giejay.android.tv.immich.api.model.Asset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class PhotoSearchGridAdapterTest {
    private fun adapter() = PhotoSearchGridAdapter({ false }, {}, {}, { _, _ -> false })
    private fun asset(id: String) = Asset(id, "IMAGE", null, null, null, null, null, null, null, null, null)

    @Test fun `commit callback runs after list publication even when unchanged or empty`() {
        val adapter = adapter()
        val items = listOf(asset("A"))
        val seen = mutableListOf<Int>()
        adapter.submit(emptyList(), 1) { seen += adapter.itemCount }
        adapter.submit(items, 1) { seen += adapter.itemCount }
        adapter.submit(items, 1) { seen += adapter.itemCount }
        assertEquals(listOf(0, 1, 1), seen)
    }

    @Test fun `new query invalidates posted focus callback even if IDs are unchanged`() {
        val adapter = adapter()
        val items = listOf(asset("A"), asset("B"))
        var oldCommit = -1L
        var newCommit = -1L
        adapter.submit(items, 1) { oldCommit = it }
        adapter.submit(items, 2) { newCommit = it }
        assertFalse(adapter.isCurrentSubmission(oldCommit))
        assertTrue(adapter.isCurrentSubmission(newCommit))
        adapter.invalidate()
        assertFalse(adapter.isCurrentSubmission(newCommit))
        assertEquals(0, adapter.itemCount)
    }

    @Test fun `reentrant observer newer submission suppresses obsolete commit callback`() {
        val adapter = adapter()
        val calls = mutableListOf<String>()
        var replacing = false
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                if (!replacing) {
                    replacing = true
                    adapter.submit(listOf(asset("new")), 2) { calls += "new" }
                }
            }
        })
        adapter.submit(listOf(asset("old")), 1) { calls += "old" }
        assertEquals(listOf("new"), calls)
        assertEquals(1, adapter.itemCount)
    }

    @Test fun `append preserves existing rows but a new query forces rebinding`() {
        val adapter = adapter()
        val events = mutableListOf<String>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { events += "replace" }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { events += "insert:$positionStart:$itemCount" }
        })
        val first = listOf(asset("A"))
        adapter.submit(first, 1)
        adapter.submit(first + asset("B"), 1)
        adapter.submit(first + asset("B"), 2)
        assertEquals(listOf("replace", "insert:1:1", "replace"), events)
    }
}
