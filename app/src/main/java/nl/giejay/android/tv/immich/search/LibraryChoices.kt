package nl.giejay.android.tv.immich.search

import androidx.annotation.Keep
import java.util.UUID

internal enum class LibraryChoiceKind { ALBUM, CITY, TAG }
internal data class LibraryChoice(val key: String, val label: String) {
    override fun toString() = "LibraryChoice(redacted)"
}
@Keep
internal data class LibraryAlbum(val id: UUID?, val albumName: String?) {
    override fun toString() = "LibraryAlbum(redacted)"
}
@Keep
internal data class LibraryTag(val id: UUID?, val name: String?, val value: String?) {
    override fun toString() = "LibraryTag(redacted)"
}

/** Match locally: the directory endpoints have no substring search or pagination. */
internal fun filterLibraryChoices(items: List<LibraryChoice>, text: String): List<LibraryChoice> {
    val query = text.trim()
    return items.filter { query.isEmpty() || it.label.contains(query, ignoreCase = true) }
}
