package nl.giejay.android.tv.immich.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Person

internal data class PhotoSearchState(
    val query: PhotoSearchQuery = PhotoSearchQuery(),
    val items: List<Asset> = emptyList(),
    val started: Boolean = false,
    val loading: Boolean = false,
    val canLoadMore: Boolean = false,
    val canLoadPrevious: Boolean = false,
    val pageNumber: Int = 1,
    val retryPrevious: Boolean = false,
    val failure: SearchFailure? = null,
    val locked: Boolean = false
)
internal data class PhotoPeopleState(
    val items: List<Person> = emptyList(),
    val started: Boolean = false,
    val loading: Boolean = false,
    val hasNextPage: Boolean = true,
    val failure: SearchFailure? = null
)

/** Calls and publications are confined to Main. Request generation + referential session checks
 * protect against cancellation-uncooperative transports and re-unlocking the same profile. */
internal class PhotoSearchViewModel(
    private val source: PhotoSearchSource,
    private val session: Any,
    private val currentSession: () -> Any
) : ViewModel() {
    private val mutableState = MutableStateFlow(PhotoSearchState())
    val state = mutableState.asStateFlow()
    private val mutablePeople = MutableStateFlow(PhotoPeopleState())
    val people = mutablePeople.asStateFlow()
    private var generation = 0L
    val queryGeneration: Long get() = generation
    private var peopleGeneration = 0L
    private var searchJob: Job? = null
    private var peopleJob: Job? = null
    private var cursor: String? = null
    private val consumedCursors = linkedSetOf<String?>()
    private val previousCursors = mutableListOf<String?>()
    private var currentPageCursor: String? = null
    private var hasLoadedPage = false
    private var peoplePage = 1
    var selectedAssetId: String? = null

    fun checkSession(): Boolean {
        if (session === currentSession() && !state.value.locked) return true
        endSession()
        return false
    }

    fun endSession() {
        generation++
        peopleGeneration++
        searchJob?.cancel()
        peopleJob?.cancel()
        cursor = null
        consumedCursors.clear()
        previousCursors.clear()
        currentPageCursor = null
        hasLoadedPage = false
        selectedAssetId = null
        mutableState.value = PhotoSearchState(locked = true)
        mutablePeople.value = PhotoPeopleState(hasNextPage = false)
    }

    /** A committed control change immediately invalidates results, even before Search is pressed. */
    fun setQuery(query: PhotoSearchQuery) {
        if (!checkSession() || state.value.query == query) return
        generation++
        searchJob?.cancel()
        cursor = null
        consumedCursors.clear()
        previousCursors.clear()
        currentPageCursor = null
        hasLoadedPage = false
        selectedAssetId = null
        mutableState.value = PhotoSearchState(query = query.copy(people = query.people.toSet()))
    }

    fun search() {
        if (!checkSession() || !state.value.query.valid || !state.value.query.hasCondition || state.value.loading) return
        generation++
        searchJob?.cancel()
        cursor = null
        consumedCursors.clear()
        previousCursors.clear()
        currentPageCursor = null
        hasLoadedPage = false
        selectedAssetId = null
        mutableState.value = PhotoSearchState(query = state.value.query, started = true, canLoadMore = true)
        loadNext()
    }

    // Search uses explicit pages: focusing the final tile must not replace it underneath the user.
    fun onFocused(@Suppress("UNUSED_PARAMETER") index: Int) = Unit

    suspend fun choices(kind: LibraryChoiceKind): List<LibraryChoice> {
        if (!checkSession()) throw CancellationException("Search session ended")
        val requestGeneration = generation
        val result = source.choices(kind)
        if (!accept(requestGeneration)) throw CancellationException("Search changed")
        return result
    }

    fun loadNext() {
        if (!checkSession()) return
        val before = state.value
        if (!before.started || before.loading || !before.canLoadMore || before.failure == SearchFailure.REPEATED_CURSOR) return
        loadPage(cursor, backwards = false)
    }

    fun loadPrevious() {
        if (!checkSession() || state.value.loading || previousCursors.isEmpty()) return
        loadPage(previousCursors.last(), backwards = true)
    }

    /** Keep one asset page and at most 128 opaque back cursors, not every photo visited. */
    private fun loadPage(requestCursor: String?, backwards: Boolean) {
        val before = state.value
        val requestGeneration = generation
        mutableState.value = before.copy(loading = true, failure = null)
        searchJob = viewModelScope.launch {
            try {
                val page = source.search(before.query, requestCursor)
                if (!accept(requestGeneration)) return@launch
                if (page.items.size > PAGE_SIZE) throw SearchException(SearchFailure.INVALID_RESPONSE)
                if (backwards) {
                    previousCursors.removeAt(previousCursors.lastIndex)
                    consumedCursors.clear()
                    consumedCursors.addAll(previousCursors)
                } else if (hasLoadedPage) {
                    previousCursors.add(currentPageCursor)
                    if (previousCursors.size > MAX_BACK_PAGES) previousCursors.removeAt(0)
                }
                consumedCursors.add(requestCursor)
                if (consumedCursors.size > MAX_BACK_PAGES + 1) consumedCursors.remove(consumedCursors.first())
                val repeated = page.nextCursor != null && page.nextCursor in consumedCursors
                val pageNumber = if (backwards) (before.pageNumber - 1).coerceAtLeast(1)
                    else if (hasLoadedPage) before.pageNumber + 1 else 1
                currentPageCursor = requestCursor
                hasLoadedPage = true
                cursor = page.nextCursor
                selectedAssetId = null
                mutableState.value = state.value.copy(
                    retryPrevious = false, items = page.items.distinctBy { it.id }, loading = false,
                    pageNumber = pageNumber, canLoadPrevious = previousCursors.isNotEmpty(),
                    canLoadMore = page.nextCursor != null && !repeated,
                    failure = if (repeated) SearchFailure.REPEATED_CURSOR else null
                )
            } catch (cancelled: CancellationException) {
                if (accept(requestGeneration)) mutableState.value = state.value.copy(loading = false)
                throw cancelled
            } catch (error: Exception) {
                if (accept(requestGeneration)) mutableState.value = state.value.copy(
                    loading = false, retryPrevious = backwards, failure = (error as? SearchException)?.failure ?: SearchFailure.CONNECTION
                )
                // Do not advance the cursor or drop current photos on a failed page request.
            }
        }
    }

    private fun accept(requestGeneration: Long): Boolean = checkSession() && requestGeneration == generation

    fun loadPeople() {
        if (!checkSession()) return
        val before = people.value
        if (before.loading || !before.hasNextPage) return
        val requestGeneration = peopleGeneration
        val requestedPage = peoplePage
        mutablePeople.value = before.copy(started = true, loading = true, failure = null)
        peopleJob = viewModelScope.launch {
            try {
                val page = source.people(requestedPage)
                if (!checkSession() || requestGeneration != peopleGeneration) return@launch
                val named = page.items.filter { !it.name.isNullOrBlank() }
                peoplePage++
                mutablePeople.value = people.value.copy(
                    items = (people.value.items + named).distinctBy { it.id },
                    loading = false, hasNextPage = page.hasNextPage
                )
            } catch (cancelled: CancellationException) {
                if (checkSession() && requestGeneration == peopleGeneration) mutablePeople.value = people.value.copy(loading = false)
                throw cancelled
            } catch (error: Exception) {
                if (checkSession() && requestGeneration == peopleGeneration) mutablePeople.value = people.value.copy(
                    loading = false, failure = (error as? SearchException)?.failure ?: SearchFailure.CONNECTION
                )
            }
        }
    }

    /** Stop off-screen work; results can survive only while this exact unlock session is current. */
    fun pause() {
        generation++
        peopleGeneration++
        searchJob?.cancel()
        peopleJob?.cancel()
        if (checkSession()) {
            mutableState.value = state.value.copy(loading = false)
            mutablePeople.value = people.value.copy(loading = false)
        }
    }

    companion object { const val PAGE_SIZE = 100; const val MAX_BACK_PAGES = 128 }
}
