package nl.giejay.android.tv.immich.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineRestorePolicyTest {
    @Test fun `first photos are focusable without any optional memories`() {
        assertTrue(TimelineRestorePolicy.hasFocusableContent(hasMemoryRow = false, hasMosaicAsset = true))
    }

    @Test fun `memories can receive focus when the main timeline is empty`() {
        assertTrue(TimelineRestorePolicy.hasFocusableContent(hasMemoryRow = true, hasMosaicAsset = false))
        assertFalse(TimelineRestorePolicy.hasFocusableContent(hasMemoryRow = false, hasMosaicAsset = false))
    }

    @Test fun `missing old memory waits only until its request completes`() {
        assertEquals(TimelineRestorePolicy.MemoryAction.Wait,
            TimelineRestorePolicy.memoryAction(hasAdapterRow = false, hasMemories = false, memoriesReady = false))
        // Both an empty success and an optional-memory failure complete the request.
        assertEquals(TimelineRestorePolicy.MemoryAction.Forget,
            TimelineRestorePolicy.memoryAction(hasAdapterRow = false, hasMemories = false, memoriesReady = true))
    }

    @Test fun `memory ready before adapter commit keeps restore pending until row is available`() {
        val beforeCommit = TimelineRestorePolicy.memoryAction(
            hasAdapterRow = false, hasMemories = true, memoriesReady = true)
        assertEquals(TimelineRestorePolicy.MemoryAction.Wait, beforeCommit)
        val afterCommit = TimelineRestorePolicy.memoryAction(
            hasAdapterRow = true, hasMemories = true, memoriesReady = true)
        assertEquals(TimelineRestorePolicy.MemoryAction.Focus, afterCommit)
    }

    @Test fun `missing asset is forgotten only after a successful response proves absence`() {
        assertTrue(TimelineRestorePolicy.shouldForgetMissingAsset(requestSucceeded = true, assetFound = false))
        assertFalse(TimelineRestorePolicy.shouldForgetMissingAsset(requestSucceeded = true, assetFound = true))
        assertFalse(TimelineRestorePolicy.shouldForgetMissingAsset(requestSucceeded = false, assetFound = false))
    }

    @Test fun `host appearing after view creation receives both notifications once and in order`() {
        val readiness = TimelineHostReadiness()
        val calls = mutableListOf<String>()
        val host = Any()
        readiness.notify(null, { calls.add("view") }, { calls.add("data") })
        assertTrue(calls.isEmpty())
        readiness.notify(host, { calls.add("view") }, { calls.add("data") })
        readiness.notify(host, { calls.add("duplicate-view") }, { calls.add("duplicate-data") })
        assertEquals(listOf("view", "data"), calls)
    }

    @Test fun `recreated view notifies the same host again after reset`() {
        val readiness = TimelineHostReadiness()
        val host = Any()
        val calls = mutableListOf<String>()
        readiness.notify(host, { calls.add("view") }, { calls.add("data") })
        readiness.reset()
        readiness.notify(host, { calls.add("view") }, { calls.add("data") })
        assertEquals(listOf("view", "data", "view", "data"), calls)
    }

    @Test fun `replacement host gets its own ready events`() {
        val readiness = TimelineHostReadiness()
        val calls = mutableListOf<String>()
        readiness.notify(Any(), { calls.add("first-view") }, { calls.add("first-data") })
        readiness.notify(Any(), { calls.add("second-view") }, { calls.add("second-data") })
        assertEquals(listOf("first-view", "first-data", "second-view", "second-data"), calls)
    }
    @Test fun `synchronous host callback cannot notify the same host twice`() {
        val readiness = TimelineHostReadiness()
        val host = Any()
        val calls = mutableListOf<String>()
        readiness.notify(host, {
            calls.add("view")
            readiness.notify(host, { calls.add("duplicate-view") }, { calls.add("duplicate-data") })
        }, { calls.add("data") })
        assertEquals(listOf("view", "data"), calls)
    }

    @Test fun `failed host notification permits a later retry`() {
        val readiness = TimelineHostReadiness()
        val host = Any()
        val calls = mutableListOf<String>()
        val failure = runCatching {
            readiness.notify(host, { throw IllegalStateException("synthetic host failure") }, {})
        }
        assertTrue(failure.isFailure)
        readiness.notify(host, { calls.add("view") }, { calls.add("data") })
        assertEquals(listOf("view", "data"), calls)
    }

}
