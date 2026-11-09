package nl.giejay.android.tv.immich.update

import com.google.gson.JsonParser
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class UpdateCandidate(
    val versionName: String,
    val versionCode: Long,
    val url: String,
    val size: Long,
    val sha256: String
)

/** Manual-only public release transport. Never accepts an account, token, server or photo URL. */
internal class UpdateRepository internal constructor(private val calls: Call.Factory) {
    constructor() : this(OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build())

    private val cancelled = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val active = AtomicReference<Call?>()

    /** Terminal cancellation; a future explicit user action must create a fresh repository. */
    fun cancel() {
        cancelled.set(true)
        active.getAndSet(null)?.cancel()
        (calls as? OkHttpClient)?.connectionPool?.evictAll()
    }

    fun check(currentCode: Long): UpdateCandidate? = operation {
        response(RELEASES, false) { result ->
            val bytes = boundedBytes(result, MAX_JSON)
            parseCandidates(bytes.toString(Charsets.UTF_8), currentCode)
        }
    }

    /** The caller supplies an app-private, previously nonexistent staging path. */
    fun download(candidate: UpdateCandidate, file: File) = operation {
        requireCandidate(candidate)
        if (!file.createNewFile()) fail()
        try {
            response(candidate.url, true) { result ->
                val body = result.body ?: fail()
                val announced = body.contentLength()
                if (announced != -1L && announced != candidate.size) fail()
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > candidate.size || count > MAX_APK) fail()
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                if (count != candidate.size || hex(digest.digest()) != candidate.sha256) fail()
                ensureActive()
            }
        } catch (_: Exception) {
            file.delete()
            fail()
        }
    }

    private fun <T> operation(block: () -> T): T {
        if (!busy.compareAndSet(false, true)) fail()
        try {
            ensureActive()
            return block()
        } catch (_: Exception) {
            // Never propagate response bodies, redirect URLs, signed query strings or stack details to UI.
            throw IOException("Update could not be verified or completed")
        } finally {
            active.getAndSet(null)?.cancel()
            busy.set(false)
        }
    }

    private fun ensureActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) fail()
    }

    private fun <T> response(start: String, asset: Boolean, consume: (Response) -> T): T {
        var url = start.toHttpUrlOrNull() ?: fail()
        if (asset) requireAssetUrl(url) else if (url.toString() != RELEASES) fail()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        for (hop in 0..MAX_REDIRECTS) {
            ensureActive()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail()
            val request = Request.Builder().url(url)
                .header("Accept", if (asset) "application/octet-stream" else "application/vnd.github+json")
                .header("User-Agent", "ImmichTV-Private-Updater")
                .build()
            val call = calls.newCall(request)
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            active.set(call)
            if (cancelled.get()) { call.cancel(); fail() }
            call.execute().use { result ->
                ensureActive()
                if (result.code in REDIRECTS) {
                    if (!asset || hop == MAX_REDIRECTS) fail()
                    val next = result.header("Location")?.let(url::resolve) ?: fail()
                    requireRedirect(url, next)
                    url = next
                } else {
                    if (result.code != 200) fail()
                    return consume(result)
                }
            }
            active.compareAndSet(call, null)
        }
        fail()
    }

    private fun boundedBytes(response: Response, maximum: Long): ByteArray {
        val body = response.body ?: fail()
        if (body.contentLength() > maximum) fail()
        val output = java.io.ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size().toLong() + read > maximum) fail()
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    internal companion object {
        const val RELEASES = "https://api.github.com/repos/z979054461/Immich-Android-TV/releases?per_page=100"
        const val MAX_JSON = 1024L * 1024
        const val MAX_APK = 150L * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val VERSION = Regex("^(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]?)\\.(0|[1-9][0-9]?)-private$")
        private val DIGEST = Regex("^[0-9a-f]{64}$")
        private const val ASSET_PREFIX = "/z979054461/Immich-Android-TV/releases/download/"

        fun versionCode(name: String): Long? {
            val match = VERSION.matchEntire(name) ?: return null
            val (major, minor, patch) = match.destructured
            return major.toLong() * 10000 + minor.toLong() * 100 + patch.toLong()
        }

        fun parseCandidates(json: String, currentCode: Long): UpdateCandidate? {
            if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON) fail()
            val releases = try { JsonParser.parseString(json) } catch (_: Exception) { fail() }
            if (!releases.isJsonArray || releases.asJsonArray.size() > 100) fail()
            return releases.asJsonArray.mapNotNull { entry ->
                runCatching {
                    val release = entry.asJsonObject
                    if (!release.has("draft") || release["draft"].asBoolean) return@runCatching null
                    val tag = release["tag_name"].asString
                    if (!tag.startsWith("v")) return@runCatching null
                    val name = tag.substring(1)
                    val code = versionCode(name) ?: return@runCatching null
                    if (code <= currentCode) return@runCatching null
                    val apks = release["assets"].asJsonArray.filter {
                        it.asJsonObject["name"].asString.endsWith(".apk", ignoreCase = true)
                    }
                    if (apks.size != 1) return@runCatching null
                    val asset = apks.single().asJsonObject
                    if (asset["state"]?.asString != "uploaded") return@runCatching null
                    val digest = asset["digest"].asString
                    if (!digest.startsWith("sha256:")) return@runCatching null
                    val candidate = UpdateCandidate(name, code, asset["browser_download_url"].asString,
                        asset["size"].asLong, digest.removePrefix("sha256:"))
                    requireCandidate(candidate)
                    val url = candidate.url.toHttpUrlOrNull() ?: fail()
                    if (url.pathSegments.last() != asset["name"].asString) fail()
                    candidate
                }.getOrNull()
            }.maxByOrNull { it.versionCode }
        }

        fun requireCandidate(candidate: UpdateCandidate) {
            if (versionCode(candidate.versionName) != candidate.versionCode ||
                candidate.size !in 1..MAX_APK || !DIGEST.matches(candidate.sha256)) fail()
            val url = candidate.url.toHttpUrlOrNull() ?: fail()
            requireAssetUrl(url)
            if (url.pathSegments[4] != "v${candidate.versionName}") fail()
        }

        private fun requireSecure(url: HttpUrl) {
            if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() ||
                url.password.isNotEmpty() || url.fragment != null) fail()
        }

        fun requireAssetUrl(url: HttpUrl) {
            requireSecure(url)
            if (url.host != "github.com" || url.query != null ||
                !url.encodedPath.startsWith(ASSET_PREFIX) || url.pathSegments.size != 6 ||
                !url.pathSegments.last().matches(Regex("[A-Za-z0-9._-]+\\.apk"))) fail()
        }

        fun requireRedirect(from: HttpUrl, to: HttpUrl) {
            requireSecure(to)
            // GitHub release downloads use this exact host, never arbitrary githubusercontent subdomains.
            if (from.host !in setOf("github.com", "release-assets.githubusercontent.com") ||
                to.host != "release-assets.githubusercontent.com" ||
                !to.encodedPath.startsWith("/github-production-release-asset/") ||
                to.pathSegments.size != 3 || to.pathSegments.any { it.isBlank() }) fail()
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun fail(): Nothing = throw IOException("Update could not be verified or completed")
    }
}
