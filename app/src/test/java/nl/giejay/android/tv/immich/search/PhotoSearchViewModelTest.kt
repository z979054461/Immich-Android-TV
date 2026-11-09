package nl.giejay.android.tv.immich.search

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Person
import nl.giejay.android.tv.immich.coroutines.CoroutinesTestRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoSearchViewModelTest {
    @get:Rule val coroutines = CoroutinesTestRule()
    private val query = PhotoSearchQuery(start = LocalDate.of(2026, 1, 1))
    private class Source : PhotoSearchSource {
        val calls = mutableListOf<Pair<PhotoSearchQuery, String?>>()
        val peopleCalls = mutableListOf<Int>()
        var searchResult: suspend (PhotoSearchQuery, String?) -> PhotoSearchPage = { _, _ -> PhotoSearchPage(emptyList(), null) }
        var peopleResult: suspend (Int) -> PhotoPeoplePage = { PhotoPeoplePage(emptyList(), false) }
        override suspend fun search(query: PhotoSearchQuery, cursor: String?): PhotoSearchPage {
            calls += query to cursor
            return searchResult(query, cursor)
        }
        override suspend fun people(page: Int): PhotoPeoplePage { peopleCalls += page; return peopleResult(page) }
    }
    private fun asset(id: String) = Asset(id, "IMAGE", null, null, null, null, null, null, null, null, null)
    private fun person(name: String?) = Person(name, UUID.randomUUID(), "synthetic")
    private fun vm(source: Source): PhotoSearchViewModel {
        val session = Any()
        return PhotoSearchViewModel(source, session) { session }.also { it.setQuery(query) }
    }

    @Test fun `first request is one page then focus near end loads only one next page`() = runTest {
        val source = Source()
        var pending: Continuation<PhotoSearchPage>? = null
        source.searchResult = { _, cursor ->
            if (cursor == null) PhotoSearchPage((1..100).map { asset("a$it") }, "opaque-A")
            else suspendCoroutine { pending = it }
        }
        val vm = vm(source)
        vm.search()
        assertEquals(listOf<String?>(null), source.calls.map { it.second })
        vm.onFocused(0)
        assertEquals(1, source.calls.size)
        vm.onFocused(99); vm.onFocused(99); vm.loadNext()
        assertEquals(2, source.calls.size)
        assertTrue(vm.state.value.loading)
        pending!!.resume(PhotoSearchPage(listOf(asset("a101")), null))
        assertEquals(101, vm.state.value.items.size)
        assertFalse(vm.state.value.canLoadMore)
        vm.loadNext()
        assertEquals(2, source.calls.size)
    }

    @Test fun `empty and duplicate only pages with cursors are not the end and do not auto drain`() = runTest {
        val source = Source()
        val pages = ArrayDeque(listOf(
            PhotoSearchPage(listOf(asset("A")), "1"),
            PhotoSearchPage(listOf(asset("A")), "2"),
            PhotoSearchPage(emptyList(), "3"),
            PhotoSearchPage(listOf(asset("B")), null)
        ))
        source.searchResult = { _, _ -> pages.removeFirst() }
        val vm = vm(source)
        vm.search(); vm.loadNext()
        assertEquals(2, source.calls.size)
        assertEquals(listOf("A"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.canLoadMore)
        vm.loadNext()
        assertTrue(vm.state.value.canLoadMore)
        assertEquals(3, source.calls.size)
        vm.loadNext()
        assertEquals(listOf("A", "B"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.canLoadMore)
    }

    @Test fun `failure keeps ordered assets and retries exactly the same opaque cursor`() = runTest {
        val source = Source()
        var failed = false
        source.searchResult = { _, cursor ->
            when {
                cursor == null -> PhotoSearchPage(listOf(asset("A")), "token/+==")
                !failed -> { failed = true; throw SearchException(SearchFailure.CONNECTION) }
                else -> PhotoSearchPage(listOf(asset("A"), asset("B")), null)
            }
        }
        val vm = vm(source)
        vm.search(); vm.loadNext()
        assertEquals(SearchFailure.CONNECTION, vm.state.value.failure)
        assertEquals(listOf("A"), vm.state.value.items.map { it.id })
        vm.onFocused(0)
        assertEquals(2, source.calls.size) // No automatic retry loop from focus.
        vm.loadNext()
        assertEquals(listOf(null, "token/+==", "token/+=="), source.calls.map { it.second })
        assertEquals(listOf("A", "B"), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.failure)
    }

    @Test fun `repeated cursor and cycles stop without losing loaded assets`() = runTest {
        val source = Source()
        val pages = ArrayDeque(listOf(
            PhotoSearchPage(listOf(asset("A")), "1"),
            PhotoSearchPage(listOf(asset("B")), "2"),
            PhotoSearchPage(listOf(asset("C")), "1")
        ))
        source.searchResult = { _, _ -> pages.removeFirst() }
        val vm = vm(source)
        vm.search(); vm.loadNext(); vm.loadNext(); vm.loadNext()
        assertEquals(3, source.calls.size)
        assertEquals(3, vm.state.value.items.size)
        assertEquals(SearchFailure.REPEATED_CURSOR, vm.state.value.failure)
        assertFalse(vm.state.value.canLoadMore)
    }

    @Test fun `rapid filter edits reject an old response even if transport ignores cancellation`() = runTest {
        val source = Source()
        val pending = mutableListOf<Continuation<PhotoSearchPage>>()
        source.searchResult = { _, _ -> suspendCoroutine { pending += it } }
        val vm = vm(source)
        vm.search()
        vm.setQuery(query.copy(endInclusive = LocalDate.of(2026, 2, 1)))
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.items.isEmpty())
        vm.search()
        pending[0].resume(PhotoSearchPage(listOf(asset("stale")), "stale"))
        assertTrue(vm.state.value.loading)
        assertTrue(vm.state.value.items.isEmpty())
        pending[1].resume(PhotoSearchPage(listOf(asset("fresh")), null))
        assertEquals(listOf("fresh"), vm.state.value.items.map { it.id })
    }

    @Test fun `same profile unlocked again is a new session and cannot revive old photos or people`() = runTest {
        val source = Source()
        val session = ApiClientConfig("https://synthetic.invalid", "synthetic-key", false, false)
        var current = session
        var pending: Continuation<PhotoSearchPage>? = null
        source.searchResult = { _, _ -> suspendCoroutine { pending = it } }
        source.peopleResult = { PhotoPeoplePage(listOf(person("Synthetic Person")), true) }
        val vm = PhotoSearchViewModel(source, session) { current }
        vm.setQuery(query); vm.loadPeople(); vm.search()
        current = session.copy()
        pending!!.resume(PhotoSearchPage(listOf(asset("stale")), null))
        assertTrue(vm.state.value.locked)
        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(vm.people.value.items.isEmpty())
        assertFalse(vm.state.value.query.hasCondition)
        vm.search(); vm.loadPeople()
        assertEquals(1, source.calls.size)
        assertEquals(1, source.peopleCalls.size)
    }

    @Test fun `blank named first people page does not terminate paging and failed page retries in place`() = runTest {
        val source = Source()
        var failed = false
        source.peopleResult = { page -> when {
            page == 1 -> PhotoPeoplePage(listOf(person(null), person("  ")), true)
            !failed -> { failed = true; throw SearchException(SearchFailure.CONNECTION) }
            else -> PhotoPeoplePage(listOf(person("Synthetic Person")), false)
        } }
        val vm = vm(source)
        vm.loadPeople()
        assertTrue(vm.people.value.items.isEmpty())
        assertTrue(vm.people.value.hasNextPage)
        vm.loadPeople(); vm.loadPeople()
        assertEquals(listOf(1, 2, 2), source.peopleCalls)
        assertEquals(1, vm.people.value.items.size)
        assertFalse(vm.people.value.hasNextPage)
    }

    @Test fun `missing person permission does not block independent date search`() = runTest {
        val source = Source()
        source.peopleResult = { throw SearchException(SearchFailure.PEOPLE_PERMISSION) }
        val vm = vm(source)
        vm.loadPeople(); vm.search()
        assertEquals(SearchFailure.PEOPLE_PERMISSION, vm.people.value.failure)
        assertNull(vm.state.value.failure)
        assertTrue(vm.state.value.started)
        assertEquals(1, source.calls.size)
    }

    @Test fun `pause cancels in flight work and stale completion cannot publish`() = runTest {
        val source = Source()
        var pending: Continuation<PhotoSearchPage>? = null
        source.searchResult = { _, _ -> suspendCoroutine { pending = it } }
        val vm = vm(source)
        vm.search(); vm.pause()
        pending!!.resume(PhotoSearchPage(listOf(asset("stale")), "2"))
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(vm.state.value.canLoadMore)
    }
}
