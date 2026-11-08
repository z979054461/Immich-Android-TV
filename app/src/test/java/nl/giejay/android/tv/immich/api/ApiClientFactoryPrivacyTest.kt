package nl.giejay.android.tv.immich.api

import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real loopback HTTP requests through the production interceptor, never a private NAS. */
class ApiClientFactoryPrivacyTest {
    private lateinit var allowed: MockWebServer
    private lateinit var forbidden: MockWebServer
    private val key = "loopback-test-key"
    private lateinit var originalConnection: ApiClientConfig

    private fun publish(config: ApiClientConfig) {
        PreferenceManager.javaClass.getDeclaredField("activeConnection").apply {
            isAccessible = true
        }.set(PreferenceManager, config)
    }

    private fun configuration(server: MockWebServer = allowed, apiKey: String = key) =
        ApiClientConfig(server.url("/").toString().trimEnd('/'), apiKey, false, false)

    @Before fun startServers() {
        originalConnection = PreferenceManager.connectionSnapshot()
        allowed = MockWebServer().apply { start() }
        forbidden = MockWebServer().apply { start() }
    }

    @After fun stopServers() {
        publish(originalConnection)
        allowed.shutdown()
        forbidden.shutdown()
    }

    private fun client(server: String = allowed.url("/").toString(), apiKey: String = key) =
        ApiClientFactory.getClient(true, apiKey, true, server)
            .newBuilder().callTimeout(2, TimeUnit.SECONDS).build()

    @Test fun sendsExactlyOneKeyHeaderAndNoQueryCredential() {
        allowed.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url(allowed.url("/api/assets?size=thumbnail"))
            .addHeader("x-api-key", "stale-one").addHeader("x-api-key", "stale-two").build()
        client().newCall(request).execute().use { assertEquals(200, it.code) }
        val recorded = allowed.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(listOf(key), recorded.headers.values("x-api-key"))
        assertEquals("/api/assets?size=thumbnail", recorded.path)
        assertEquals(0, forbidden.requestCount)
    }

    @Test fun neverFollowsAnyRedirectToSecondOrigin() {
        val http = client()
        assertFalse(http.followRedirects)
        assertFalse(http.followSslRedirects)
        listOf(301, 302, 303, 307, 308).forEach { code ->
            allowed.enqueue(MockResponse().setResponseCode(code).setHeader("Location", forbidden.url("/api/stolen")))
            http.newCall(Request.Builder().url(allowed.url("/api/assets")).build()).execute().use {
                assertEquals(code, it.code)
            }
        }
        assertEquals(5, allowed.requestCount)
        assertNull(forbidden.takeRequest(100, TimeUnit.MILLISECONDS))
        assertEquals(0, forbidden.requestCount)
    }

    @Test fun rejectsForbiddenHostnameBeforeDnsResolution() {
        val lookups = AtomicInteger()
        val http = client().newBuilder().dns(Dns {
            lookups.incrementAndGet()
            listOf(InetAddress.getLoopbackAddress())
        }).build()
        assertThrows(IOException::class.java) {
            http.newCall(Request.Builder().url("http://forbidden.invalid:${forbidden.port}/api/assets").build()).execute().close()
        }
        assertEquals(0, lookups.get())
        assertEquals(0, allowed.requestCount)
        assertEquals(0, forbidden.requestCount)
    }

    @Test fun rejectsDifferentPortWithoutSendingRequest() {
        assertThrows(IOException::class.java) {
            client().newCall(Request.Builder().url(forbidden.url("/api/assets")).build()).execute().close()
        }
        assertEquals(0, allowed.requestCount)
        assertEquals(0, forbidden.requestCount)
    }

    @Test fun invalidConfigurationAndKeysFailWithoutNetwork() {
        val request = Request.Builder().url(allowed.url("/api/assets")).build()
        listOf("", "invalid", "https://user:password@example.com").forEach { server ->
            assertThrows(IOException::class.java) { client(server).newCall(request).execute().close() }
        }
        listOf("", "key\r\ninjected: yes", "key with space").forEach { apiKey ->
            assertThrows(IOException::class.java) { client(apiKey = apiKey).newCall(request).execute().close() }
        }
        assertEquals(0, allowed.requestCount)
        assertEquals(0, forbidden.requestCount)
    }

    @Test fun currentSessionWorksButRejectsNewServerAndLogout() {
        val initial = configuration()
        publish(initial)
        assertSame(initial, ApiClientConfig.fromPrefs())
        val http = ApiClientFactory.forSession(initial)
        val request = Request.Builder().url(allowed.url("/api/assets")).build()
        allowed.enqueue(MockResponse().setBody("ok"))
        http.newCall(request).execute().use { assertEquals(200, it.code) }
        publish(configuration(forbidden, "other-key"))
        assertThrows(IOException::class.java) { http.newCall(request).execute().close() }
        publish(ApiClientConfig("", "", false, false))
        assertThrows(IOException::class.java) { http.newCall(request).execute().close() }
        assertEquals(1, allowed.requestCount)
        assertEquals(0, forbidden.requestCount)
    }

    @Test fun staleSessionRejectsRotatedKeyBeforeDns() {
        val initial = ApiClientConfig("http://session.invalid:2283", key, false, false)
        publish(initial)
        val lookups = AtomicInteger()
        val http = ApiClientFactory.forSession(initial).newBuilder().dns(Dns {
            lookups.incrementAndGet()
            listOf(InetAddress.getLoopbackAddress())
        }).build()
        publish(initial.copy(apiKey = "rotated-key"))
        assertThrows(IOException::class.java) {
            http.newCall(Request.Builder().url("http://session.invalid:2283/api/assets").build()).execute().close()
        }
        assertEquals(0, lookups.get())
    }

    @Test fun imageClientReadsFreshSnapshotWithoutRecreation() {
        val http = ApiClientFactory.forImages()
        val request = Request.Builder().url(allowed.url("/api/assets/photo/thumbnail")).build()
        publish(configuration())
        allowed.enqueue(MockResponse().setBody("image-one"))
        http.newCall(request).execute().close()
        assertEquals(key, allowed.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("x-api-key"))
        publish(configuration(apiKey = "rotated-key"))
        allowed.enqueue(MockResponse().setBody("image-two"))
        http.newCall(request).execute().close()
        assertEquals("rotated-key", allowed.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("x-api-key"))
        publish(configuration(forbidden, "new-origin-key"))
        assertThrows(IOException::class.java) { http.newCall(request).execute().close() }
        forbidden.enqueue(MockResponse().setBody("new-server-image"))
        http.newCall(Request.Builder().url(forbidden.url("/api/assets/photo/thumbnail")).build()).execute().close()
        assertEquals("new-origin-key", forbidden.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("x-api-key"))
        publish(ApiClientConfig("", "", false, false))
        assertThrows(IOException::class.java) {
            http.newCall(Request.Builder().url(forbidden.url("/api/assets/photo/thumbnail")).build()).execute().close()
        }
        assertEquals(2, allowed.requestCount)
        assertEquals(1, forbidden.requestCount)
    }

    @Test fun imageClientRejectsRedirectsAndForbiddenDns() {
        publish(configuration())
        val lookups = AtomicInteger()
        val http = ApiClientFactory.forImages().newBuilder().dns(Dns { host ->
            lookups.incrementAndGet()
            Dns.SYSTEM.lookup(host)
        }).build()
        assertFalse(http.followRedirects)
        assertFalse(http.followSslRedirects)
        assertThrows(IOException::class.java) {
            http.newCall(Request.Builder().url("http://forbidden.invalid/api/assets").build()).execute().close()
        }
        assertEquals(0, lookups.get())
        allowed.enqueue(MockResponse().setResponseCode(302).setHeader("Location", forbidden.url("/api/image")))
        http.newCall(Request.Builder().url(allowed.url("/api/image")).build()).execute().use { assertEquals(302, it.code) }
        assertEquals(0, forbidden.requestCount)
    }

    @Test fun connectionStringNeverDisclosesOriginOrKey() {
        val config = configuration()
        assertFalse(config.toString().contains(config.apiKey))
        assertFalse(config.toString().contains(config.hostName))
    }

}
