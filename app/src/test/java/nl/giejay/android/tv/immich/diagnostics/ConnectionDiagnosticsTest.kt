package nl.giejay.android.tv.immich.diagnostics

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.ApiClientFactory
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

/** Real loopback transport with synthetic metadata and credentials only. */
class ConnectionDiagnosticsTest {
    private lateinit var allowed: MockWebServer
    private lateinit var forbidden: MockWebServer
    private lateinit var original: ApiClientConfig
    private lateinit var session: ApiClientConfig
    private val key = "synthetic-diagnostic-key"
    private fun publish(value: ApiClientConfig) {
        PreferenceManager.javaClass.getDeclaredField("activeConnection").apply { isAccessible = true }.set(PreferenceManager, value)
    }
    @Before fun setup() {
        original = PreferenceManager.connectionSnapshot()
        allowed = MockWebServer().apply { start() }
        forbidden = MockWebServer().apply { start() }
        session = ApiClientConfig(allowed.url("/PhotoLibrary").toString(), key, false, false)
        publish(session)
    }
    @After fun cleanup() {
        publish(original)
        ApiClientFactory.cancelAll()
        allowed.shutdown()
        forbidden.shutdown()
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun probesUseOnlyGuardedReadOnlyBoundedEndpointsAndNeverReturnMetadata() = runBlocking {
        val probe = ConnectionDiagnostics()
        val responses = listOf(
            DiagnosticStep.PING to """{"res":"pong"}""",
            DiagnosticStep.VERSION to """{"major":3,"minor":3,"patch":5}""",
            DiagnosticStep.METADATA to """{"assets":{"items":[{"id":"synthetic-photo","originalFileName":"private-file"}]}}""",
            DiagnosticStep.PEOPLE to """{"people":[{"name":"private-person"}]}"""
        )
        responses.forEach { (step, body) ->
            allowed.enqueue(json(body))
            val result = probe.check(step, session)
            assertEquals(DiagnosticStatus.PASSED, result.status)
            assertNotNull(result.elapsedMillis)
            listOf(key, "private-file", "private-person", "synthetic-photo", "PhotoLibrary").forEach {
                assertFalse(result.toString().contains(it))
            }
            if (step == DiagnosticStep.VERSION) assertEquals(DiagnosticVersion(3, 3, 5), result.version)
            val request = allowed.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(listOf(key), request.headers.values("x-api-key"))
            assertFalse(request.path!!.contains(key))
            when (step) {
                DiagnosticStep.PING -> assertEquals("/PhotoLibrary/api/server/ping", request.path)
                DiagnosticStep.VERSION -> assertEquals("/PhotoLibrary/api/server/version", request.path)
                DiagnosticStep.PEOPLE -> assertEquals("/PhotoLibrary/api/people?page=1&size=1", request.path)
                DiagnosticStep.METADATA -> {
                    assertEquals("/PhotoLibrary/api/search/metadata", request.path)
                    assertEquals("POST", request.method)
                    val bodyJson = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                    // v3.3.5 MetadataSearchDto structured shape; no legacy-field mixing.
                    assertEquals(setOf("filter", "orderBy", "size"), bodyJson.keySet())
                    assertEquals(1, bodyJson.get("size").asInt)
                    val filter = bodyJson.getAsJsonObject("filter")
                    assertEquals(setOf("visibility"), filter.keySet())
                    assertEquals(setOf("eq"), filter.getAsJsonObject("visibility").keySet())
                    assertEquals("timeline", filter.getAsJsonObject("visibility").get("eq").asString)
                    val order = bodyJson.getAsJsonObject("orderBy")
                    assertEquals(setOf("field", "direction"), order.keySet())
                    assertEquals("fileCreatedAt", order.get("field").asString)
                    assertEquals("desc", order.get("direction").asString)
                    listOf("page", "visibility", "withExif", "order", "cursor").forEach { assertFalse(bodyJson.has(it)) }
                }
                else -> fail("Unexpected request")
            }
            if (step != DiagnosticStep.METADATA) assertEquals("GET", request.method)
        }
        assertEquals(DiagnosticStatus.NOT_TESTED, probe.check(DiagnosticStep.PHOTO_VIEW, session).status)
        assertEquals(4, allowed.requestCount)
        assertEquals(0, forbidden.requestCount)
    }

    @Test fun emptyLibraryAndEmptyPeopleStillPass() = runBlocking {
        allowed.enqueue(json("""{"assets":{"items":[]}}"""))
        allowed.enqueue(json("""{"people":[]}"""))
        assertEquals(DiagnosticStatus.PASSED, ConnectionDiagnostics().check(DiagnosticStep.METADATA, session).status)
        assertEquals(DiagnosticStatus.PASSED, ConnectionDiagnostics().check(DiagnosticStep.PEOPLE, session).status)
    }

    @Test fun redirectsAreNeverFollowedEvenOnSameOrigin() = runBlocking {
        listOf(301, 302, 303, 307, 308).forEach { code ->
            allowed.enqueue(MockResponse().setResponseCode(code).setHeader("Location", forbidden.url("/api/steal")))
            assertEquals(DiagnosticStatus.REDIRECT_BLOCKED, ConnectionDiagnostics().check(DiagnosticStep.PING, session).status)
        }
        allowed.enqueue(MockResponse().setResponseCode(302).setHeader("Location", allowed.url("/PhotoLibrary/api/server/version")))
        assertEquals(DiagnosticStatus.REDIRECT_BLOCKED, ConnectionDiagnostics().check(DiagnosticStep.PING, session).status)
        assertEquals(6, allowed.requestCount)
        assertEquals(0, forbidden.requestCount)
    }

    @Test fun httpErrorsAreClassifiedWithoutReturningUntrustedBody() = runBlocking {
        val expected = mapOf(400 to DiagnosticStatus.REQUEST_CONTRACT_REJECTED, 401 to DiagnosticStatus.UNAUTHORIZED,
            403 to DiagnosticStatus.FORBIDDEN, 404 to DiagnosticStatus.NOT_FOUND,
            503 to DiagnosticStatus.SERVER_ERROR, 429 to DiagnosticStatus.HTTP_ERROR)
        expected.forEach { (code, status) ->
            allowed.enqueue(MockResponse().setResponseCode(code).setBody("$key private-person https://private.invalid"))
            val result = ConnectionDiagnostics().check(DiagnosticStep.METADATA, session)
            assertEquals(status, result.status)
            assertFalse(result.toString().contains(key))
            assertFalse(result.toString().contains("private"))
        }
    }

    @Test fun malformedDiagnosticRequestIsNotReportedAsMissingReadPermission() = runBlocking {
        allowed.enqueue(MockResponse().setResponseCode(400)
            .setBody("required permission: all; synthetic rejected request details"))
        val result = ConnectionDiagnostics().check(DiagnosticStep.METADATA, session)
        assertEquals(DiagnosticStatus.REQUEST_CONTRACT_REJECTED, result.status)
        assertNotEquals(DiagnosticStatus.FORBIDDEN, result.status)
        assertFalse(result.toString().contains("required permission"))
        assertEquals(1, allowed.requestCount) // Never retry with a different shape or wider access.
    }

    @Test fun rejectsHtmlInvalidJsonOversizedBodiesAndUnboundedVersionStrings() = runBlocking {
        val cases = listOf(
            MockResponse().setHeader("Content-Type", "text/html").setBody("<html>private-site</html>"),
            json("not-json"), json("[]"), json("""{"major":"https://private.invalid","minor":3,"patch":5}"""),
            json("""{"major":100000,"minor":3,"patch":5}"""),
            json("x".repeat(70_000)),
            MockResponse().setHeader("Content-Type", "application/json").setChunkedBody("x".repeat(70_000), 1024)
        )
        cases.forEach { response ->
            allowed.enqueue(response)
            assertEquals(DiagnosticStatus.UNEXPECTED_RESPONSE, ConnectionDiagnostics().check(DiagnosticStep.VERSION, session).status)
        }
    }

    @Test fun forbiddenOriginIsBlockedBeforeDnsAndTimeoutsRemainShort() {
        val lookups = AtomicInteger()
        val client = ApiClientFactory.forDiagnosticSession(session).newBuilder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups.incrementAndGet()
                return listOf(InetAddress.getLoopbackAddress())
            }
        }).build()
        assertEquals(5_000, client.connectTimeoutMillis)
        assertEquals(5_000, client.readTimeoutMillis)
        assertEquals(10_000, client.callTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("http://forbidden.invalid/api/server/ping").build()).execute().close()
        }
        assertEquals(0, lookups.get())
        assertEquals(0, allowed.requestCount)
        assertEquals(0, forbidden.requestCount)
    }

    @Test fun invalidAndExpiredSessionsNeverSendRequests() = runBlocking {
        val invalid = session.copy(hostName = "not-a-url")
        publish(invalid)
        assertEquals(DiagnosticStatus.INVALID_CONFIGURATION, ConnectionDiagnostics().check(DiagnosticStep.PING, invalid).status)
        publish(session.copy()) // Equal values, but a newly unlocked session.
        assertEquals(DiagnosticStatus.SESSION_EXPIRED, ConnectionDiagnostics().check(DiagnosticStep.PING, session).status)
        assertEquals(0, allowed.requestCount)
    }

    @Test fun sessionChangeWhileBodyIsReadDiscardsTheResponse() = runBlocking {
        allowed.enqueue(json("""{"major":3,"minor":3,"patch":5}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val pending = async(Dispatchers.Default) { ConnectionDiagnostics().check(DiagnosticStep.VERSION, session) }
        assertNotNull(allowed.takeRequest(2, TimeUnit.SECONDS))
        publish(session.copy())
        assertEquals(DiagnosticStatus.SESSION_EXPIRED, pending.await().status)
    }

    @Test fun cancellingACoroutineCancelsItsOwnCallWithoutGlobalCancellation() = runBlocking {
        val cancelled = CountDownLatch(1)
        val probe = ConnectionDiagnostics({ config ->
            ApiClientFactory.forDiagnosticSession(config).newBuilder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
        }, PreferenceManager::connectionSnapshot, System::nanoTime)
        allowed.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.Default) { probe.check(DiagnosticStep.PING, session) }
        assertNotNull(allowed.takeRequest(2, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertSame(session, PreferenceManager.connectionSnapshot())
    }

    @Test fun cancellationAfterResponseHeadersAlsoCancelsBodyConsumption() = runBlocking {
        val headers = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val probe = ConnectionDiagnostics({ config ->
            ApiClientFactory.forDiagnosticSession(config).newBuilder().eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: okhttp3.Response) { headers.countDown() }
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
        }, PreferenceManager::connectionSnapshot, System::nanoTime)
        allowed.enqueue(json("""{"res":"pong"}""").setBodyDelay(500, TimeUnit.MILLISECONDS))
        val pending = async(Dispatchers.Default) { probe.check(DiagnosticStep.PING, session) }
        assertTrue(headers.await(2, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertEquals(1, allowed.requestCount)
    }

    @Test fun networkDeadlineProducesOnlyATimeoutStatus() = runBlocking {
        val probe = ConnectionDiagnostics({ config ->
            ApiClientFactory.forDiagnosticSession(config).newBuilder().callTimeout(100, TimeUnit.MILLISECONDS).build()
        }, PreferenceManager::connectionSnapshot, System::nanoTime)
        allowed.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(DiagnosticStatus.TIMEOUT, probe.check(DiagnosticStep.PING, session).status)
    }

    @Test fun exceptionMessagesCannotEscapeClassification() {
        val secret = "$key https://private.invalid 192.0.2.2"
        assertEquals(DiagnosticStatus.DNS_FAILURE, ConnectionDiagnostics.classifyFailure(UnknownHostException(secret)))
        assertEquals(DiagnosticStatus.CERTIFICATE_FAILURE, ConnectionDiagnostics.classifyFailure(SSLHandshakeException(secret)))
        assertEquals(DiagnosticStatus.CONNECTION_FAILURE, ConnectionDiagnostics.classifyFailure(ConnectException(secret)))
        assertEquals(DiagnosticStatus.TIMEOUT, ConnectionDiagnostics.classifyFailure(InterruptedIOException(secret)))
        assertEquals(DiagnosticStatus.NETWORK_FAILURE, ConnectionDiagnostics.classifyFailure(IOException(secret)))
    }
}
