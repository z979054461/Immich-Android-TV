package nl.giejay.android.tv.immich.security

import org.junit.Assert.*
import org.junit.Test

class PinHasherTest {
    @Test fun `PIN format allows only six to twelve ASCII digits`() {
        listOf("", "12345", "1234567890123", "１２３４５６", "123 56", "abcdef").forEach {
            assertFalse(PinHasher.valid(it))
        }
        assertTrue(PinHasher.valid("012345"))
        assertTrue(PinHasher.valid("123456789012"))
    }
    @Test fun `salted hashes verify without storing plaintext`() {
        val salt = PinHasher.salt()
        val hash = PinHasher.hash("012345", salt)
        assertEquals(32, hash.size)
        assertTrue(PinHasher.verify("012345", salt, hash))
        assertFalse(PinHasher.verify("012346", salt, hash))
        assertFalse(PinHasher.verify("012345", PinHasher.salt(), hash))
        assertFalse(PinHasher.verify("012345", salt, byteArrayOf(1)))
    }
    @Test fun `failed attempts have bounded exponential delay`() {
        assertEquals(0L, PinHasher.delayMillis(4))
        assertEquals(30_000L, PinHasher.delayMillis(5))
        assertEquals(60_000L, PinHasher.delayMillis(6))
        assertEquals(300_000L, PinHasher.delayMillis(100))
    }
}
