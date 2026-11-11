package nl.giejay.android.tv.immich.search

import androidx.annotation.Keep
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class PeopleMatch { ALL, ANY }

/** Only lives in the unlocked screen's memory. Never put this in saved state or preferences. */
data class PhotoSearchQuery(
    val start: LocalDate? = null,
    val endInclusive: LocalDate? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val people: Set<UUID> = emptySet(),
    val match: PeopleMatch = PeopleMatch.ALL,
    val album: UUID? = null,
    val city: String? = null,
    val tag: UUID? = null,
    val fileNamePrefix: String? = null,
    val albumName: String? = null,
    val tagName: String? = null
) {
    val hasCondition: Boolean get() = start != null || endInclusive != null || people.isNotEmpty() || album != null || !city.isNullOrBlank() || tag != null || !fileNamePrefix.isNullOrBlank()
    val valid: Boolean get() = (start == null || endInclusive == null || !start.isAfter(endInclusive)) &&
        (city == null || city.isNotBlank()) && (fileNamePrefix == null || fileNamePrefix.isNotBlank())

    fun request(cursor: String? = null): FilterSearchRequest {
        require(valid && hasCondition)
        return FilterSearchRequest(
            filter = PhotoSearchFilter(
                takenAt = if (start != null || endInclusive != null) TakenAtFilter(
                    gte = start?.atStartOfDay(zone)?.toInstant()?.toString(),
                    // A local day is not always 24 hours (DST and historical offset changes).
                    lt = endInclusive?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toString()
                ) else null,
                albumIds = album?.let { PersonIdsFilter(any = listOf(it)) },
                tagIds = tag?.let { PersonIdsFilter(any = listOf(it)) },
                city = city?.let { ExactTextFilter(it) },
                originalFileName = fileNamePrefix?.let { FileNamePrefixFilter(it) },
                personIds = people.takeIf { it.isNotEmpty() }?.sortedBy { it.toString() }?.let {
                    if (match == PeopleMatch.ALL) PersonIdsFilter(all = it) else PersonIdsFilter(any = it)
                }
            ),
            cursor = cursor
        )
    }
    override fun toString() = "PhotoSearchQuery(redacted)"
}

/** Immich >= 3.2 structured search. Deliberately separate from the deprecated SearchRequest. */
@Keep
data class FilterSearchRequest(
    val filter: PhotoSearchFilter,
    val orderBy: FilterSearchOrder = FilterSearchOrder(),
    val size: Int = 100,
    val cursor: String? = null
) { override fun toString() = "FilterSearchRequest(redacted)" }

@Keep
data class PhotoSearchFilter(
    val takenAt: TakenAtFilter? = null,
    val personIds: PersonIdsFilter? = null,
    val visibility: VisibilityFilter = VisibilityFilter(),
    val albumIds: PersonIdsFilter? = null,
    val tagIds: PersonIdsFilter? = null,
    val city: ExactTextFilter? = null,
    val originalFileName: FileNamePrefixFilter? = null
) { override fun toString() = "PhotoSearchFilter(redacted)" }
@Keep
data class TakenAtFilter(val gte: String? = null, val lt: String? = null) {
    override fun toString() = "TakenAtFilter(redacted)"
}
@Keep
data class PersonIdsFilter(val all: List<UUID>? = null, val any: List<UUID>? = null) {
    override fun toString() = "PersonIdsFilter(redacted)"
}
@Keep
data class VisibilityFilter(val eq: String = "timeline")
@Keep
data class FilterSearchOrder(val field: String = "fileCreatedAt", val direction: String = "desc")

@Keep
data class ExactTextFilter(val eq: String) { override fun toString() = "ExactTextFilter(redacted)" }
@Keep
data class FileNamePrefixFilter(val startsWith: String) { override fun toString() = "FileNamePrefixFilter(redacted)" }
