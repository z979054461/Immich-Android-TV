package nl.giejay.android.tv.immich.api

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

/** All playback, preview and screensaver traffic shares the same fail-closed origin guard. */
object PrivateMediaDataSource {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun factory(): DataSource.Factory = OkHttpDataSource.Factory(
        ApiClientFactory.forSession(ApiClientConfig.fromPrefs())
    )
}
