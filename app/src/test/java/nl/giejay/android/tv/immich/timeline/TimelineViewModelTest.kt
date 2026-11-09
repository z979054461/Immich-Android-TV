package nl.giejay.android.tv.immich.timeline

import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.giejay.android.tv.immich.api.model.TimeBucketSummary
import nl.giejay.android.tv.immich.api.model.TimelineAsset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun asset(
        id: String,
        createdAt: String = "2026-07-01T12:00:00Z",
        localOffsetHours: Double = 0.0
    ) = TimelineAsset(
        id = id,
        ratio = 1.0,
        isFavorite = false,
        isImage = true,
        thumbhash = null,
        fileCreatedAt = OffsetDateTime.parse(createdAt),
        localOffsetHours = localOffsetHours,
        duration = null
    )

    @Test
    fun `loadBucketList caches buckets and eagerly loads first months`() = runTest {
        val bucketFetches = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(
                    listOf(
                        TimeBucketSummary("2026-07-01", 2),
                        TimeBucketSummary("2026-06-01", 1),
                        TimeBucketSummary("2026-05-01", 1),
                        TimeBucketSummary("2026-04-01", 1)
                    )
                )
            },
            fetchBucket = { key ->
                bucketFetches.incrementAndGet()
                Either.Right(listOf(asset("$key-a")))
            },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        vm.loadBucketList(eagerMonths = 3)
        advanceUntilIdle()

        assertEquals(4, vm.buckets.value.size)
        assertEquals(3, bucketFetches.get())
        assertTrue(vm.bucketAssets.value.containsKey("2026-07-01"))
        assertTrue(vm.bucketAssets.value.containsKey("2026-06-01"))
        assertTrue(vm.bucketAssets.value.containsKey("2026-05-01"))
        assertTrue("2026-04-01" !in vm.bucketAssets.value)
    }

    @Test
    fun `loadBucket skips duplicate in-flight fetches`() = runTest {
        val fetches = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(TimeBucketSummary("2026-07-01", 1))) },
            fetchBucket = {
                fetches.incrementAndGet()
                delay(50)
                Either.Right(listOf(asset("a")))
            },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        val first = async { vm.loadBucket("2026-07-01") }
        val second = async { vm.loadBucket("2026-07-01") }
        advanceUntilIdle()
        assertTrue(first.await().isRight())
        assertTrue(second.await().isRight())
        assertEquals(1, fetches.get())
    }

    @Test
    fun `days groups assets by local calendar day newest first`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(
                    listOf(
                        TimeBucketSummary("2026-07-01", 3),
                        TimeBucketSummary("2026-06-01", 1)
                    )
                )
            },
            fetchBucket = { key ->
                Either.Right(
                    when (key) {
                        "2026-07-01" -> listOf(
                            asset("jul-2", "2026-07-02T18:00:00Z"),
                            asset("jul-1b", "2026-07-01T15:00:00Z"),
                            asset("jul-1a", "2026-07-01T10:00:00Z")
                        )
                        else -> listOf(asset("jun-1", "2026-06-15T12:00:00Z"))
                    }
                )
            },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        vm.loadBucketList(eagerMonths = 2)
        advanceUntilIdle()

        assertEquals(
            listOf("2026-07-02", "2026-07-01", "2026-06-15"),
            vm.days.value.map { it.dayKey }
        )
        assertEquals(listOf("jul-1b", "jul-1a"), vm.days.value[1].assets.map { it.id })
        assertEquals(
            listOf("jul-2", "jul-1b", "jul-1a", "jun-1"),
            vm.flatAssetIndex().map { it.second.id }
        )
        assertEquals(
            listOf("2026-07-02", "2026-07-01", "2026-07-01", "2026-06-15"),
            vm.flatAssetIndex().map { it.first }
        )
    }

    @Test
    fun `nextUnloadedBucket returns first missing month`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(
                    listOf(
                        TimeBucketSummary("2026-07-01", 1),
                        TimeBucketSummary("2026-06-01", 1)
                    )
                )
            },
            fetchBucket = { Either.Right(listOf(asset("a"))) },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        vm.loadBucketList(eagerMonths = 1)
        advanceUntilIdle()

        assertEquals("2026-06-01", vm.nextUnloadedBucket()?.timeBucket)
    }

    @Test
    fun `nextOlderUnloadedBucket extends past oldest loaded not first global gap`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(
                    listOf(
                        TimeBucketSummary("2026-07-01", 1),
                        TimeBucketSummary("2026-06-01", 1),
                        TimeBucketSummary("2026-05-01", 1),
                        TimeBucketSummary("2005-03-01", 1),
                        TimeBucketSummary("2005-02-01", 1)
                    )
                )
            },
            fetchBucket = { key -> Either.Right(listOf(asset("$key-a"))) },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        vm.loadBucketList(eagerMonths = 1)
        advanceUntilIdle()
        vm.loadBucket("2005-03-01")
        advanceUntilIdle()

        // First global gap is still June 2026, but bottom-of-timeline paging must continue from 2005.
        assertEquals("2026-06-01", vm.nextUnloadedBucket()?.timeBucket)
        assertEquals("2005-02-01", vm.nextOlderUnloadedBucket()?.timeBucket)
    }

    @Test
    fun `nextNewerUnloadedBucket bridges toward today from a historic island`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(
                    listOf(
                        TimeBucketSummary("2026-07-01", 1),
                        TimeBucketSummary("2026-06-01", 1),
                        TimeBucketSummary("2026-02-01", 1),
                        TimeBucketSummary("2006-08-01", 1),
                        TimeBucketSummary("2006-07-01", 1)
                    )
                )
            },
            fetchBucket = { key -> Either.Right(listOf(asset("$key-a"))) },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        vm.loadBucketList(eagerMonths = 1)
        advanceUntilIdle()
        vm.loadBucket("2026-02-01")
        vm.loadBucket("2006-07-01")
        advanceUntilIdle()

        // Closest newer unloaded month above July 2006 is August 2006, not a 2026 gap.
        assertEquals("2006-08-01", vm.nextNewerUnloadedBucket("2006-07-15")?.timeBucket)
    }

    @Test
    fun `nextOlderUnloadedBucket from day bridges holes under focus not past oldest island`() =
        runTest {
            val vm = TimelineViewModel(
                fetchBuckets = {
                    Either.Right(
                        listOf(
                            TimeBucketSummary("2026-07-01", 1),
                            TimeBucketSummary("2004-08-01", 1),
                            TimeBucketSummary("2004-07-01", 1),
                            TimeBucketSummary("2004-01-01", 1)
                        )
                    )
                },
                fetchBucket = { key -> Either.Right(listOf(asset("$key-a"))) },
                prefetchDebounceMs = 0
            )

            vm.loadBucketList(eagerMonths = 1)
            advanceUntilIdle()
            vm.loadBucket("2004-08-01")
            vm.loadBucket("2004-01-01")
            advanceUntilIdle()

            // Global oldest-loaded paging would go past January; from-focus must fill July 2004.
            assertEquals("2004-07-01", vm.nextOlderUnloadedBucket("2004-08-21")?.timeBucket)
            assertTrue(vm.hasUnloadedBucketBetween("2004-08-21", "2004-01-01"))
            assertFalse(vm.hasUnloadedBucketBetween("2004-08-21", "2004-08-01"))
        }

    @Test
    fun `hasUnloadedBucketBetween is false for true library sparsity`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(
                    listOf(
                        TimeBucketSummary("2004-08-01", 1),
                        TimeBucketSummary("2004-01-01", 1)
                    )
                )
            },
            fetchBucket = { key -> Either.Right(listOf(asset("$key-a"))) },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        vm.loadBucketList(eagerMonths = 2)
        advanceUntilIdle()

        assertFalse(vm.hasUnloadedBucketBetween("2004-08-21", "2004-01-01"))
        assertNull(vm.nextOlderUnloadedBucket("2004-08-21"))
    }

    @Test
    fun `nextOlder and nextNewer return null when contiguous island has no gap`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(
                    listOf(
                        TimeBucketSummary("2026-07-01", 1),
                        TimeBucketSummary("2026-06-01", 1),
                        TimeBucketSummary("2026-05-01", 1)
                    )
                )
            },
            fetchBucket = { key -> Either.Right(listOf(asset("$key-a"))) },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        vm.loadBucketList(eagerMonths = 3)
        advanceUntilIdle()

        assertNull(vm.nextOlderUnloadedBucket())
        assertNull(vm.nextNewerUnloadedBucket("2026-05-15"))
        assertNull(vm.nextUnloadedBucket())
    }

    @Test
    fun `nextBucketAfter pages older than slider cursor never first global gap`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(
                    listOf(
                        TimeBucketSummary("2026-07-01", 1),
                        TimeBucketSummary("2026-06-01", 1),
                        TimeBucketSummary("2026-05-01", 1),
                        TimeBucketSummary("2005-03-01", 1)
                    )
                )
            },
            fetchBucket = { key -> Either.Right(listOf(asset("$key-a"))) },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        vm.loadBucketList(eagerMonths = 1)
        advanceUntilIdle()
        vm.loadBucket("2005-03-01")
        advanceUntilIdle()

        // Global gap is still June; slider at historic island must continue to May (via after Jul? No —
        // after 2005-03 there is nothing). After June when only Jul loaded: May is not next after Jul.
        assertEquals("2026-06-01", vm.nextBucketAfter("2026-07-01")?.timeBucket)
        assertEquals("2026-05-01", vm.nextBucketAfter("2026-06-01")?.timeBucket)
        assertNull(vm.nextBucketAfter("2005-03-01"))
        assertNull(vm.nextBucketAfter("missing"))
    }

    @Test
    fun `rememberSelection and rememberSelectionByAssetId round trip`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(listOf(TimeBucketSummary("2026-07-01", 2)))
            },
            fetchBucket = {
                Either.Right(
                    listOf(
                        asset("a", "2026-07-02T12:00:00Z"),
                        asset("b", "2026-07-01T12:00:00Z")
                    )
                )
            },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        vm.loadBucketList(eagerMonths = 1)
        advanceUntilIdle()

        vm.rememberSelection("2026-07-02", "a")
        assertEquals("2026-07-02", vm.lastSelectedDayKey)
        assertEquals("a", vm.lastSelectedAssetId)

        vm.pendingResumeAssetId = "a"
        assertEquals("a", vm.pendingResumeAssetId)
        vm.pendingResumeAssetId = null
        assertNull(vm.pendingResumeAssetId)

        vm.rememberSelectionByAssetId("b")
        assertEquals("2026-07-01", vm.lastSelectedDayKey)
        assertEquals("b", vm.lastSelectedAssetId)
    }

    @Test
    fun `slider open then advance leave-off restores last viewed asset`() {
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(emptyList()) },
            fetchBucket = { Either.Right(emptyList()) },
            prefetchDebounceMs = 0
        )

        // Opening a mosaic item (TimelineFragment.onItemClicked).
        vm.applyLeaveOffSnapshot(TimelineLeaveOff.afterOpeningMosaic("asset-open"))
        assertEquals(
            TimelineLeaveOff.Target.Mosaic("asset-open", allowScrollAdjust = false),
            TimelineLeaveOff.resolveRestore(vm.leaveOffSnapshot())
        )

        // Advancing in the slider (MediaSliderConfiguration.onAssetSelected wiring).
        vm.applyLeaveOffSnapshot(TimelineLeaveOff.afterOpeningMosaic("asset-later"))
        vm.rememberSelectionByAssetId("asset-later") // no-op without buckets; dayKey unset
        assertEquals(
            TimelineLeaveOff.Target.Mosaic("asset-later", allowScrollAdjust = false),
            TimelineLeaveOff.resolveRestore(vm.leaveOffSnapshot())
        )
        assertEquals("asset-later", vm.pendingResumeAssetId)
        assertEquals("asset-later", vm.lastSelectedAssetId)
        assertFalse(vm.leaveOffAllowScrollAdjust)
    }

    @Test
    fun `menu capture after Left keeps sticky memory on ViewModel`() {
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(emptyList()) },
            fetchBucket = { Either.Right(emptyList()) },
            prefetchDebounceMs = 0
        )
        vm.lastSelectedMemoryId = "mem-left"
        vm.lastSelectedAssetId = "asset-a"

        // Headers open: focus already gone (focusedMemory/Mosaic null), sticky remains.
        vm.applyLeaveOffSnapshot(
            TimelineLeaveOff.captureForMenu(
                focusedMemoryId = null,
                focusedMosaicAssetId = null,
                stickyMemoryId = vm.lastSelectedMemoryId,
                lastAssetId = vm.lastSelectedAssetId
            )
        )
        assertEquals(
            TimelineLeaveOff.Target.Memory("mem-left"),
            TimelineLeaveOff.resolveRestore(vm.leaveOffSnapshot())
        )
    }

    @Test
    fun `focus month demand load never requests its neighbors`() = runTest {
        val fetched = mutableListOf<String>()
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(
                TimeBucketSummary("2026-07-01", 1),
                TimeBucketSummary("2026-06-01", 1),
                TimeBucketSummary("2026-05-01", 1),
                TimeBucketSummary("2026-04-01", 1)
            )) },
            fetchBucket = { key ->
                fetched.add(key)
                Either.Right(listOf(asset("$key-a", createdAt = "${key.take(8)}15T12:00:00Z")))
            },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")
        vm.loadBucketList(eagerMonths = 0)
        assertTrue(fetched.isEmpty())

        // Exercise the debounced callback's demand loader without real timer/thread races.
        vm.loadFocusMonth("2026-06-15")
        vm.loadFocusMonth("2026-06-16")

        assertEquals(listOf("2026-06-01"), fetched)
        assertEquals(setOf("2026-06-01"), vm.bucketAssets.value.keys)
    }

    @Test
    fun `loadMemories sets memoriesReady on success and error`() = runTest {
        val ok = TimelineViewModel(
            fetchBuckets = { Either.Right(emptyList()) },
            fetchBucket = { Either.Right(emptyList()) },
            fetchMemories = {
                Either.Right(
                    listOf(
                        nl.giejay.android.tv.immich.api.model.Memory(
                            id = "m1",
                            type = "on_this_day",
                            memoryAt = java.util.Date(),
                            data = nl.giejay.android.tv.immich.api.model.MemoryData(year = 2020),
                            assets = emptyList()
                        )
                    )
                )
            },
            prefetchDebounceMs = 0
        )
        ok.loadMemories()
        advanceUntilIdle()
        assertTrue(ok.memoriesReady.value)
        assertEquals(1, ok.memories.value.size)

        val fail = TimelineViewModel(
            fetchBuckets = { Either.Right(emptyList()) },
            fetchBucket = { Either.Right(emptyList()) },
            fetchMemories = { Either.Left("boom") },
            prefetchDebounceMs = 0
        )
        fail.loadMemories()
        advanceUntilIdle()
        assertTrue(fail.memoriesReady.value)
        assertTrue(fail.memories.value.isEmpty())
        assertNull(fail.error.value)
    }

    @Test
    fun `loadBucketList is idempotent`() = runTest {
        val fetches = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = {
                fetches.incrementAndGet()
                Either.Right(listOf(TimeBucketSummary("2026-07-01", 1)))
            },
            fetchBucket = { Either.Right(listOf(asset("a"))) },
            prefetchDebounceMs = 0
        )
        vm.loadBucketList(eagerMonths = 1)
        advanceUntilIdle()
        vm.loadBucketList(eagerMonths = 1)
        advanceUntilIdle()
        assertEquals(1, fetches.get())
    }

    @Test
    fun `bucketKeyForAsset and resolveBucketKey fuzzy YearMonth`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(listOf(TimeBucketSummary("2022-01-01", 1)))
            },
            fetchBucket = {
                Either.Right(listOf(asset("x", "2022-01-15T12:00:00Z")))
            },
            prefetchDebounceMs = 0
        )
        vm.loadBucketList(eagerMonths = 1)
        advanceUntilIdle()

        assertEquals("2022-01-01", vm.bucketKeyForAsset("x"))
        assertEquals("2022-01-01", vm.resolveBucketKey("2022-01"))
        assertEquals("2022-01-01", vm.resolveBucketKey("2022-01-15"))
    }

    @Test
    fun `monthBucketKey uses first of month`() {
        assertEquals(
            "2026-07-01",
            TimelineViewModel.monthBucketKey(java.time.LocalDate.of(2026, 7, 13))
        )
    }

    @Test
    fun `newestDayKeyForBucket uses local capture date not UTC month label`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(listOf(TimeBucketSummary("2022-01-01", 1)))
            },
            fetchBucket = {
                // UTC Jan 1 05:00 with UTC-8 → local Dec 31 2021 — common scrubber miss.
                Either.Right(
                    listOf(
                        asset(
                            id = "nye",
                            createdAt = "2022-01-01T05:00:00Z",
                            localOffsetHours = -8.0
                        )
                    )
                )
            },
            prefetchDebounceMs = 0
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        vm.loadBucketList(eagerMonths = 1)
        advanceUntilIdle()

        assertEquals("2021-12-31", vm.newestDayKeyForBucket("2022-01-01"))
        assertEquals("2022-01-01", vm.resolveBucketKey("2022-01"))
    }

    @Test
    fun `default startup fetches only newest month even for huge library`() = runTest {
        val fetched = mutableListOf<String>()
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(
                TimeBucketSummary("2025-01-01", 100000),
                TimeBucketSummary("2026-07-01", 100000),
                TimeBucketSummary("2026-06-01", 100000)
            )) },
            fetchBucket = { key -> fetched.add(key); Either.Right(listOf(asset(key))) }
        )
        vm.loadBucketList()
        assertEquals(listOf("2026-07-01"), fetched)
        assertTrue(vm.initialLoadComplete.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `cached index still retries failed initial month`() = runTest {
        val attempts = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(TimeBucketSummary("2026-07-01", 1))) },
            fetchBucket = {
                if (attempts.incrementAndGet() == 1) Either.Left("403 missing asset.read")
                else Either.Right(listOf(asset("a")))
            }
        )
        vm.loadBucketList()
        assertEquals("403 missing asset.read", vm.error.value)
        assertTrue(vm.initialLoadComplete.value)
        assertFalse(vm.isLoading.value)
        vm.loadBucketList()
        assertEquals(2, attempts.get())
        assertNull(vm.error.value)
        assertEquals("a", vm.bucketAssets.value["2026-07-01"]!!.single().id)
    }

    @Test
    fun `optional memories finishing cannot hide pending timeline spinner`() = runTest {
        val response = CompletableDeferred<Either<String, List<TimeBucketSummary>>>()
        val vm = TimelineViewModel(
            fetchBuckets = { response.await() },
            fetchBucket = { Either.Right(emptyList()) },
            fetchMemories = { Either.Left("403 missing memory.read") }
        )
        val loading = async { vm.loadBucketList() }
        runCurrent()
        assertTrue(vm.isLoading.value)
        vm.loadMemories()
        assertTrue(vm.isLoading.value)
        assertFalse(vm.initialLoadComplete.value)
        assertNull(vm.error.value)
        response.complete(Either.Right(emptyList()))
        loading.await()
        assertFalse(vm.isLoading.value)
        assertTrue(vm.initialLoadComplete.value)
    }

    @Test
    fun `malformed asset response becomes retryable error instead of crashing`() = runTest {
        val attempts = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(TimeBucketSummary("2026-07-01", 1))) },
            fetchBucket = {
                if (attempts.incrementAndGet() == 1) throw IllegalArgumentException("private server detail")
                Either.Right(listOf(asset("a")))
            }
        )
        vm.loadBucketList()
        assertTrue(vm.error.value != null)
        assertFalse(vm.error.value!!.contains("private server detail"))
        assertFalse(vm.isLoading.value)
        vm.loadBucketList()
        assertNull(vm.error.value)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `explicit retry refreshes a previously empty timeline index`() = runTest {
        val attempts = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = {
                if (attempts.incrementAndGet() == 1) Either.Right(emptyList())
                else Either.Right(listOf(TimeBucketSummary("2026-07-01", 1)))
            },
            fetchBucket = { Either.Right(listOf(asset("new"))) }
        )
        vm.loadBucketList()
        assertTrue(vm.buckets.value.isEmpty())
        vm.loadBucketList(refresh = true)
        assertEquals(2, attempts.get())
        assertEquals("new", vm.bucketAssets.value["2026-07-01"]!!.single().id)
    }


    @Test
    fun `failed index stops loading and can be retried`() = runTest {
        val attempts = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = {
                if (attempts.incrementAndGet() == 1) Either.Left("404 timeline unavailable")
                else Either.Right(listOf(TimeBucketSummary("2026-07-01", 1)))
            },
            fetchBucket = { Either.Right(listOf(asset("a"))) }
        )
        vm.loadBucketList()
        assertEquals("404 timeline unavailable", vm.error.value)
        assertTrue(vm.initialLoadComplete.value)
        assertFalse(vm.isLoading.value)
        vm.loadBucketList()
        assertEquals(2, attempts.get())
        assertNull(vm.error.value)
        assertEquals("a", vm.bucketAssets.value["2026-07-01"]!!.single().id)
    }


    @Test
    fun `startup skips an empty newest month and stops at the first month with photos`() = runTest {
        val fetched = mutableListOf<String>()
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(
                TimeBucketSummary("2026-07-01", 1),
                TimeBucketSummary("2026-06-01", 1),
                TimeBucketSummary("2026-05-01", 1)
            )) },
            fetchBucket = { key ->
                fetched.add(key)
                Either.Right(if (key == "2026-07-01") emptyList() else listOf(asset(key)))
            }
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")

        vm.loadBucketList()

        assertEquals(listOf("2026-07-01", "2026-06-01"), fetched)
        assertEquals("2026-06-01", vm.days.value.single().assets.single().id)
        assertFalse(vm.initialSearchPending.value)
        assertNull(vm.error.value)
    }

    @Test
    fun `six empty months require explicit continuation without refetching the index or empty months`() = runTest {
        val months = (0L..8L).map { YearMonth.of(2026, 12).minusMonths(it).atDay(1).toString() }
        val fetched = mutableListOf<String>()
        val indexFetches = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = {
                indexFetches.incrementAndGet()
                Either.Right(months.map { TimeBucketSummary(it, 1) })
            },
            fetchBucket = { key ->
                fetched.add(key)
                Either.Right(if (key == months[6]) listOf(asset("first-photo")) else emptyList())
            }
        )

        vm.loadBucketList()
        advanceUntilIdle()
        assertEquals(months.take(6), fetched)
        assertTrue(vm.initialSearchPending.value)
        assertTrue(vm.initialLoadComplete.value)
        assertFalse(vm.isLoading.value)
        assertNull(vm.error.value)

        vm.continueInitialLoad()
        assertEquals(months.take(7), fetched)
        assertEquals(1, indexFetches.get())
        assertFalse(vm.initialSearchPending.value)
        assertEquals("first-photo", vm.bucketAssets.value[months[6]]!!.single().id)
    }

    @Test
    fun `an entirely empty timeline is checked in bounded batches until exhausted`() = runTest {
        val months = (0L..12L).map { YearMonth.of(2026, 12).minusMonths(it).atDay(1).toString() }
        val fetched = mutableListOf<String>()
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(months.map { TimeBucketSummary(it, 1) }) },
            fetchBucket = { key -> fetched.add(key); Either.Right(emptyList()) }
        )

        vm.loadBucketList()
        assertEquals(6, fetched.size)
        assertTrue(vm.initialSearchPending.value)
        vm.continueInitialLoad()
        assertEquals(12, fetched.size)
        assertTrue(vm.initialSearchPending.value)
        vm.continueInitialLoad()
        assertEquals(months, fetched)
        assertFalse(vm.initialSearchPending.value)
        assertTrue(vm.bucketAssets.value.values.all { it.isEmpty() })
        assertTrue(vm.layout.value.items.isEmpty())
        assertNull(vm.error.value)
        vm.continueInitialLoad()
        assertEquals(13, fetched.size)
    }

    @Test
    fun `an empty batch ending exactly at the last month does not offer continuation`() = runTest {
        val months = (0L..5L).map { YearMonth.of(2026, 12).minusMonths(it).atDay(1).toString() }
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(months.map { TimeBucketSummary(it, 1) }) },
            fetchBucket = { Either.Right(emptyList()) }
        )
        vm.loadBucketList()
        assertEquals(6, vm.bucketAssets.value.size)
        assertFalse(vm.initialSearchPending.value)
        assertNull(vm.error.value)
    }

    @Test
    fun `month failures stop empty-month search and retry without being cached as empty`() = runTest {
        val fetched = mutableListOf<String>()
        val attempts = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(
                TimeBucketSummary("2026-07-01", 1),
                TimeBucketSummary("2026-06-01", 1),
                TimeBucketSummary("2026-05-01", 1)
            )) },
            fetchBucket = { key ->
                fetched.add(key)
                when {
                    key == "2026-07-01" -> Either.Right(emptyList())
                    attempts.incrementAndGet() == 1 -> Either.Left("403 denied")
                    else -> Either.Right(listOf(asset("recovered")))
                }
            }
        )

        vm.loadBucketList()
        assertEquals(listOf("2026-07-01", "2026-06-01"), fetched)
        assertEquals("403 denied", vm.error.value)
        assertFalse(vm.initialSearchPending.value)
        assertFalse(vm.bucketAssets.value.containsKey("2026-06-01"))
        vm.continueInitialLoad()
        assertEquals(listOf("2026-07-01", "2026-06-01", "2026-06-01"), fetched)
        assertNull(vm.error.value)
        assertEquals("recovered", vm.bucketAssets.value["2026-06-01"]!!.single().id)
    }

    @Test
    fun `zero eager months fetches only the index even when all indexed months could be empty`() = runTest {
        val monthFetches = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(TimeBucketSummary("2026-07-01", 1))) },
            fetchBucket = { monthFetches.incrementAndGet(); Either.Right(emptyList()) }
        )
        vm.loadBucketList(eagerMonths = 0)
        vm.loadBucketList(eagerMonths = 0, refresh = true)
        assertEquals(0, monthFetches.get())
        assertFalse(vm.initialSearchPending.value)
    }

    @Test
    fun `refresh fetches a cached month again and replaces its photos`() = runTest {
        val indexFetches = AtomicInteger(0)
        val monthFetches = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = {
                indexFetches.incrementAndGet()
                Either.Right(listOf(TimeBucketSummary("2026-07-01", 1)))
            },
            fetchBucket = { Either.Right(listOf(asset("photo-${monthFetches.incrementAndGet()}"))) }
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")
        vm.loadBucketList()
        assertEquals("photo-1", vm.days.value.single().assets.single().id)

        vm.loadBucketList(refresh = true)

        assertEquals(2, indexFetches.get())
        assertEquals(2, monthFetches.get())
        assertEquals("photo-2", vm.days.value.single().assets.single().id)
        assertNull(vm.error.value)
    }

    @Test
    fun `refresh with an empty index clears old photos and layout even before remeasurement`() = runTest {
        val indexFetches = AtomicInteger(0)
        val monthFetches = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(if (indexFetches.incrementAndGet() == 1)
                    listOf(TimeBucketSummary("2026-07-01", 1)) else emptyList())
            },
            fetchBucket = { monthFetches.incrementAndGet(); Either.Right(listOf(asset("removed"))) }
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")
        vm.loadBucketList()
        assertTrue(vm.layout.value.items.isNotEmpty())
        vm.setLayoutConfig(0, 200, 10, "Today", "Yesterday")

        vm.loadBucketList(refresh = true)

        assertTrue(vm.buckets.value.isEmpty())
        assertTrue(vm.bucketAssets.value.isEmpty())
        assertTrue(vm.days.value.isEmpty())
        assertTrue(vm.layout.value.items.isEmpty())
        assertEquals(1, monthFetches.get())
        assertFalse(vm.initialSearchPending.value)
        assertNull(vm.error.value)
    }

    @Test
    fun `failed month refresh preserves current-index photos and remains retryable`() = runTest {
        val attempts = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(TimeBucketSummary("2026-07-01", 1))) },
            fetchBucket = {
                when (attempts.incrementAndGet()) {
                    1 -> Either.Right(listOf(asset("old")))
                    2 -> Either.Left("403 denied")
                    else -> Either.Right(listOf(asset("new")))
                }
            }
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")
        vm.loadBucketList()
        vm.loadBucketList(refresh = true)
        assertEquals("old", vm.days.value.single().assets.single().id)
        assertEquals("403 denied", vm.error.value)
        assertFalse(vm.initialSearchPending.value)
        assertFalse(vm.isLoading.value)

        vm.continueInitialLoad()
        assertEquals(3, attempts.get())
        assertEquals("new", vm.days.value.single().assets.single().id)
        assertNull(vm.error.value)
    }

    @Test
    fun `a later successful month cannot conceal a failed index refresh`() = runTest {
        val indexFetches = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = {
                if (indexFetches.incrementAndGet() == 2) Either.Left("404 index failed")
                else Either.Right(listOf(
                    TimeBucketSummary("2026-07-01", 1), TimeBucketSummary("2026-06-01", 1)
                ))
            },
            fetchBucket = { Either.Right(listOf(asset(it))) }
        )
        vm.loadBucketList()
        vm.loadBucketList(refresh = true)
        vm.loadBucket("2026-06-01")
        assertEquals("404 index failed", vm.error.value)
        assertFalse(vm.initialSearchPending.value)
        vm.continueInitialLoad()
        assertEquals(3, indexFetches.get())
        assertNull(vm.error.value)
    }

    @Test
    fun `refresh cannot be overwritten by an older in-flight month response`() = runTest {
        val olderStarted = CompletableDeferred<Unit>()
        val olderResponse = CompletableDeferred<List<TimelineAsset>>()
        val indexFetches = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(if (indexFetches.incrementAndGet() == 1) listOf(
                    TimeBucketSummary("2026-07-01", 1), TimeBucketSummary("2026-06-01", 1)
                ) else listOf(TimeBucketSummary("2026-07-01", 1)))
            },
            fetchBucket = { key ->
                if (key == "2026-06-01") {
                    olderStarted.complete(Unit)
                    // Emulate a response source that does not promptly honor cancellation.
                    Either.Right(withContext(NonCancellable) { olderResponse.await() })
                } else Either.Right(listOf(asset("current")))
            }
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")
        vm.loadBucketList()
        val pending = async { vm.loadBucket("2026-06-01") }
        olderStarted.await()
        vm.loadBucketList(refresh = true)
        olderResponse.complete(listOf(asset("superseded")))
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertFalse(vm.bucketAssets.value.containsKey("2026-06-01"))
        assertEquals("current", vm.days.value.single().assets.single().id)
        assertNull(vm.error.value)
    }

    @Test
    fun `cancelled initial fetch is not reported as a completed empty timeline`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(TimeBucketSummary("2026-07-01", 1))) },
            fetchBucket = { throw CancellationException("Synthetic cancellation") }
        )
        assertTrue(runCatching { vm.loadBucketList() }.exceptionOrNull() is CancellationException)
        assertFalse(vm.initialLoadComplete.value)
        assertFalse(vm.initialSearchPending.value)
        assertFalse(vm.isLoading.value)
        assertTrue(vm.bucketAssets.value.isEmpty())
        assertNull(vm.error.value)
    }


    @Test
    fun `focus indexes work before layout measurement and reuse one ordered snapshot`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(
                TimeBucketSummary("2026-07-01", 1), TimeBucketSummary("2026-06-01", 1)
            )) },
            fetchBucket = { key -> Either.Right(listOf(if (key == "2026-07-01")
                asset("july", "2026-07-01T23:30:00Z", localOffsetHours = 5.5)
                else asset("june", "2026-06-15T10:00:00Z"))) }
        )
        vm.loadBucketList(eagerMonths = 2)
        assertEquals("2026-07-01", vm.bucketKeyForAsset("july"))
        assertEquals("2026-06-01", vm.bucketKeyForAsset("june"))
        vm.rememberSelectionByAssetId("july")
        assertEquals("2026-07-02", vm.lastSelectedDayKey)
        assertEquals("july", vm.lastSelectedAssetId)
        vm.rememberSelectionByAssetId("june")
        assertEquals("2026-06-15", vm.lastSelectedDayKey)
        assertSame(vm.flatAssetIndex(), vm.flatAssetIndex())
        assertEquals(listOf("july", "june"), vm.flatAssetIndex().map { it.second.id })
    }

    @Test
    fun `refresh replaces focus indexes and removes lookup entries for removed assets`() = runTest {
        val requests = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(TimeBucketSummary("2026-07-01", 2))) },
            fetchBucket = {
                Either.Right(if (requests.incrementAndGet() == 1) listOf(
                    asset("removed", "2026-07-02T12:00:00Z"), asset("same", "2026-07-01T12:00:00Z")
                ) else listOf(asset("same", "2026-07-03T12:00:00Z")))
            }
        )
        vm.loadBucketList()
        assertEquals("2026-07-01", vm.bucketKeyForAsset("removed"))
        vm.rememberSelectionByAssetId("same")
        assertEquals("2026-07-01", vm.lastSelectedDayKey)
        vm.loadBucketList(refresh = true)
        vm.rememberSelectionByAssetId("same")
        assertEquals("2026-07-03", vm.lastSelectedDayKey)
        assertNull(vm.bucketKeyForAsset("removed"))
        assertEquals(listOf("same"), vm.flatAssetIndex().map { it.second.id })
        vm.rememberSelectionByAssetId("removed")
        assertEquals("same", vm.lastSelectedAssetId)
    }

    @Test
    fun `an empty refreshed index clears all focus lookup snapshots`() = runTest {
        val requests = AtomicInteger(0)
        val vm = TimelineViewModel(
            fetchBuckets = {
                Either.Right(if (requests.incrementAndGet() == 1)
                    listOf(TimeBucketSummary("2026-07-01", 1)) else emptyList())
            },
            fetchBucket = { Either.Right(listOf(asset("removed"))) }
        )
        vm.loadBucketList()
        assertEquals("2026-07-01", vm.bucketKeyForAsset("removed"))
        vm.loadBucketList(refresh = true)
        assertNull(vm.bucketKeyForAsset("removed"))
        assertTrue(vm.flatAssetIndex().isEmpty())
        vm.rememberSelectionByAssetId("removed")
        assertNull(vm.lastSelectedAssetId)
        assertNull(vm.lastSelectedDayKey)
    }


    @Test
    fun `memories ready is emitted only after its data and layout are available`() = runTest {
        val memory = nl.giejay.android.tv.immich.api.model.Memory(
            id = "ready-memory", type = "on_this_day", memoryAt = java.util.Date(),
            data = nl.giejay.android.tv.immich.api.model.MemoryData(year = 2020), assets = emptyList()
        )
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(emptyList()) },
            fetchBucket = { Either.Right(emptyList()) },
            fetchMemories = { Either.Right(listOf(memory)) }
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")
        // Unconfined observes the exact readiness emission, not merely the state after load returns.
        val layoutAtReady = async(UnconfinedTestDispatcher(testScheduler)) {
            vm.memoriesReady.first { it }
            vm.memories.value to vm.layout.value
        }
        vm.loadMemories()
        val (memories, layout) = layoutAtReady.await()
        assertEquals(listOf(memory), memories)
        assertEquals(listOf(memory), layout.items.filterIsInstance<TimelineMosaicItem.MemoriesRow>().single().memories)
    }

    @Test
    fun `optional memory error completes readiness with the existing photo layout intact`() = runTest {
        val vm = TimelineViewModel(
            fetchBuckets = { Either.Right(listOf(TimeBucketSummary("2026-07-01", 1))) },
            fetchBucket = { Either.Right(listOf(asset("photo"))) },
            fetchMemories = { Either.Left("403 no memory permission") }
        )
        vm.setLayoutConfig(1000, 200, 10, "Today", "Yesterday")
        vm.loadBucketList()
        val layoutAtReady = async(UnconfinedTestDispatcher(testScheduler)) {
            vm.memoriesReady.first { it }
            vm.layout.value
        }
        vm.loadMemories()
        assertEquals("photo", layoutAtReady.await().days.single().assets.single().id)
        assertTrue(vm.memories.value.isEmpty())
        assertNull(vm.error.value)
    }

}
