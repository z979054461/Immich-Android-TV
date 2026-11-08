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
    private fun seed(failures: Int = 0, next: Long = 0L) {
        val salt = ByteArray(16) { it.toByte() }
        val row = JSONObject().put("id", "synthetic-id").put("name", "Family")
            .put("host", "https://photos.example").put("key", "untrusted-plaintext")
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("hash", Base64.encodeToString(PinHasher.hash("123456", salt), Base64.NO_WRAP))
            .put("failures", failures).put("nextAttempt", next)
        prefs.edit().putString("profiles_v1", JSONArray().put(row).toString()).commit()
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
