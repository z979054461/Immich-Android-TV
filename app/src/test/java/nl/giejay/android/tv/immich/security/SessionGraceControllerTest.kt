package nl.giejay.android.tv.immich.security

import org.junit.Assert.*
import org.junit.Test

class SessionGraceControllerTest {
    private class Timer(val delay: Long, val action: () -> Unit) {
        var canceled = false
    }
    private class Fixture {
        var now = 10_000L
        val timers = mutableListOf<Timer>()
        val controller = SessionGraceController<String>(
            clock = { now },
            schedule = { delay, action ->
                val timer = Timer(delay, action)
                timers += timer
                SessionGraceController.Cancellation { timer.canceled = true }
            },
        )
    }

    @Test fun `default five minutes is valid just before deadline and consumed once`() {
        val f = Fixture()
        f.controller.arm("a", "secret")
        assertEquals(300_000L, f.timers.single().delay)
        f.now += 299_999L
        assertEquals("a", f.controller.peekProfileId())
        assertEquals("secret", f.controller.consume("a"))
        assertNull(f.controller.consume("a"))
        assertTrue(f.timers.single().canceled)
        assertFalse(f.controller.hasPending())
    }

    @Test fun `exact expiry is invalid even if cleanup timer has not run`() {
        val f = Fixture()
        f.controller.arm("a", "secret", 60_000L)
        f.now += 60_000L
        assertNull(f.controller.consume("a"))
        assertTrue(f.timers.single().canceled)
    }

    @Test fun `late cleanup destroys pending state without a foreground visit`() {
        val f = Fixture()
        f.controller.arm("a", "secret")
        f.now += 400_000L
        f.timers.single().action()
        val pendingField = f.controller.javaClass.getDeclaredField("pending")
        pendingField.isAccessible = true
        assertTrue("Timer must release credentials before any public read",
            pendingField.get(f.controller) == null)
        assertTrue(f.timers.single().canceled)
        assertNull(f.controller.peekProfileId())
        assertNull(f.controller.consume("a"))
    }

    @Test fun `early cleanup schedules only remaining time`() {
        val f = Fixture()
        f.controller.arm("a", "secret")
        f.now += 100_000L
        f.timers.single().action()
        assertTrue(f.timers.first().canceled)
        assertEquals(200_000L, f.timers.last().delay)
        f.now += 200_000L
        f.timers.last().action()
        assertNull(f.controller.consume("a"))
    }

    @Test fun `clock rollback fails closed including rollback after a successful observation`() {
        val f = Fixture()
        f.controller.arm("a", "secret")
        f.now += 10_000L
        assertTrue(f.controller.hasPending())
        f.now -= 1L
        assertNull(f.controller.consume("a"))
        assertTrue(f.timers.single().canceled)
    }

    @Test fun `repeated background arm neither extends deadline nor replaces credentials`() {
        val f = Fixture()
        f.controller.arm("a", "original", 60_000L)
        f.now += 30_000L
        f.controller.arm("a", "replacement", 900_000L)
        assertEquals(1, f.timers.size)
        assertEquals("original", f.controller.consume("a"))
        f.controller.arm("a", "original", 60_000L)
        f.now += 30_000L
        f.controller.arm("a", "replacement", 900_000L)
        f.now += 30_000L
        assertNull(f.controller.consume("a"))
    }

    @Test fun `repeated arm at expiry cannot resurrect pending grant`() {
        val f = Fixture()
        f.controller.arm("a", "secret", 60_000L)
        f.now += 60_000L
        f.controller.arm("a", "replacement")
        assertNull(f.controller.consume("a"))
        assertEquals(1, f.timers.size)
    }

    @Test fun `wrong consume profile or different arm profile destroys grant`() {
        val f = Fixture()
        f.controller.arm("a", "secret")
        assertNull(f.controller.consume("b"))
        assertNull(f.controller.consume("a"))
        f.controller.arm("a", "secret")
        f.controller.arm("b", "other")
        assertFalse(f.controller.hasPending())
    }

    @Test fun `clear and stale canceled timer cannot damage a newer grant`() {
        val f = Fixture()
        f.controller.arm("a", "old", 60_000L)
        val old = f.timers.single()
        f.controller.clear()
        assertTrue(old.canceled)
        assertNull(f.controller.consume("a"))
        f.controller.arm("b", "new")
        f.now += 60_000L
        old.action()
        assertEquals("new", f.controller.consume("b"))
    }

    @Test fun `new process controller starts empty`() {
        val old = Fixture()
        old.controller.arm("a", "secret")
        val recreated = Fixture()
        assertFalse(recreated.controller.hasPending())
        assertNull(recreated.controller.peekProfileId())
        assertNull(recreated.controller.consume("a"))
    }

    @Test fun `immediate mode retains no credentials and cancels any old grant`() {
        val f = Fixture()
        f.controller.arm("a", "secret", 0L)
        assertFalse(f.controller.hasPending())
        assertTrue(f.timers.isEmpty())
        f.controller.arm("a", "secret")
        f.controller.arm("a", "secret", 0L)
        assertFalse(f.controller.hasPending())
        assertTrue(f.timers.single().canceled)
    }

    @Test fun `supported preferences map to durations and invalid preferences fail closed`() {
        listOf(0, 1, 5, 15).forEach {
            assertEquals(it * 60_000L, SessionGraceController.durationMillis(it))
        }
        listOf(-1, 2, Int.MAX_VALUE).forEach {
            assertEquals(0L, SessionGraceController.durationMillis(it))
        }
        listOf(60_000L, 300_000L, 900_000L).forEach {
            val f = Fixture()
            f.controller.arm("a", "secret", it)
            assertEquals(it, f.timers.single().delay)
            assertTrue(f.controller.hasPending())
        }
    }

    @Test fun `invalid raw durations and invalid clocks fail closed`() {
        listOf(-1L, 2L, Long.MAX_VALUE).forEach {
            val f = Fixture()
            f.controller.arm("a", "secret", it)
            assertFalse(f.controller.hasPending())
            assertTrue(f.timers.isEmpty())
            f.controller.arm("a", "valid")
            f.controller.arm("a", "invalid", it)
            assertFalse(f.controller.hasPending())
            assertTrue(f.timers.single().canceled)
        }
        val f = Fixture()
        f.now = -1L
        f.controller.arm("a", "secret")
        assertFalse(f.controller.hasPending())
    }

    @Test fun `clock near maximum uses elapsed difference without deadline overflow`() {
        val f = Fixture()
        f.now = Long.MAX_VALUE - 60_000L
        f.controller.arm("a", "secret", 60_000L)
        f.now = Long.MAX_VALUE - 1L
        assertTrue(f.controller.hasPending())
        f.now = Long.MAX_VALUE
        assertNull(f.controller.consume("a"))
    }

    @Test fun `clock wrapping to negative fails closed`() {
        val f = Fixture()
        f.now = Long.MAX_VALUE - 1L
        f.controller.arm("a", "secret")
        f.now = Long.MAX_VALUE
        assertTrue(f.controller.hasPending())
        f.now = Long.MIN_VALUE
        assertNull(f.controller.consume("a"))
        assertTrue(f.timers.single().canceled)
    }

    @Test fun `timer registration failure cannot leave credentials retained`() {
        val controller = SessionGraceController<String>(
            clock = { 0L },
            schedule = { _, _ -> throw IllegalStateException("scheduler unavailable") },
        )
        assertThrows(IllegalStateException::class.java) { controller.arm("a", "secret") }
        assertFalse(controller.hasPending())
    }
}
