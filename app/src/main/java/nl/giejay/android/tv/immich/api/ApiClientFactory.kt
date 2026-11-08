package nl.giejay.android.tv.immich.api

import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Dispatcher
import java.io.IOException

object ApiClientFactory {
    // All profile media/API work shares one cancellation boundary.
    private val dispatcher = Dispatcher()
    fun cancelAll() = dispatcher.cancelAll()

    /** Referential identity prevents an old session reviving when the same user unlocks again. */
    fun forSession(config: ApiClientConfig): OkHttpClient = getClient(false, config.apiKey, false, config.hostName)
        .newBuilder().addInterceptor { chain ->
            if (config !== PreferenceManager.connectionSnapshot()) throw IOException("Session locked or changed")
            val response = chain.proceed(chain.request())
            if (config !== PreferenceManager.connectionSnapshot()) {
                response.close()
                throw IOException("Session locked or changed")
            }
            response
        }.build()

    /** Glide is process-global: read exactly one current connection for each image request. */
    fun forImages(): OkHttpClient = OkHttpClient.Builder().dispatcher(dispatcher)
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            val config = PreferenceManager.connectionSnapshot()
            ServerPolicy.requireAllowed(config.hostName, chain.request().url)
            if (!ServerPolicy.isValidApiKey(config.apiKey)) throw IOException("Unlock a profile first")
            val response = chain.proceed(chain.request().newBuilder().header("x-api-key", config.apiKey).build())
            if (config !== PreferenceManager.connectionSnapshot()) {
                response.close()
                throw IOException("Session locked or changed")
            }
            response
        }.build()

    // Low-level factory is also used by isolated policy tests, never by UI/media consumers.
    @Suppress("UNUSED_PARAMETER")
    fun getClient(disableSsl: Boolean, apiKey: String, debugMode: Boolean,
                  server: String): OkHttpClient =
        OkHttpClient.Builder().dispatcher(dispatcher)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(Interceptor { chain ->
                ServerPolicy.requireAllowed(server, chain.request().url)
                if (!ServerPolicy.isValidApiKey(apiKey)) throw IOException("A valid Immich API key is required")
                chain.proceed(chain.request().newBuilder().header("x-api-key", apiKey).build())
            })
            .build()
}
