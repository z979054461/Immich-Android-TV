package nl.giejay.android.tv.immich.api

import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException

object ApiClientFactory {
    /** Immutable API/video sessions cannot send a key after server change or logout. */
    fun forSession(config: ApiClientConfig): OkHttpClient = getClient(false, config.apiKey, false, config.hostName)
        .newBuilder().addInterceptor { chain ->
            if (config != PreferenceManager.connectionSnapshot()) throw IOException("Connection settings changed; reopen this screen")
            chain.proceed(chain.request())
        }.build()

    /** Glide is process-global: read exactly one current connection for each image request. */
    fun forImages(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            val config = PreferenceManager.connectionSnapshot()
            ServerPolicy.requireAllowed(config.hostName, chain.request().url)
            if (!ServerPolicy.isValidApiKey(config.apiKey)) throw IOException("A valid Immich API key is required")
            chain.proceed(chain.request().newBuilder().header("x-api-key", config.apiKey).build())
        }.build()

    // Legacy switches are intentionally ignored: certificates are always verified, bodies never logged.
    @Suppress("UNUSED_PARAMETER")
    fun getClient(disableSsl: Boolean, apiKey: String, debugMode: Boolean,
                  server: String): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(Interceptor { chain ->
                ServerPolicy.requireAllowed(server, chain.request().url)
                if (!ServerPolicy.isValidApiKey(apiKey)) throw IOException("A valid Immich API key is required")
                chain.proceed(chain.request().newBuilder().header("x-api-key", apiKey).build())
            })
            .build()
}
