package nl.giejay.android.tv.immich.security

import android.content.SharedPreferences
import android.util.Base64
import android.os.SystemClock
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.ServerPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Non-secret input-format hint; missing/unknown values always require explicit confirmation. */
data class ProfileSummary(val id: String, val name: String, val pinLength: Int? = null)

/** Non-secret policy revision prevents a suspended session surviving credential/policy changes. */
data class ProfileSessionPolicy(val graceMinutes: Int, val revision: String)

/** Only encrypted credentials and salted PIN hashes are durable. No automatic unlock on startup. */
class ProfileStore internal constructor(
    private val prefs: SharedPreferences,
    private val encryptKey: (String) -> String,
    private val decryptKey: (String) -> String
) {
    constructor(prefs: SharedPreferences) : this(prefs,
        { ApiKeyCipher.encrypt(it) }, { ApiKeyCipher.decryptOrAdoptLegacy(it) })
    private fun read(): JSONArray = JSONArray(prefs.getString("profiles_v1", "[]"))
    private fun write(rows: JSONArray) {
        val previous = prefs.getString("profiles_v1", null)
        val saved = runCatching { prefs.edit().putString("profiles_v1", rows.toString()).commit() }.getOrDefault(false)
        if (!saved) {
            // SharedPreferences updates memory even when its disk commit fails. Restore the
            // previous verifier in memory too, so a failed PIN change cannot silently take effect.
            runCatching { prefs.edit().putString("profiles_v1", previous).commit() }
            error("Secure storage unavailable")
        }
    }
    @Synchronized fun profiles(): List<ProfileSummary> {
        val rows = read()
        return (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
            ProfileSummary(row.getString("id"), row.getString("name"), row.optInt("pinLength", 0).takeIf { it == PinHasher.PIN_LENGTH })
        } }
    }
    @Synchronized fun sessionPolicy(id: String): ProfileSessionPolicy? {
        val rows = read()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
            ?: return null
        val minutes = if (row.has("pinGraceMinutes")) row.optInt("pinGraceMinutes", 0) else 5
        return ProfileSessionPolicy(if (minutes in listOf(0, 1, 5, 15)) minutes else 0,
            row.optString("sessionRevision", "legacy"))
    }

    @Synchronized fun setGraceMinutes(id: String, minutes: Int) {
        require(minutes in listOf(0, 1, 5, 15))
        val rows = read()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
            ?: error("Profile unavailable")
        row.put("pinGraceMinutes", minutes).put("sessionRevision", UUID.randomUUID().toString())
        write(rows)
    }

    @Synchronized fun add(name: String, host: String, key: String, pin: String): String {
        require(name.trim().length in 1..40 && PinHasher.valid(pin) && ServerPolicy.isValidApiKey(key))
        val server = ServerPolicy.normalizeServer(host)
        val rows = read()
        require(rows.length() < 12)
        val salt = PinHasher.salt()
        val hash = PinHasher.hash(pin, salt)
        val encrypted = encryptKey(key)
        val id = UUID.randomUUID().toString()
        rows.put(JSONObject().put("id", id).put("name", name.trim()).put("host", server)
            .put("key", encrypted).put("salt", encode(salt)).put("hash", encode(hash))
            .put("failures", 0).put("nextAttempt", 0L).put("pinLength", PinHasher.PIN_LENGTH)
            .put("sessionRevision", UUID.randomUUID().toString()).put("pinGraceMinutes", 5))
        write(rows)
        return id
    }
    /** Count and persist the attempt BEFORE testing it, so killing the process cannot reset throttling. */
    @Synchronized fun unlock(id: String, pin: String, now: Long = System.currentTimeMillis(), elapsed: Long = SystemClock.elapsedRealtime()): ApiClientConfig? {
        val rows = read()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
            ?: return null
        val lastElapsed = row.optLong("lastElapsed", -1L)
        if (lastElapsed >= 0 && elapsed >= lastElapsed) {
            // Within one boot, changing the TV wall clock cannot bypass the cooldown.
            if (elapsed < row.getLong("nextElapsed")) return null
        } else if (now < row.getLong("nextAttempt")) {
            // After reboot/clock rollback, start a bounded monotonic remainder, not an
            // arbitrarily long wall-clock lockout. Persist it before returning.
            val remainder = (row.getLong("nextAttempt") - now).coerceIn(0L, 300_000L)
            row.put("lastElapsed", elapsed).put("nextElapsed", elapsed + remainder)
            write(rows)
            return null
        }
        val failures = (row.getInt("failures") + 1).coerceIn(1, 100)
        val delay = PinHasher.delayMillis(failures)
        row.put("failures", failures).put("nextAttempt", now + delay)
            .put("lastElapsed", elapsed).put("nextElapsed", elapsed + delay)
        write(rows)
        if (!PinHasher.verify(pin, decode(row.getString("salt")), decode(row.getString("hash")))) return null
        val key = decryptKey(row.getString("key"))
        val host = ServerPolicy.normalizeServer(row.getString("host"))
        if (!ServerPolicy.isValidApiKey(key)) return null
        row.put("failures", 0).put("nextAttempt", 0L).put("nextElapsed", 0L)
        // Learn the hint only after full verification; no inference from a hash or entered prefix.
        if (PinHasher.valid(pin)) row.put("pinLength", PinHasher.PIN_LENGTH)
        write(rows)
        return ApiClientConfig(host, key, false, false)
    }
    /**
     * Reverify the complete current PIN through the normal persisted throttle. A previous UI
     * unlock is not authority to overwrite a verifier that may have changed since then.
     * The replacement and salt are committed together before any unlocked session is returned.
     */
    @Synchronized fun changePin(
        id: String,
        currentPin: String,
        newPin: String,
        now: Long = System.currentTimeMillis(),
        elapsed: Long = SystemClock.elapsedRealtime()
    ): ApiClientConfig? {
        require(PinHasher.valid(newPin))
        val config = unlock(id, currentPin, now, elapsed) ?: return null
        val rows = read()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
            ?: return null
        val salt = PinHasher.salt()
        row.put("salt", encode(salt)).put("hash", encode(PinHasher.hash(newPin, salt)))
            .put("pinLength", PinHasher.PIN_LENGTH).put("sessionRevision", UUID.randomUUID().toString())
        write(rows)
        return config
    }

    @Synchronized fun remove(id: String) {
        val rows = read()
        val remaining = JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.getString("id") != id) remaining.put(row)
        }
        write(remaining)
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
}
