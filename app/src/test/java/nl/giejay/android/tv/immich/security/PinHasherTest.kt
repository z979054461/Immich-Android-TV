package nl.giejay.android.tv.immich.security

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinHasherTest {
    @Test fun `new PIN format allows exactly four ASCII digits`() {
        listOf("", "123", "12345", "123456", "123456789012", "１２３４", "١٢٣٤", "12 4", "abcd", "1234\n").forEach {
            assertFalse(it, PinHasher.valid(it))
        }
        assertTrue(PinHasher.valid("0123"))
        assertTrue(PinHasher.valid("0000"))
        assertTrue(PinHasher.valid("9999"))
    }
    @Test fun `salted hashes verify four digits without storing plaintext`() {
        val salt = PinHasher.salt()
        val hash = PinHasher.hash("0123", salt)
        assertEquals(32, hash.size)
        assertTrue(PinHasher.verify("0123", salt, hash))
        assertFalse(PinHasher.verify("0124", salt, hash))
        assertFalse(PinHasher.verify("0123", PinHasher.salt(), hash))
        assertFalse(PinHasher.verify("0123", salt, byteArrayOf(1)))
        assertFalse(PinHasher.verify("0123", byteArrayOf(1), hash))
        assertFalse(PinHasher.verify("012345", salt, hash))
    }
    @Test fun `legacy enrollment is rejected but full old PINs still verify`() {
        for (pin in listOf("012345", "012345678901")) {
            val salt = PinHasher.salt()
            val hash = legacyVerifier(pin, salt)
            assertThrows(IllegalArgumentException::class.java) { PinHasher.hash(pin, salt) }
            assertTrue(PinHasher.isLegacy(pin))
            assertTrue(PinHasher.verify(pin, salt, hash))
            assertFalse(PinHasher.verify(pin.take(4), salt, hash))
            assertFalse(PinHasher.verify(pin.take(5), salt, hash))
            assertFalse(PinHasher.verify(pin + "0", salt, hash))
        }
        assertFalse(PinHasher.isLegacy("0123"))
        assertFalse(PinHasher.isLegacy("１２３４５６"))
        assertFalse(PinHasher.isLegacy("1234567890123"))
    }
    @Test fun `failed attempts have bounded exponential delay`() {
        assertEquals(0L, PinHasher.delayMillis(4))
        assertEquals(30_000L, PinHasher.delayMillis(5))
        assertEquals(60_000L, PinHasher.delayMillis(6))
        assertEquals(300_000L, PinHasher.delayMillis(100))
    }
}

/** Independent fixture for the previous release, which enrolled 6–12 digits. */
internal fun legacyVerifier(pin: String, salt: ByteArray): ByteArray {
    val chars = pin.toCharArray()
    val spec = PBEKeySpec(chars, salt, 600_000, 256)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    } finally { chars.fill('\u0000'); spec.clearPassword() }
}
