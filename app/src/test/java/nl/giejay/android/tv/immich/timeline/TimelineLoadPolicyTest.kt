package nl.giejay.android.tv.immich.timeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineLoadPolicyTest {
    @Test fun `dense single day does not prefetch another month near its beginning`() {
        assertFalse(TimelineLoadPolicy.nearEnd(10, 5, 10_001))
        assertTrue(TimelineLoadPolicy.nearEnd(9_996, 9_990, 10_001))
    }
    @Test fun `dpad focus near last row triggers load even without a scroll`() {
        assertTrue(TimelineLoadPolicy.nearEnd(80, 97, 100))
        assertFalse(TimelineLoadPolicy.nearEnd(80, 90, 100))
    }
    @Test fun `empty unmeasured or out of range rows never trigger load`() {
        assertFalse(TimelineLoadPolicy.nearEnd(-1, -1, 0))
        assertFalse(TimelineLoadPolicy.nearEnd(-1, -1, 4))
        assertFalse(TimelineLoadPolicy.nearEnd(100, 101, 100))
    }
    @Test fun `short month can request the next month once rows are actually visible`() {
        assertTrue(TimelineLoadPolicy.nearEnd(2, -1, 4))
    }
}
