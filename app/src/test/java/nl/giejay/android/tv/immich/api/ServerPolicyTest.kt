package nl.giejay.android.tv.immich.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ServerPolicyTest {
    @Test fun normalizesOriginButPreservesPathCase() {
        assertEquals("https://nas.example/PhotoLibrary", ServerPolicy.normalizeServer(" HTTPS://NAS.EXAMPLE:443/PhotoLibrary/api/ "))
        assertEquals("http://nas.example:2283", ServerPolicy.normalizeServer("http://NAS.EXAMPLE:2283/"))
        assertEquals("https://nas.example", ServerPolicy.normalizeServer("https://nas.example/api"))
        assertEquals("https://nas.example/PhotoLibrary/api/", ServerPolicy.apiBase("https://nas.example/PhotoLibrary").toString())
    }

    @Test fun rejectsMalformedOrAmbiguousServerConfiguration() {
        listOf("", "nas.example", "ftp://nas.example", "file:///tmp/photos", "https://user:pass@nas.example",
            "https://user@nas.example", "https://nas.example?token=secret", "https://nas.example#fragment",
            "https://nas.ex ample", "https://nas.example/\napi", "https://nas.example/\u0000").forEach { input ->
            assertThrows("Unexpectedly accepted server: ${input.replace('\n', '_')}", IllegalArgumentException::class.java) {
                ServerPolicy.normalizeServer(input)
            }
        }
    }

    @Test fun validatesKeysWithoutTrimmingOrAllowingHeaderControls() {
        assertTrue(ServerPolicy.isValidApiKey("a-valid_key.123"))
        listOf("", " ", " leading", "trailing ", "two words", "key\nvalue", "key\rvalue", "key\tvalue",
            "key\u0000", "key\u007f", "key\u0080", "clé").forEach {
            assertFalse(ServerPolicy.isValidApiKey(it))
        }
    }

    @Test fun allowsOnlyConfiguredOriginAndApiPath() {
        val server = "https://nas.example/PhotoLibrary"
        ServerPolicy.requireAllowed(server, "https://nas.example/PhotoLibrary/api/assets?size=thumbnail".toHttpUrl())
        listOf("http://nas.example/PhotoLibrary/api/assets", "https://other.example/PhotoLibrary/api/assets",
            "https://nas.example:444/PhotoLibrary/api/assets", "https://nas.example/photolibrary/api/assets",
            "https://nas.example/PhotoLibrary/api-other/assets", "https://nas.example/PhotoLibrary/api/../secret",
            "https://user:pass@nas.example/PhotoLibrary/api/assets", "https://nas.example/PhotoLibrary/api/assets#fragment",
            "https://nas.example/PhotoLibrary/api/assets?apiKey=secret", "https://nas.example/PhotoLibrary/api/assets?API_KEY=secret",
            "https://nas.example/PhotoLibrary/api/assets?%61piKey=secret").forEach {
            assertThrows(IOException::class.java) { ServerPolicy.requireAllowed(server, it.toHttpUrl()) }
        }
        assertThrows(IOException::class.java) { ServerPolicy.requireAllowed("", "https://nas.example/api/assets".toHttpUrl()) }
    }
}
