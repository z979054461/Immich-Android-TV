package nl.giejay.android.tv.immich.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A local screen lock, not an Immich password or protection against a rooted device. */
object PinHasher {
    const val ITERATIONS = 600_000
    const val PIN_LENGTH = 4

    /** Only this format may be enrolled, including when changing an existing PIN. */
    fun valid(pin: String) = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }
    fun isLegacy(pin: String) = pin.length in 6..12 && pin.all { it in '0'..'9' }
    fun salt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    fun hash(pin: String, salt: ByteArray): ByteArray {
        require(valid(pin) && salt.size == 16)
        return derive(pin, salt)
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, ITERATIONS, 256)
        return try {
            // SHA1 PBKDF2 is available on every supported Android version (API 24+).
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally { chars.fill('\u0000'); spec.clearPassword() }
    }

    /** Legacy verification always uses the entire old PIN, never a shortened prefix. */
    fun verify(pin: String, salt: ByteArray, expected: ByteArray): Boolean =
        (valid(pin) || isLegacy(pin)) && salt.size == 16 && expected.size == 32 &&
            MessageDigest.isEqual(derive(pin, salt), expected)

    fun delayMillis(failures: Int): Long = if (failures < 5) 0 else
        (30_000L * (1L shl (failures - 5).coerceIn(0, 4))).coerceAtMost(300_000L)
}
