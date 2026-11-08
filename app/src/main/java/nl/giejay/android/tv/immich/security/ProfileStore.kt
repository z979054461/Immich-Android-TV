package nl.giejay.android.tv.immich.security

import android.content.SharedPreferences
import android.util.Base64
import android.os.SystemClock
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.ServerPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ProfileSummary(val id: String, val name: String)

/** Only encrypted credentials and salted PIN hashes are durable. No automatic unlock on startup. */
class ProfileStore(private val prefs: SharedPreferences) {
    private fun read(): JSONArray = JSONArray(prefs.getString("profiles_v1", "[]"))
    private fun write(rows: JSONArray) {
        check(prefs.edit().putString("profiles_v1", rows.toString()).commit()) { "Secure storage unavailable" }
    }
    @Synchronized fun profiles(): List<ProfileSummary> {
        val rows = read()
        return (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
            ProfileSummary(row.getString("id"), row.getString("name"))
        } }
    }
    @Synchronized fun add(name: String, host: String, key: String, pin: String): String {
        require(name.trim().length in 1..40 && PinHasher.valid(pin) && ServerPolicy.isValidApiKey(key))
        val server = ServerPolicy.normalizeServer(host)
        val rows = read()
        require(rows.length() < 12)
        val salt = PinHasher.salt()
        val hash = PinHasher.hash(pin, salt)
        val encrypted = ApiKeyCipher.encrypt(key)
        val id = UUID.randomUUID().toString()
        rows.put(JSONObject().put("id", id).put("name", name.trim()).put("host", server)
            .put("key", encrypted).put("salt", encode(salt)).put("hash", encode(hash))
            .put("failures", 0).put("nextAttempt", 0L))
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
        val key = ApiKeyCipher.decryptOrAdoptLegacy(row.getString("key"))
        val host = ServerPolicy.normalizeServer(row.getString("host"))
        if (!ServerPolicy.isValidApiKey(key)) return null
        row.put("failures", 0).put("nextAttempt", 0L).put("nextElapsed", 0L)
        write(rows)
        return ApiClientConfig(host, key, false, false)
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
