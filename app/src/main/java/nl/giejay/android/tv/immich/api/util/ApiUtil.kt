package nl.giejay.android.tv.immich.api.util

import arrow.core.Either
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import nl.giejay.android.tv.immich.api.model.ImmichErrorResponse
import nl.giejay.android.tv.immich.api.model.toDisplayMessage
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import retrofit2.HttpException
import retrofit2.Response
import timber.log.Timber
import java.util.UUID

object ApiUtil {

    fun getThumbnailUrl(assetId: String?, format: String, loadEdited: Boolean = false): String? {
        return assetId?.let {
            val base = "${hostName()}/api/assets/${it}/thumbnail?size=${format}&edited=${loadEdited}"
            base
        }
    }

    private fun hostName(): String {
        return PreferenceManager.hostName
    }

    fun getFileUrl(assetId: String?, type: String, forceOriginal: Boolean = false, loadEdited: Boolean = false): String? {
        if(forceOriginal){
            return assetId?.let {
                "${hostName()}/api/assets/${it}/original"
            }
        }
        return when (type) {
            "VIDEO" ->
                assetId?.let {
                        "${hostName()}/api/assets/${it}/video/playback"
                }

            else ->
                assetId?.let {
                    "${hostName()}/api/assets/${it}/original?edited=${loadEdited}"
                }

        }
    }

    fun getPersonThumbnail(personId: UUID): String {
        return "${hostName()}/api/people/$personId/thumbnail"
    }

    suspend fun <T> executeAPICall(expectedStatus: Int, handler: suspend () -> Response<T>): Either<String, T> {
        try {
            val res = handler()
            logCorrelationId(res)
            return when (val code = res.code()) {
                expectedStatus -> {
                    res.body()?.let { Either.Right(it) }
                        ?: Either.Left("Did not receive a input from the server")
                }
                else -> {
                    val bodyString: String? = res.errorBody()?.string()
                    if(bodyString?.contains("required permission: all") == true){
                        Either.Left("API key lacks a required permission. Grant only the read permissions needed by this screen.")
                    } else {
                        val parsed = parseErrorBodySafely(bodyString)
                        val baseMessage = parsed?.message?.toDisplayMessage(bodyString ?: "Unknown error") ?: (bodyString ?: "Unknown error")
                        val validationErrors = parsed?.errors?.map { it.message.toDisplayMessage("") }?.filter { it.isNotBlank() }?.joinToString(", ")
                        val message = if (!validationErrors.isNullOrBlank()) {
                            "$baseMessage: $validationErrors"
                        } else {
                            baseMessage
                        }
                        Either.Left("Invalid status ($code) returned by Immich Server: $message")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Timber.e(e, "Could not fetch items due to http error")
            return Either.Left("Could not fetch data from API, status: ${e.code()}")
        } catch (e: Exception) {
            Timber.e(e, "Could not fetch items, unknown error")
            return Either.Left("Could not connect to your Immich server. Check address, certificate and API key permissions.")
        }
    }

    private fun parseErrorBodySafely(body: String?): ImmichErrorResponse? {
        if (body.isNullOrBlank()) return null
        return try {
            Gson().fromJson(body, ImmichErrorResponse::class.java)
        } catch (e: Exception) {
            Timber.w(e, "Could not parse structured error response, falling back to raw message")
            null
        }
    }

    private fun logCorrelationId(res: Response<*>) {
        val correlationId = res.headers()["X-Correlation-ID"]
        if (correlationId != null) {
            Timber.d("X-Correlation-ID: $correlationId")
        }
    }
}