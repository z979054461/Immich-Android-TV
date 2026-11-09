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
    private val consumedCursors = mutableSetOf<String?>()
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
        selectedAssetId = null
        mutableState.value = PhotoSearchState(query = query.copy(people = query.people.toSet()))
    }

    fun search() {
        if (!checkSession() || !state.value.query.valid || !state.value.query.hasCondition || state.value.loading) return
        generation++
        searchJob?.cancel()
        cursor = null
        consumedCursors.clear()
        selectedAssetId = null
        mutableState.value = PhotoSearchState(query = state.value.query, started = true, canLoadMore = true)
        loadNext()
    }

    fun onFocused(index: Int) {
        if (index >= 0 && state.value.items.size - index <= PREFETCH_THRESHOLD && state.value.failure == null) loadNext()
    }

    /** Exactly one page per call. Empty/duplicate-only pages still expose an explicit Continue. */
    fun loadNext() {
        if (!checkSession()) return
        val before = state.value
        if (!before.started || before.loading || !before.canLoadMore || before.failure == SearchFailure.REPEATED_CURSOR) return
        val requestGeneration = generation
        val requestCursor = cursor
        mutableState.value = before.copy(loading = true, failure = null)
        searchJob = viewModelScope.launch {
            try {
                val page = source.search(before.query, requestCursor)
                if (!accept(requestGeneration)) return@launch
                val merged = (state.value.items + page.items).distinctBy { it.id }
                consumedCursors.add(requestCursor)
                val repeated = page.nextCursor != null && page.nextCursor in consumedCursors
                cursor = page.nextCursor
                mutableState.value = state.value.copy(
                    items = merged, loading = false,
                    canLoadMore = page.nextCursor != null && !repeated,
                    failure = if (repeated) SearchFailure.REPEATED_CURSOR else null
                )
            } catch (cancelled: CancellationException) {
                if (accept(requestGeneration)) mutableState.value = state.value.copy(loading = false)
                throw cancelled
            } catch (error: Exception) {
                if (accept(requestGeneration)) mutableState.value = state.value.copy(
                    loading = false, failure = (error as? SearchException)?.failure ?: SearchFailure.CONNECTION
                )
                // Cursor and successfully loaded assets remain unchanged for an explicit retry.
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

    companion object { const val PREFETCH_THRESHOLD = 12 }
}
