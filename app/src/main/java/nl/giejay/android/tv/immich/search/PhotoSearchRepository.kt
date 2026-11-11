package nl.giejay.android.tv.immich.search

import androidx.annotation.Keep
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import kotlinx.coroutines.CancellationException
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.ApiClientFactory
import nl.giejay.android.tv.immich.api.ServerPolicy
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Person
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

@Keep
internal data class FilterSearchResponse(val assets: FilterAssetPage?)
@JsonAdapter(FilterAssetPageAdapter::class)
@Keep
internal data class FilterAssetPage(val items: List<Asset>?, val nextCursor: String?)

/** Missing cursor is a bad/legacy response, not proof that the result set ended. */
@Keep
internal class FilterAssetPageAdapter : JsonDeserializer<FilterAssetPage> {
    override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): FilterAssetPage {
        val page = json.asJsonObject
        if (!page.has("nextCursor") || !page.has("items") || !page.get("items").isJsonArray) throw JsonParseException("Invalid search page")
        val cursor = page.get("nextCursor")
        if (!cursor.isJsonNull && (!cursor.isJsonPrimitive || !cursor.asJsonPrimitive.isString)) throw JsonParseException("Invalid search page")
        return FilterAssetPage(
            context.deserialize(page.get("items"), object : TypeToken<List<Asset>>() {}.type),
            if (cursor.isJsonNull) null else cursor.asString
        )
    }
}
@Keep
internal data class FilterPeopleResponse(val people: List<Person>?, val hasNextPage: Boolean?)
@Keep
internal data class FilterServerVersion(val major: Int?, val minor: Int?, val patch: Int?) {
    fun supported(): Boolean = major != null && minor != null && (major > 3 || (major == 3 && minor >= 2))
}

@Keep
internal interface PhotoSearchService {
    @GET("albums") suspend fun albums(): Response<List<LibraryAlbum>>
    @GET("tags") suspend fun tags(): Response<List<LibraryTag>>
    @GET("search/suggestions") suspend fun cities(@Query("type") type: String = "city"): Response<List<String>>
    @GET("server/version") suspend fun version(): Response<FilterServerVersion>
    @POST("search/metadata") suspend fun search(@Body request: FilterSearchRequest): Response<FilterSearchResponse>
    @GET("people") suspend fun people(@Query("page") page: Int, @Query("size") size: Int = 100): Response<FilterPeopleResponse>
}

internal enum class SearchFailure {
    CONNECTION, PERMISSION, PEOPLE_PERMISSION, UNSUPPORTED, VERSION_UNAVAILABLE, INVALID_RESPONSE, REPEATED_CURSOR
}
internal class SearchException(val failure: SearchFailure) : Exception(failure.name)
internal data class PhotoSearchPage(val items: List<Asset>, val nextCursor: String?)
internal data class PhotoPeoplePage(val items: List<Person>, val hasNextPage: Boolean)

internal interface PhotoSearchSource {
    suspend fun search(query: PhotoSearchQuery, cursor: String?): PhotoSearchPage
    suspend fun people(page: Int): PhotoPeoplePage
    suspend fun choices(kind: LibraryChoiceKind): List<LibraryChoice> = throw SearchException(SearchFailure.UNSUPPORTED)
}

/** This instance is bound to one immutable unlock session, including version capability results. */
internal class PhotoSearchRepository(
    private val service: PhotoSearchService,
    private val sessionValid: () -> Boolean
) : PhotoSearchSource {
    private var versionVerified = false

    constructor(config: ApiClientConfig) : this(
        Retrofit.Builder().baseUrl(ServerPolicy.apiBase(config.hostName))
            .client(ApiClientFactory.forApiSession(config))
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(PhotoSearchService::class.java),
        { config === PreferenceManager.connectionSnapshot() }
    )

    private fun requireSession() {
        if (!sessionValid()) throw CancellationException("Search session ended")
    }

    private suspend fun ensureCapability() {
        requireSession()
        if (versionVerified) return
        val version = request(SearchFailure.VERSION_UNAVAILABLE) { service.version() }
        if (version.major == null || version.minor == null) throw SearchException(SearchFailure.VERSION_UNAVAILABLE)
        if (!version.supported()) throw SearchException(SearchFailure.UNSUPPORTED)
        versionVerified = true
    }

    override suspend fun search(query: PhotoSearchQuery, cursor: String?): PhotoSearchPage {
        ensureCapability()
        val response = request(SearchFailure.CONNECTION) { service.search(query.request(cursor)) }
        val page = response.assets ?: throw SearchException(SearchFailure.INVALID_RESPONSE)
        val items = page.items ?: throw SearchException(SearchFailure.INVALID_RESPONSE)
        if (page.nextCursor?.isBlank() == true || items.any { it.id.isBlank() || (it.type != "IMAGE" && it.type != "VIDEO") }) {
            throw SearchException(SearchFailure.INVALID_RESPONSE)
        }
        return PhotoSearchPage(items, page.nextCursor)
    }

    override suspend fun people(page: Int): PhotoPeoplePage {
        require(page >= 1)
        val response = request(SearchFailure.CONNECTION, people = true) { service.people(page) }
        // Only hasNextPage governs traversal, never the number of named people in a page.
        return PhotoPeoplePage(
            response.people ?: throw SearchException(SearchFailure.INVALID_RESPONSE),
            response.hasNextPage ?: throw SearchException(SearchFailure.INVALID_RESPONSE)
        )
    }

    override suspend fun choices(kind: LibraryChoiceKind): List<LibraryChoice> {
        ensureCapability()
        val values = when (kind) {
            LibraryChoiceKind.ALBUM -> request(SearchFailure.CONNECTION) { service.albums() }.map {
                val id = it.id ?: throw SearchException(SearchFailure.INVALID_RESPONSE)
                val name = it.albumName ?: throw SearchException(SearchFailure.INVALID_RESPONSE)
                LibraryChoice(id.toString(), name)
            }
            LibraryChoiceKind.TAG -> request(SearchFailure.CONNECTION) { service.tags() }.map {
                val id = it.id ?: throw SearchException(SearchFailure.INVALID_RESPONSE)
                val name = it.value?.takeIf(String::isNotBlank) ?: it.name ?: throw SearchException(SearchFailure.INVALID_RESPONSE)
                LibraryChoice(id.toString(), name)
            }
            LibraryChoiceKind.CITY -> request(SearchFailure.CONNECTION) { service.cities() }
                .filter(String::isNotBlank).map { LibraryChoice(it, it) }
        }
        requireSession()
        return values.distinctBy { it.key }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private suspend fun <T> request(
        fallback: SearchFailure,
        people: Boolean = false,
        block: suspend () -> Response<T>
    ): T {
        requireSession()
        try {
            val response = block()
            requireSession()
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                throw SearchException(if (fallback == SearchFailure.VERSION_UNAVAILABLE) fallback else if (response.code() in listOf(401, 403)) {
                    if (people) SearchFailure.PEOPLE_PERMISSION else SearchFailure.PERMISSION
                } else fallback)
            }
            return response.body() ?: throw SearchException(SearchFailure.INVALID_RESPONSE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SearchException) {
            throw error
        } catch (_: JsonParseException) {
            requireSession()
            throw SearchException(SearchFailure.INVALID_RESPONSE)
        } catch (_: Exception) {
            requireSession()
            // No response bodies, people, criteria, URLs or credentials in errors or logs.
            throw SearchException(fallback)
        }
    }
}
