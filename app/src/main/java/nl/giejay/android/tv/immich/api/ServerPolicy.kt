package nl.giejay.android.tv.immich.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Runtime requests are restricted to the configured Immich origin and API path. */
object ServerPolicy {
    fun normalizeServer(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty() && value.none { it.isWhitespace() || it.code < 32 || it.code == 127 }) { "Enter a valid server address" }
        val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Use an http or https server address")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Server address cannot contain credentials, query or fragment" }
        var path = url.encodedPath.trimEnd('/')
        if (path.endsWith("/api")) path = path.removeSuffix("/api")
        return url.newBuilder().encodedPath(if (path.isEmpty()) "/" else path).build().toString().trimEnd('/')
    }

    fun isValidApiKey(value: String): Boolean = value.isNotBlank() && value.all { it.code in 33..126 }

    fun apiBase(server: String): HttpUrl = (normalizeServer(server) + "/api/").toHttpUrl()

    fun requireAllowed(server: String, target: HttpUrl) {
        val base = try { apiBase(server) } catch (_: IllegalArgumentException) {
            throw IOException("Configure your Immich server first")
        }
        if (target.scheme != base.scheme || target.host != base.host || target.port != base.port ||
            target.username.isNotEmpty() || target.password.isNotEmpty() ||
            !target.encodedPath.startsWith(base.encodedPath) || target.fragment != null ||
            target.queryParameterNames.any { it.equals("apiKey", true) || it.equals("api_key", true) }) {
            throw IOException("Blocked request outside the configured Immich API")
        }
    }
}
