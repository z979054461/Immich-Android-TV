package nl.giejay.android.tv.immich.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ProfileStoreTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: ProfileStore
    @Before fun setup() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test_profiles", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = ProfileStore(prefs)
    }
    private fun seed(failures: Int = 0, next: Long = 0L, pin: String = "123456", key: String = "untrusted-plaintext") {
        val salt = ByteArray(16) { it.toByte() }
        val row = JSONObject().put("id", "synthetic-id").put("name", "Family")
            .put("host", "https://photos.example").put("key", key)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("hash", Base64.encodeToString(legacyVerifier(pin, salt), Base64.NO_WRAP))
            .put("failures", failures).put("nextAttempt", next)
        prefs.edit().putString("profiles_v1", JSONArray().put(row).toString()).commit()
    }
    private fun workingStore(preferences: SharedPreferences = prefs) = ProfileStore(preferences,
        { "synthetic-ciphertext" }, { if (it == "synthetic-ciphertext") "synthetic-api-key" else "" })

    private fun row() = JSONArray(prefs.getString("profiles_v1", "[]")).getJSONObject(0)

    @Test fun `new enrollment accepts four digits and rejects every legacy length`() {
        val working = workingStore()
        for (pin in listOf("123", "12345", "123456", "123456789012", "１２３４")) {
            assertThrows(IllegalArgumentException::class.java) {
                working.add("Family", "https://photos.example", "synthetic-api-key", pin)
            }
        }
        assertTrue(working.profiles().isEmpty())
        val id = working.add("Family", "https://photos.example", "synthetic-api-key", "0123")
        assertEquals(4, working.profiles().single().pinLength)
        assertNotNull(working.unlock(id, "0123", now = 1000, elapsed = 1000))
        assertNull(working.unlock(id, "123", now = 1001, elapsed = 1001))
    }

    @Test fun `legacy migration requires the full old PIN and preserves account identity`() {
        seed(pin = "012345678901", key = "synthetic-ciphertext")
        val original = row()
        val working = workingStore()
        assertNull(working.changePin("synthetic-id", "0123", "4321", now = 1000, elapsed = 1000))
        assertEquals(original.getString("hash"), row().getString("hash"))
        assertNotNull(working.changePin("synthetic-id", "012345678901", "4321", now = 1001, elapsed = 1001))
        val migrated = row()
        for (field in listOf("id", "name", "host", "key")) assertEquals(original.getString(field), migrated.getString(field))
        assertNotEquals(original.getString("salt"), migrated.getString("salt"))
        assertNotEquals(original.getString("hash"), migrated.getString("hash"))
        assertEquals(0, migrated.getInt("failures"))
        assertEquals(4, working.profiles().single().pinLength)
        assertNotNull(workingStore().unlock("synthetic-id", "4321", now = 1002, elapsed = 1002))
        assertNull(working.unlock("synthetic-id", "012345678901", now = 1003, elapsed = 1003))
        assertNull(working.changePin("synthetic-id", "012345678901", "9876", now = 1004, elapsed = 1004))
        assertNotNull(working.unlock("synthetic-id", "4321", now = 1005, elapsed = 1005))
    }

    @Test fun `verifying old PIN alone leaves old verifier unchanged until explicit change`() {
        seed(key = "synthetic-ciphertext")
        val original = row()
        assertNotNull(workingStore().unlock("synthetic-id", "123456", now = 1000, elapsed = 1000))
        assertEquals(original.getString("hash"), row().getString("hash"))
        assertEquals(original.getString("salt"), row().getString("salt"))
        assertNotNull(workingStore().unlock("synthetic-id", "123456", now = 1001, elapsed = 1001))
        assertNull(workingStore().unlock("synthetic-id", "1234", now = 1002, elapsed = 1002))
    }

    @Test fun `changing existing four digit PIN requires current PIN and a four digit replacement`() {
        seed(pin = "0123", key = "synthetic-ciphertext")
        val original = row().getString("hash")
        for (pin in listOf("123", "12345", "123456", "１２３４")) {
            assertThrows(IllegalArgumentException::class.java) {
                workingStore().changePin("synthetic-id", "0123", pin, now = 1000, elapsed = 1000)
            }
        }
        assertEquals(original, row().getString("hash"))
        assertNotNull(workingStore().changePin("synthetic-id", "0123", "9876", now = 1000, elapsed = 1000))
        assertNull(workingStore().unlock("synthetic-id", "0123", now = 1001, elapsed = 1001))
        assertNotNull(workingStore().unlock("synthetic-id", "9876", now = 1002, elapsed = 1002))
    }

    @Test fun `change shares persisted cooldown and cannot bypass clock or reboot protection`() {
        seed(failures = 4, key = "synthetic-ciphertext")
        val original = row().getString("hash")
        assertNull(workingStore().changePin("synthetic-id", "000000", "4321", now = 1000, elapsed = 1000))
        assertEquals(5, row().getInt("failures"))
        assertNull(workingStore().changePin("synthetic-id", "123456", "4321", now = 999999999, elapsed = 2000))
        assertNull(workingStore().unlock("synthetic-id", "123456", now = 2000, elapsed = 10))
        assertEquals(original, row().getString("hash"))
        assertEquals(5, row().getInt("failures"))
        assertNotNull(workingStore().changePin("synthetic-id", "123456", "4321", now = 31000, elapsed = 29010))
    }

    @Test fun `failed attempt persistence never reaches verification or migration`() {
        seed(key = "synthetic-ciphertext")
        val original = prefs.getString("profiles_v1", null)
        val failing = FailCommitPreferences(prefs, 1)
        assertThrows(IllegalStateException::class.java) {
            workingStore(failing).changePin("synthetic-id", "123456", "4321", now = 1000, elapsed = 1000)
        }
        assertEquals(original, prefs.getString("profiles_v1", null))
    }

    @Test fun `failed migration commit restores old PIN even if preferences memory was changed`() {
        seed(key = "synthetic-ciphertext")
        val original = row()
        // Attempt persistence, successful authentication, then replacement verifier commit.
        val failing = FailCommitPreferences(prefs, 3)
        assertThrows(IllegalStateException::class.java) {
            workingStore(failing).changePin("synthetic-id", "123456", "4321", now = 1000, elapsed = 1000)
        }
        assertEquals(original.getString("hash"), row().getString("hash"))
        assertEquals(original.getString("salt"), row().getString("salt"))
        assertNull(workingStore().unlock("synthetic-id", "4321", now = 1001, elapsed = 1001))
        assertNotNull(workingStore().unlock("synthetic-id", "123456", now = 1002, elapsed = 1002))
        assertNotNull(workingStore().changePin("synthetic-id", "123456", "4321", now = 1003, elapsed = 1003))
    }

    @Test fun `missing format hints never infer a four digit prefix and are learned only on success`() {
        seed(key = "synthetic-ciphertext")
        assertNull(workingStore().profiles().single().pinLength)
        assertNull(workingStore().unlock("synthetic-id", "1234", now = 1000, elapsed = 1000))
        assertNull(workingStore().profiles().single().pinLength)
        assertNotNull(workingStore().unlock("synthetic-id", "123456", now = 1001, elapsed = 1001))
        assertNull(workingStore().profiles().single().pinLength)
        seed(pin = "0123", key = "synthetic-ciphertext")
        assertNull(workingStore().profiles().single().pinLength)
        assertNotNull(workingStore().unlock("synthetic-id", "0123", now = 1002, elapsed = 1002))
        assertEquals(4, workingStore().profiles().single().pinLength)
    }

    @Test fun `listing never exposes stored key or verifier`() {
        seed()
        assertEquals(listOf(ProfileSummary("synthetic-id", "Family")), store.profiles())
        assertFalse(store.profiles().toString().contains("untrusted-plaintext"))
    }
    @Test fun `attempt is persisted and cooldown survives new store instance`() {
        seed(failures = 4)
        assertNull(store.unlock("synthetic-id", "000000", now = 1000, elapsed = 1000))
        val row = JSONArray(prefs.getString("profiles_v1", "[]")).getJSONObject(0)
        assertEquals(5, row.getInt("failures"))
        assertEquals(31_000L, row.getLong("nextAttempt"))
        assertNull(ProfileStore(prefs).unlock("synthetic-id", "123456", now = 2000, elapsed = 2000))
        assertEquals(5, JSONArray(prefs.getString("profiles_v1", "[]")).getJSONObject(0).getInt("failures"))
    }
    @Test fun `moving wall clock forward cannot bypass cooldown within boot`() {
        seed(failures = 4)
        assertNull(store.unlock("synthetic-id", "000000", now = 1000, elapsed = 1000))
        assertNull(ProfileStore(prefs).unlock("synthetic-id", "123456", now = 999999999, elapsed = 2000))
        assertEquals(5, JSONArray(prefs.getString("profiles_v1", "[]")).getJSONObject(0).getInt("failures"))
    }
    @Test fun `correct PIN cannot adopt plaintext credential`() {
        seed()
        assertNull(store.unlock("synthetic-id", "123456", now = 1000, elapsed = 1000))
    }
    @Test fun `remove only deletes selected local profile`() {
        seed()
        store.remove("different-id")
        assertEquals(1, store.profiles().size)
        store.remove("synthetic-id")
        assertTrue(store.profiles().isEmpty())
    }
    @Test fun `malformed storage fails closed rather than allowing replacement`() {
        prefs.edit().putString("profiles_v1", "broken").commit()
        assertThrows(Exception::class.java) { store.profiles() }
        assertThrows(Exception::class.java) { store.unlock("anything", "123456") }
    }
}

/** Models Android updating memory even when disk commit reports failure. */
private class FailCommitPreferences(
    private val backing: SharedPreferences,
    private val failCommit: Int
) : SharedPreferences by backing {
    private var commits = 0
    override fun edit(): SharedPreferences.Editor {
        val editor = backing.edit()
        return object : SharedPreferences.Editor by editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                editor.putString(key, value)
                return this
            }
            override fun commit(): Boolean {
                val result = editor.commit()
                commits++
                return result && commits != failCommit
            }
        }
    }
}
