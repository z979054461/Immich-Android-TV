package nl.giejay.android.tv.immich.api

import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** One guarded HTTP client/pool per immutable unlocked thumbnail session, not per image. */
internal class ThumbnailNetworkSession(config: ApiClientConfig) {
    private val monitor = Any()
    private var connection: ApiClientConfig? = config
    private var client: OkHttpClient? = ApiClientFactory.forSession(config)
    private val calls = mutableSetOf<Pending>()

    internal class Pending(val call: Call, val pool: ConnectionPool)

    fun matches(config: ApiClientConfig): Boolean = synchronized(monitor) {
        connection === config && client != null
    }

    fun newCall(request: Request): Pending = synchronized(monitor) {
        val current = connection
        val transport = client
        if (current == null || transport == null || current !== PreferenceManager.connectionSnapshot()) {
            throw IOException("Thumbnail session locked or changed")
        }
        Pending(transport.newCall(request), transport.connectionPool).also { calls.add(it) }
    }

    /** Called only after the response body is closed (or execution failed). */
    fun finished(pending: Pending) {
        val revoked = synchronized(monitor) { calls.remove(pending); client == null }
        if (revoked) {
            pending.call.cancel()
            // evictAll only removes idle sockets. A body closing after revoke may have just
            // released another socket, so evict again without retaining the old client.
            pending.pool.evictAll()
        }
    }

    fun revoke() {
        val retired: OkHttpClient?
        val pending: List<Pending>
        synchronized(monitor) {
            retired = client
            client = null
            connection = null
            pending = calls.toList()
            calls.clear()
        }
        pending.forEach { it.call.cancel() }
        retired?.connectionPool?.evictAll()
        // Do not shut down the factory's dispatcher: API/video clients share it and keep
        // their existing timeout policies and their normal global cancellation boundary.
    }

    override fun toString() = "ThumbnailNetworkSession(redacted)"
}
