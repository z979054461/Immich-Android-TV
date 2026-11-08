package nl.giejay.android.tv.immich.shared.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import nl.giejay.android.tv.immich.security.ApiKeyCipher
import nl.giejay.android.tv.immich.security.ProfileStore
import nl.giejay.android.tv.immich.api.ApiClientFactory
import nl.giejay.android.tv.immich.slider.FavoriteButtonControllerPlugin
import nl.giejay.android.tv.immich.slider.FavoriteService
import nl.giejay.mediaslider.adapter.AlignOption
import nl.giejay.mediaslider.adapter.MetaDataClock
import nl.giejay.mediaslider.adapter.MetaDataItem
import nl.giejay.mediaslider.adapter.MetaDataMediaCount
import nl.giejay.mediaslider.adapter.MetaDataSliderItem
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.plugin.DateOverlayViewPlugin
import nl.giejay.mediaslider.plugin.MediaRemoteControlsKeyEventPlugin
import nl.giejay.mediaslider.plugin.MetadataViewPlugin
import nl.giejay.mediaslider.plugin.SliderControllerPlugin
import nl.giejay.mediaslider.plugin.SliderKeyEventPlugin
import nl.giejay.mediaslider.plugin.SliderViewPlugin
import nl.giejay.mediaslider.util.MetaDataConverter
import nl.giejay.android.tv.immich.api.ServerPolicy
import nl.giejay.android.tv.immich.api.ApiClientConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.reflect.KClass

enum class MetaDataScreen {
    SCREENSAVER, VIEWER
}

/** Default Immich slider plugins with a shared [MetadataViewPlugin] instance. */
data class EnabledSliderPlugins(
    val controllerPlugins: List<SliderControllerPlugin>,
    val viewPlugins: List<SliderViewPlugin<*>>,
    val keyEventPlugins: List<SliderKeyEventPlugin>
)

object PreferenceManager {
    lateinit var sharedPreference: SharedPreferences
    private lateinit var liveSharedPreferences: LiveSharedPreferences

    @Volatile private var activeConnection = ApiClientConfig("", "", false, false)

    lateinit var profiles: ProfileStore
        private set
    var activeProfileId: String? = null
        private set

    fun connectionSnapshot(): ApiClientConfig = activeConnection

    // stores the typed values, not the internal PrefTypes (String, Int)
    private val liveContext: MutableMap<String, Any?> = mutableMapOf()

    fun <T : Any> subclasses(clazz: KClass<T>): List<KClass<out T>> {
        return clazz.sealedSubclasses.flatMap { subclasses(it) + it }
    }

    fun init(context: Context) {
        sharedPreference = PreferenceManager.getDefaultSharedPreferences(context)
        liveSharedPreferences = LiveSharedPreferences(sharedPreference)
        // One-time upgrade: require key re-entry and PIN enrollment, never let a visitor
        // choose a PIN for an existing account merely by opening the app.
        if (sharedPreference.contains(API_KEY.key())) {
            check(sharedPreference.edit().remove(API_KEY.key()).remove(HOST_NAME.key())
                .putBoolean("profile_upgrade_required", true).commit())
        }
        profiles = ProfileStore(context.getSharedPreferences("private_profiles", Context.MODE_PRIVATE))
        lock()
        subclasses(Pref::class).filter { it.objectInstance != null }.forEach { pref ->
            val prefInstance = pref.objectInstance!! as Pref<Any, *, *>
            liveSharedPreferences.subscribeTyped(prefInstance) { typeValue ->
                liveContext[prefInstance.key()] = typeValue
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: Pref<T, *, *>): T {
        if (key.key() == API_KEY.key()) return activeConnection.apiKey as T
        if (key.key() == HOST_NAME.key()) return activeConnection.hostName as T
        return liveContext[key.key()] as T ?: key.getValue(sharedPreference)
    }

    val hostName: String
        get() = activeConnection.hostName.trim().removeSuffix("/")

    fun <T, PREFTYPE> save(key: Pref<T, *, PREFTYPE>, value: T) {
        require(key.key() != API_KEY.key() && key.key() != HOST_NAME.key()) { "Use atomic connection setup" }
        key.save(sharedPreference, value)
        liveContext[key.key()] = value
    }

    fun <T> subscribe(key: Pref<T, *, *>, onChange: (T) -> Unit) {
        liveSharedPreferences.subscribeTyped(key, onChange)
    }

    fun subscribeMultiple(keys: List<Pref<*, *, *>>, onChange: (Map<String, Any?>) -> Unit) {
        liveSharedPreferences.subscribeMultiple(keys, onChange)
    }

    fun isLoggedId(): Boolean {
        val connection = activeConnection
        return isValid(connection.hostName, connection.apiKey)
    }

    fun isValid(hostName: String?, apiKey: String?): Boolean {
        return hostName != null && apiKey != null && ServerPolicy.isValidApiKey(apiKey) && runCatching { ServerPolicy.normalizeServer(hostName) }.isSuccess
    }

    /** Lock invalidates every old immutable session, even when the same profile unlocks again. */
    @Synchronized fun lock() {
        activeConnection = ApiClientConfig("", "", false, false)
        activeProfileId = null
        liveContext[HOST_NAME.key()] = ""
        liveContext[API_KEY.key()] = ""
        ApiClientFactory.cancelAll()
        nl.giejay.android.tv.immich.shared.util.AssetDetailCache.clearSession()
    }

    @Synchronized fun activateProfile(id: String, connection: ApiClientConfig) {
        require(isValid(connection.hostName, connection.apiKey))
        lock()
        // Never reuse account-specific album choices between users.
        save(SCREENSAVER_ALBUMS, emptySet())
        activeProfileId = id
        activeConnection = connection
    }

    @Synchronized fun removeApiSettings() {
        val id = activeProfileId
        lock()
        if (id != null) profiles.remove(id)
    }

    private fun removeStringSetItem(item: String, prefKey: StringSetPref) {
        save(prefKey, get(prefKey).filter { it != item }.toSet())
    }

    private fun addStringSetItem(item: String, prefKey: StringSetPref) {
        save(prefKey, get(prefKey) + item)
    }

    fun toggleStringSetItem(name: String, prefKey: StringSetPref) {
        if (get(prefKey).contains(name)) {
            removeStringSetItem(name, prefKey)
        } else {
            addStringSetItem(name, prefKey)
        }
    }

    fun itemInStringSet(name: String?, prefKey: StringSetPref): Boolean {
        return name?.let { get(prefKey).contains(it) } ?: false
    }

    fun getString(key: String, default: String): String {
        return sharedPreference.getString(key, default)!!
    }

    fun getViewMetaDataFromOldPrefs(): List<MetaDataItem> {
        val metaData: MutableList<MetaDataItem> = mutableListOf()
        if (get(SLIDER_SHOW_DESCRIPTION)) {
            metaData.add(MetaDataSliderItem(MetaDataType.DESCRIPTION, AlignOption.RIGHT))
//            metaData.add(MetaDataSliderItem(MetaDataType.ALBUM_NAME, AlignOption.RIGHT))
        }
        if (get(SLIDER_SHOW_CITY)) {
            metaData.add(MetaDataSliderItem(MetaDataType.CITY, AlignOption.RIGHT))
            metaData.add(MetaDataSliderItem(MetaDataType.COUNTRY, AlignOption.RIGHT))
        }
        // todo add toggles

        if (get(SLIDER_SHOW_DATE)) {
            metaData.add(MetaDataSliderItem(MetaDataType.DATE, AlignOption.RIGHT))
        }

        if (get(SLIDER_SHOW_MEDIA_COUNT)) {
            metaData.add(MetaDataMediaCount(AlignOption.RIGHT))
        }
        return metaData
    }

    fun getScreenSaverMetaDataFromOldPrefs(): List<MetaDataItem> {
        val metaData: MutableList<MetaDataItem> = mutableListOf()
        if (get(SCREENSAVER_SHOW_CLOCK)) {
            metaData.add(MetaDataClock(AlignOption.RIGHT))
        }
        if (get(SCREENSAVER_SHOW_DESCRIPTION)) {
            metaData.add(MetaDataSliderItem(MetaDataType.DESCRIPTION, AlignOption.RIGHT))
            metaData.add(MetaDataSliderItem(MetaDataType.CITY, AlignOption.RIGHT))
            metaData.add(MetaDataSliderItem(MetaDataType.COUNTRY, AlignOption.RIGHT))
        }

        if (get(SCREENSAVER_SHOW_ALBUM_NAME)) {
            metaData.add(MetaDataSliderItem(MetaDataType.ALBUM_NAME, AlignOption.RIGHT))
        }
        if (get(SCREENSAVER_SHOW_DATE)) {
            metaData.add(MetaDataSliderItem(MetaDataType.DATE, AlignOption.RIGHT))
        }

        return metaData
    }

    fun saveMetaData(align: AlignOption, metaDataScreen: MetaDataScreen, metaData: List<MetaDataItem>) {
        sharedPreference.edit().putString(createKey(metaDataScreen, align), MetaDataConverter.metaDataListToJson(metaData)).apply()
    }

    private fun createKey(metaDataScreen: MetaDataScreen, align: AlignOption) = "meta_data_${metaDataScreen}_${align}"

    fun getMetaData(align: AlignOption, metaDataScreen: MetaDataScreen): List<MetaDataItem> {
        return MetaDataConverter.metaDataListFromJson(sharedPreference.getString(createKey(metaDataScreen, align), "[]")!!)
    }

    fun getAllMetaData(metaDataScreen: MetaDataScreen): List<MetaDataItem> {
        return getMetaData(AlignOption.RIGHT, metaDataScreen) + getMetaData(AlignOption.LEFT, metaDataScreen)
    }

    fun hasMetaDataForScreen(metaDataScreen: MetaDataScreen, align: AlignOption): Boolean {
        return sharedPreference.contains(createKey(metaDataScreen, align))
    }

    /**
     * Builds a fresh set of Immich slider plugins for one slider session.
     * [MetadataViewPlugin] is shared across view + controller lists.
     * [DateOverlayViewPlugin] optionally renders DATE at the top of the slider.
     * [MediaRemoteControlsKeyEventPlugin] handles remote/D-pad seek and related keys.
     */
    fun createEnabledSliderPlugins(scope: CoroutineScope, favoriteService: FavoriteService): EnabledSliderPlugins {
        val metadataPlugin = MetadataViewPlugin()
        val remoteControlsPlugin = MediaRemoteControlsKeyEventPlugin()
        return EnabledSliderPlugins(
            controllerPlugins = listOf(
                FavoriteButtonControllerPlugin(favoriteService, scope),
                metadataPlugin
            ),
            viewPlugins = listOf(metadataPlugin, DateOverlayViewPlugin()),
            keyEventPlugins = listOf(remoteControlsPlugin)
        )
    }

}