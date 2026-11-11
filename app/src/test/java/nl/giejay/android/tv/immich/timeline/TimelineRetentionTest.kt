package nl.giejay.android.tv.immich.timeline

import org.junit.Assert.*
import org.junit.Test

class TimelineRetentionTest {
    @Test fun `long browsing retains a bounded month window`() {
        val keys = (0 until 120).map { "month-$it" }
        var loaded = emptyMap<String, List<Int>>()
        for ((index, key) in keys.withIndex()) {
            loaded = TimelineRetention.retain(loaded + (key to listOf(index)), keys, key, keys.getOrNull(index - 1))
            assertTrue(loaded.size <= TimelineRetention.MAX_MONTHS)
            assertTrue(key in loaded)
        }
        assertEquals(keys.takeLast(4).toSet(), loaded.keys)
    }

    @Test fun `scrubber jump preserves focus and evicts distant months`() {
        val keys = (0..20).map { "$it" }
        val loaded = listOf("0", "1", "2", "3", "20").associateWith { listOf(1) }
        val result = TimelineRetention.retain(loaded, keys, "20", "0")
        assertEquals(setOf("0", "2", "3", "20"), result.keys)
    }

    @Test fun `asset budget removes complete months without truncating photos`() {
        val loaded = listOf("0", "1", "2", "3").associateWith { List(3) { it } }
        val result = TimelineRetention.retain(loaded, loaded.keys.toList(), "3", "2", assetBudget = 7)
        assertEquals(setOf("2", "3"), result.keys)
        assertEquals(listOf(0, 1, 2), result["3"])
    }

    @Test fun `oversized focused and incoming months are never silently truncated`() {
        val loaded = mapOf("old" to listOf(1), "focus" to List(10) { it }, "new" to List(10) { it })
        val result = TimelineRetention.retain(loaded, loaded.keys.toList(), "new", "focus", assetBudget = 5)
        assertEquals(setOf("focus", "new"), result.keys)
        assertEquals(20, result.values.sumOf { it.size })
    }

    @Test fun `empty markers remain so empty month scanning can continue`() {
        val loaded = (0..30).associate { "$it" to emptyList<Int>() } + ("new" to listOf(1))
        assertEquals(loaded, TimelineRetention.retain(loaded, loaded.keys.toList(), "new", null))
    }
}
