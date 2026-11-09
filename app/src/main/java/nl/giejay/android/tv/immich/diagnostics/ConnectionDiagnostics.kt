package nl.giejay.android.tv.immich.diagnostics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.ApiClientFactory
import nl.giejay.android.tv.immich.api.ServerPolicy
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

fun interface DiagnosticProbe {
    suspend fun check(step: DiagnosticStep, session: ApiClientConfig): DiagnosticResult
}

/** Foreground, read-only probes. All requests use the production session/origin guard.
 * Bodies are bounded, parsed off the main thread, and discarded without logging.
 */
class ConnectionDiagnostics internal constructor(
    private val clientFactory: (ApiClientConfig) -> OkHttpClient,
    private val currentSession: () -> ApiClientConfig,
    private val nanoTime: () -> Long
) : DiagnosticProbe {
    constructor() : this(ApiClientFactory::forDiagnosticSession, PreferenceManager::connectionSnapshot, System::nanoTime)

    override suspend fun check(step: DiagnosticStep, session: ApiClientConfig): DiagnosticResult {
        if (session !== currentSession()) return DiagnosticResult(step, DiagnosticStatus.SESSION_EXPIRED)
        val base = try {
            if (!ServerPolicy.isValidApiKey(session.apiKey)) throw IllegalArgumentException()
            ServerPolicy.apiBase(session.hostName)
        } catch (_: IllegalArgumentException) {
            return DiagnosticResult(step, DiagnosticStatus.INVALID_CONFIGURATION)
        }
        val path = when (step) {
            DiagnosticStep.PING -> "server/ping"
            DiagnosticStep.VERSION -> "server/version"
            DiagnosticStep.METADATA -> "search/metadata"
            DiagnosticStep.PEOPLE -> "people"
            else -> return DiagnosticResult(step, DiagnosticStatus.NOT_TESTED)
        }
        val url = base.newBuilder().addPathSegments(path).apply {
            if (step == DiagnosticStep.PEOPLE) addQueryParameter("page", "1").addQueryParameter("size", "1")
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (step == DiagnosticStep.METADATA) post(METADATA_BODY.toRequestBody("application/json".toMediaType()))
        }.build()
        val start = nanoTime()
        val call = clientFactory(session).newCall(request)
        return suspendCancellableCoroutine { continuation ->
            // Remains attached while the callback consumes the body, not just until headers arrive.
            continuation.invokeOnCancellation { call.cancel() }
            fun deliver(result: DiagnosticResult) {
                val safeResult = if (session === currentSession()) result else
                    DiagnosticResult(step, DiagnosticStatus.SESSION_EXPIRED)
                if (continuation.isActive) continuation.resume(safeResult)
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    deliver(DiagnosticResult(step, classifyFailure(e), elapsed(start)))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { parseResponse(step, it).copy(elapsedMillis = elapsed(start)) }
                    } catch (error: IOException) {
                        DiagnosticResult(step, classifyFailure(error), elapsed(start))
                    } catch (_: Exception) {
                        DiagnosticResult(step, DiagnosticStatus.UNEXPECTED_RESPONSE, elapsed(start))
                    }
                    deliver(result)
                }
            })
        }
    }

    private fun elapsed(start: Long): Long = ((nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)

    private fun parseResponse(step: DiagnosticStep, response: Response): DiagnosticResult {
        // Do not read or retain error/redirect bodies, including proxy error pages.
        if (response.code != 200) {
            val status = if (step == DiagnosticStep.METADATA && response.code == 400)
                DiagnosticStatus.REQUEST_CONTRACT_REJECTED else statusForCode(response.code)
            return DiagnosticResult(step, status)
        }
        val body = response.body ?: return DiagnosticResult(step, DiagnosticStatus.UNEXPECTED_RESPONSE)
        val type = body.contentType()
        if (type == null || type.type != "application" || (type.subtype != "json" && !type.subtype.endsWith("+json"))) {
            return DiagnosticResult(step, DiagnosticStatus.UNEXPECTED_RESPONSE)
        }
        if (body.contentLength() > MAX_BODY_BYTES) return DiagnosticResult(step, DiagnosticStatus.UNEXPECTED_RESPONSE)
        val source = body.source()
        if (source.request(MAX_BODY_BYTES + 1L)) return DiagnosticResult(step, DiagnosticStatus.UNEXPECTED_RESPONSE)
        val json = JsonParser.parseString(source.readUtf8())
        if (!json.isJsonObject) return DiagnosticResult(step, DiagnosticStatus.UNEXPECTED_RESPONSE)
        val objectValue = json.asJsonObject
        val version = if (step == DiagnosticStep.VERSION) parseVersion(objectValue) else null
        val valid = when (step) {
            DiagnosticStep.PING -> objectValue.get("res")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "pong" } == true
            DiagnosticStep.VERSION -> version != null
            DiagnosticStep.METADATA -> objectValue.get("assets")?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("items")?.let { it.isJsonArray && it.asJsonArray.size() <= 1 } == true
            DiagnosticStep.PEOPLE -> objectValue.get("people")?.let { it.isJsonArray && it.asJsonArray.size() <= 1 } == true
            else -> false
        }
        return DiagnosticResult(step, if (valid) DiagnosticStatus.PASSED else DiagnosticStatus.UNEXPECTED_RESPONSE, version = version)
    }

    private fun parseVersion(value: JsonObject): DiagnosticVersion? {
        fun part(name: String): Int? = value.get(name)?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString.matches(Regex("[0-9]{1,5}"))
        }?.asInt
        return DiagnosticVersion(part("major") ?: return null, part("minor") ?: return null, part("patch") ?: return null)
    }

    companion object {
        internal const val MAX_BODY_BYTES = 65_536L
        // Verified against immich-app/immich v3.3.5 server/src/dtos/search.dto.ts.
        // Do not mix structured filter/orderBy with deprecated flat page/visibility fields.
        // A one-item diagnostic does not need a cursor or optional expansion fields.
        internal const val METADATA_BODY = "{\"filter\":{\"visibility\":{\"eq\":\"timeline\"}},\"orderBy\":{\"field\":\"fileCreatedAt\",\"direction\":\"desc\"},\"size\":1}"
        internal fun statusForCode(code: Int) = when (code) {
            in 300..399 -> DiagnosticStatus.REDIRECT_BLOCKED
            400 -> DiagnosticStatus.BAD_REQUEST
            401 -> DiagnosticStatus.UNAUTHORIZED
            403 -> DiagnosticStatus.FORBIDDEN
            404 -> DiagnosticStatus.NOT_FOUND
            in 500..599 -> DiagnosticStatus.SERVER_ERROR
            else -> DiagnosticStatus.HTTP_ERROR
        }
        internal fun classifyFailure(error: IOException) = when (error) {
            is UnknownHostException -> DiagnosticStatus.DNS_FAILURE
            is SSLException -> DiagnosticStatus.CERTIFICATE_FAILURE
            is InterruptedIOException -> DiagnosticStatus.TIMEOUT
            is ConnectException, is NoRouteToHostException -> DiagnosticStatus.CONNECTION_FAILURE
            else -> DiagnosticStatus.NETWORK_FAILURE
        }
    }
}
