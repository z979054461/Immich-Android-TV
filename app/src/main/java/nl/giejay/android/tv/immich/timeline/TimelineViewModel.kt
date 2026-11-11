package nl.giejay.android.tv.immich.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.api.model.Memory
import nl.giejay.android.tv.immich.api.model.TimeBucketSummary
import nl.giejay.android.tv.immich.api.model.TimelineAsset
import nl.giejay.android.tv.immich.shared.util.Debouncer
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TimelineLayout(
    val items: List<TimelineMosaicItem> = emptyList(),
    val neighbors: Map<String, TimelineFocusNeighbors> = emptyMap(),
    val days: List<TimelineDay> = emptyList()
)

/**
 * Holds timeline month buckets + lazily-fetched assets, exposed as day rows for the UI.
 */
class TimelineViewModel(
    private val fetchBuckets: suspend () -> Either<String, List<TimeBucketSummary>>,
    private val fetchBucket: suspend (String) -> Either<String, List<TimelineAsset>>,
    private val fetchMemories: suspend () -> Either<String, List<Memory>> = { Either.Right(emptyList()) },
    private val prefetchDebounceMs: Long = PREFETCH_DEBOUNCE_MS
) : ViewModel() {

    private val _buckets = MutableStateFlow<List<TimeBucketSummary>>(emptyList())
    val buckets: StateFlow<List<TimeBucketSummary>> = _buckets.asStateFlow()

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    /** True after [loadMemories] finishes (even when the list is empty). */
    private val _memoriesReady = MutableStateFlow(false)
    val memoriesReady: StateFlow<Boolean> = _memoriesReady.asStateFlow()

    private val _bucketAssets = MutableStateFlow<Map<String, List<TimelineAsset>>>(emptyMap())
    val bucketAssets: StateFlow<Map<String, List<TimelineAsset>>> = _bucketAssets.asStateFlow()

    private val _days = MutableStateFlow<List<TimelineDay>>(emptyList())
    val days: StateFlow<List<TimelineDay>> = _days.asStateFlow()

    private val _layout = MutableStateFlow(TimelineLayout())
    val layout: StateFlow<TimelineLayout> = _layout.asStateFlow()

    private data class AssetFocusIndex(
        val dayByAsset: Map<String, String> = emptyMap(),
        val bucketByAsset: Map<String, String> = emptyMap(),
        val orderedAssets: List<Pair<String, TimelineAsset>> = emptyList()
    )
    // Published with a completed data/layout snapshot; focus never scans or copies the library.
    @Volatile private var assetFocusIndex = AssetFocusIndex()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete.asStateFlow()
    /** An empty bounded search batch ended with older months still to check. */
    private val _initialSearchPending = MutableStateFlow(false)
    val initialSearchPending: StateFlow<Boolean> = _initialSearchPending.asStateFlow()
    private var bucketListLoaded = false
    private val listMutex = Mutex()
    private val layoutMutex = Mutex()
    private val stateLock = Any()
    private var indexGeneration = 0L
    private var indexError: String? = null
    private val failedBuckets = ConcurrentHashMap<String, String>()
    // Keep old photos visible during refresh, but never treat them as a fresh response.
    private val staleBuckets = mutableSetOf<String>()

    private var contentWidthPx: Int = 0
    private var rowHeightPx: Int = 0
    private var gapPx: Int = 0
    private var todayLabel: String = ""
    private var yesterdayLabel: String = ""

    private data class BucketRequest(val generation: Long, val timeBucket: String)
    private val inFlight = ConcurrentHashMap<BucketRequest, Deferred<Either<String, List<TimelineAsset>>>>()

    /** Survives TimelineFragment recreation when returning from the photo slider. */
    var lastSelectedDayKey: String? = null
    var lastSelectedAssetId: String? = null
    var lastSelectedBucketKey: String? = null
        private set

    /**
     * Memory card to restore after returning from the memory slider. Distinct from mosaic
     * [lastSelectedAssetId] so leave-off can land back on "N years ago" instead of a cell.
     */
    var lastSelectedMemoryId: String? = null

    /**
     * Sticky mosaic asset for menu / slider return. Survives stray Leanback focus until
     * [TimelineFragment] restore consumes it. Prefer over [lastSelectedAssetId] when set.
     */
    var pendingResumeAssetId: String? = null

    /**
     * Menu re-entry may jump-scroll into view; slider round-trips should keep the viewport
     * and only re-focus (see [TimelineLeaveOff.Snapshot.allowScrollAdjust]).
     */
    var leaveOffAllowScrollAdjust: Boolean = true

    fun applyLeaveOffSnapshot(snapshot: TimelineLeaveOff.Snapshot) {
        lastSelectedMemoryId = snapshot.memoryId
        pendingResumeAssetId = snapshot.pendingAssetId
        leaveOffAllowScrollAdjust = snapshot.allowScrollAdjust
        if (snapshot.lastAssetId != null) {
            lastSelectedAssetId = snapshot.lastAssetId
        }
    }

    /** Activity-scoped leave-off snapshot for unit tests and restore. */
    fun leaveOffSnapshot(): TimelineLeaveOff.Snapshot =
        TimelineLeaveOff.Snapshot(
            memoryId = lastSelectedMemoryId,
            pendingAssetId = pendingResumeAssetId,
            lastAssetId = lastSelectedAssetId,
            allowScrollAdjust = leaveOffAllowScrollAdjust
        )

    /**
     * Sets the configuration for mosaic layout calculation.
     *
     * @param widthPx Available width for the mosaic grid.
     * @param rowHeightPx Target height for each justified row.
     * @param gapPx Space between mosaic cells.
     * @param todayLabel localized "Today" string for headers.
     * @param yesterdayLabel localized "Yesterday" string for headers.
     */
    fun setLayoutConfig(
        widthPx: Int,
        rowHeightPx: Int,
        gapPx: Int,
        todayLabel: String,
        yesterdayLabel: String
    ) {
        this.contentWidthPx = widthPx
        this.rowHeightPx = rowHeightPx
        this.gapPx = gapPx
        this.todayLabel = todayLabel
        this.yesterdayLabel = yesterdayLabel
    }

    /**
     * Find the first non-empty month without mistaking an empty month for an empty library.
     * Each invocation makes at most [MAX_INITIAL_BUCKET_REQUESTS] month requests. Successfully
     * checked empty months remain cached, so [continueInitialLoad] resumes instead of restarting.
     * [eagerMonths] counts non-empty months; zero fetches only the index.
     */
    suspend fun loadBucketList(eagerMonths: Int = 1, refresh: Boolean = false) = listMutex.withLock {
        require(eagerMonths >= 0)
        _isLoading.value = true
        _initialSearchPending.value = false
        var cancelled = false
        try {
            if (refresh) bucketListLoaded = false
            if (!bucketListLoaded) {
                when (val result = safeFetch { fetchBuckets() }) {
                    is Either.Left -> {
                        synchronized(stateLock) {
                            indexError = result.value
                            publishError()
                        }
                        return@withLock
                    }
                    is Either.Right -> {
                        val buckets = result.value.distinctBy { it.timeBucket }
                            .sortedByDescending { it.timeBucket }
                        val keys = buckets.mapTo(mutableSetOf()) { it.timeBucket }
                        val superseded = synchronized(stateLock) {
                            indexGeneration++
                            _buckets.value = buckets
                            _bucketAssets.update { assets -> assets.filterKeys { it in keys } }
                            staleBuckets.clear()
                            staleBuckets.addAll(_bucketAssets.value.keys)
                            failedBuckets.keys.retainAll(keys)
                            indexError = null
                            publishError()
                            inFlight.filterKeys { it.generation != indexGeneration }.values.toList()
                        }
                        // An older response must not repopulate a removed month or overwrite new data.
                        superseded.forEach { it.cancel() }
                        bucketListLoaded = true
                        recomputeLayout()
                    }
                }
            }
            if (eagerMonths == 0) return@withLock

            var requestsRemaining = MAX_INITIAL_BUCKET_REQUESTS
            var nonEmptyMonths = 0
            val attempted = mutableSetOf<String>()
            for (bucket in _buckets.value) {
                val key = bucket.timeBucket
                val cached = freshCachedAssets(key)
                if (cached == null && requestsRemaining == 0) {
                    _initialSearchPending.value = nonEmptyMonths == 0 && _error.value == null
                    break
                }
                val result = if (cached != null) {
                    Either.Right(cached)
                } else {
                    requestsRemaining--
                    attempted.add(key)
                    loadBucket(key)
                }
                when (result) {
                    is Either.Left -> return@withLock // An error is not an empty month.
                    is Either.Right -> if (result.value.isNotEmpty()) nonEmptyMonths++
                }
                if (nonEmptyMonths >= eagerMonths) break
            }

            // Preserve retry support for a failed later month, within the same request budget.
            val failed = _buckets.value.map { it.timeBucket }
                .filter { failedBuckets.containsKey(it) && it !in attempted }
            for (key in failed) {
                if (requestsRemaining == 0) break
                requestsRemaining--
                if (loadBucket(key) is Either.Left) {
                    _initialSearchPending.value = false
                    break
                }
            }
        } catch (exception: CancellationException) {
            cancelled = true
            throw exception
        } finally {
            _isLoading.value = false
            if (!cancelled) _initialLoadComplete.value = true
        }
    }

    /** Explicit user action to check the next bounded batch, preserving checked empty months. */
    suspend fun continueInitialLoad() = loadBucketList()

    private fun freshCachedAssets(timeBucket: String): List<TimelineAsset>? = synchronized(stateLock) {
        if (timeBucket in staleBuckets || failedBuckets.containsKey(timeBucket)) null else _bucketAssets.value[timeBucket]
    }

    /** Called while holding [stateLock], so a successful month cannot clear an index failure. */
    private fun publishError() {
        _error.value = indexError ?: failedBuckets.values.firstOrNull()
    }

    suspend fun loadMemories() {
        // Optional memories must never hide the independent timeline loading indicator.
        val result = safeFetch { fetchMemories() }
        result.fold(
            { _memories.value = emptyList() },
            { _memories.value = it }
        )
        recomputeLayout()
        // Consumers may use this event to abandon a missing memory restore target.
        // Publish it only after the response and its layout have both been committed.
        _memoriesReady.value = true
    }

    private suspend fun <T> safeFetch(block: suspend () -> Either<String, T>): Either<String, T> =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not expose raw exception text, URLs, keys or a server response body.
            Either.Left("Could not read the timeline response. Check the server connection and Immich version, then retry.")
        }

    fun rememberSelection(dayKey: String, assetId: String, bucketKey: String? = assetFocusIndex.bucketByAsset[assetId]) {
        lastSelectedDayKey = dayKey
        lastSelectedAssetId = assetId
        lastSelectedBucketKey = bucketKey
    }

    fun rememberSelectionByAssetId(assetId: String) {
        val dayKey = assetFocusIndex.dayByAsset[assetId] ?: return
        rememberSelection(dayKey, assetId)
    }

    /**
     * Loads a month's assets if not cached. Concurrent callers for the same bucket share one fetch.
     */
    suspend fun loadBucket(timeBucket: String): Either<String, List<TimelineAsset>> {
        val deferred = synchronized(stateLock) {
            freshCachedAssets(timeBucket)?.let { return Either.Right(it) }
            val request = BucketRequest(indexGeneration, timeBucket)
            inFlight.computeIfAbsent(request) {
                viewModelScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        val result = safeFetch { fetchBucket(timeBucket) }
                        val accepted = synchronized(stateLock) {
                            if (request.generation != indexGeneration) {
                                false
                            } else {
                                result.fold(
                                    { message -> failedBuckets[timeBucket] = message },
                                    { assets ->
                                        failedBuckets.remove(timeBucket)
                                        staleBuckets.remove(timeBucket)
                                        _bucketAssets.update { current ->
                                            TimelineRetention.retain(
                                                loaded = current + (timeBucket to assets),
                                                orderedKeys = _buckets.value.map { it.timeBucket },
                                                incoming = timeBucket,
                                                focused = lastSelectedBucketKey
                                            )
                                        }
                                        staleBuckets.retainAll(_bucketAssets.value.keys)
                                    }
                                )
                                publishError()
                                true
                            }
                        }
                        if (!accepted) throw CancellationException("Timeline index was refreshed")
                        if (result is Either.Right) recomputeLayout()
                        result
                    } finally {
                        inFlight.remove(request)
                    }
                }
            }
        }
        return deferred.await()
    }

    /**
     * Public way to trigger a re-layout from the fragment after config changes.
     *
     * This is needed because layout parameters (like screen width) are owned by the View layer,
     * but the heavy calculation of justified rows and D-pad neighbors is offloaded here
     * to a background thread to prevent ANRs.
     */
    fun triggerRecomputeLayout() {
        viewModelScope.launch {
            recomputeLayout()
        }
    }

    /**
     * Recomputes the entire mosaic layout and focus neighbor map.
     *
     * This operation is performed on [Dispatchers.Default] because it is computationally intensive
     * for large libraries. It handles:
     * 1. Grouping assets into [TimelineDay]s.
     * 2. Calculating justified row dimensions (preserving aspect ratios).
     * 3. Building a 2D neighbor map for D-pad navigation.
     *
     * Offloading this prevents the main thread from stalling for several seconds during
     * large jumps (e.g., using the scrubber).
     */
    private suspend fun recomputeLayout() = layoutMutex.withLock {
        withContext(Dispatchers.Default) {
            val snapshot = synchronized(stateLock) {
                Triple(indexGeneration, _bucketAssets.value, _buckets.value)
            }
            val loaded = snapshot.second
            val buckets = snapshot.third
            val memories = _memories.value
            val width = contentWidthPx
            val height = rowHeightPx
            val gap = gapPx
            val today = todayLabel
            val yesterday = yesterdayLabel

            val assets = buckets.flatMap { loaded[it.timeBucket].orEmpty() }
            val daysList = assets.toTimelineDays()
            val focusIndex = AssetFocusIndex(
                dayByAsset = buildMap {
                    daysList.forEach { day -> day.assets.forEach { put(it.id, day.dayKey) } }
                },
                bucketByAsset = buildMap {
                    buckets.forEach { bucket ->
                        loaded[bucket.timeBucket].orEmpty().forEach { put(it.id, bucket.timeBucket) }
                    }
                },
                orderedAssets = buildList {
                    daysList.forEach { day -> day.assets.forEach { add(day.dayKey to it) } }
                }
            )
            if (width <= 0 || height <= 0) {
                // Index changes must clear old rows even while the replacement view is unmeasured.
                synchronized(stateLock) {
                    if (snapshot.first == indexGeneration) {
                        assetFocusIndex = focusIndex
                        _days.value = daysList
                        _layout.value = TimelineLayout(days = daysList)
                    }
                }
                return@withContext
            }

            val items = TimelineMosaicLayoutEngine.layout(
                days = daysList,
                contentWidthPx = width,
                rowHeightPx = height,
                gapPx = gap,
                dayLabel = { day ->
                    TimelineDateFormatter.dayLabel(
                        date = day.date,
                        todayLabel = today,
                        yesterdayLabel = yesterday
                    )
                }
            )

            val neighbors = TimelineMosaicLayoutEngine.buildFocusNeighbors(items) { from, to ->
                hasUnloadedBucketBetween(from, to)
            }

            val itemsWithMemories = if (memories.isNotEmpty()) {
                listOf(TimelineMosaicItem.MemoriesRow(memories)) + items
            } else {
                items
            }

            synchronized(stateLock) {
                if (snapshot.first == indexGeneration) {
                    assetFocusIndex = focusIndex
                    _days.value = daysList
                    _layout.value = TimelineLayout(itemsWithMemories, neighbors, daysList)
                }
            }
        }
    }

    /** Ensure only the focused month is loaded; neighboring months require navigation. */
    fun prefetchAroundDay(dayKey: String) {
        Debouncer.debounce(PREFETCH_KEY, {
            if (!viewModelScope.isActive) return@debounce
            viewModelScope.launch(Dispatchers.IO) { loadFocusMonth(dayKey) }
        }, prefetchDebounceMs, TimeUnit.MILLISECONDS)
    }

    internal suspend fun loadFocusMonth(dayKey: String) {
        val monthKey = monthBucketKey(LocalDate.parse(dayKey))
        loadBucket(resolveBucketKey(monthKey) ?: monthKey)
    }

    fun flatAssetIndex(): List<Pair<String, TimelineAsset>> = assetFocusIndex.orderedAssets

    /**
     * All assets from [bucketKey] and its immediate neighbors (one newer, one older).
     * Used to seed the photo slider with a small window instead of the entire timeline.
     *
     * To keep startup fast, it only includes the older/next neighbor if the current bucket
     * has fewer than 30 assets.
     */
    fun assetsAroundBucket(bucketKey: String): List<TimelineAsset> {
        val loaded = _bucketAssets.value
        val buckets = _buckets.value
        val index = buckets.indexOfFirst { it.timeBucket == bucketKey }
        if (index < 0) return loaded[bucketKey].orEmpty()

        val current = loaded[bucketKey].orEmpty()
        val prev = buckets.getOrNull(index - 1)?.timeBucket
        val next = if (current.size < 30) buckets.getOrNull(index + 1)?.timeBucket else null

        return (loaded[prev].orEmpty() + current + loaded[next].orEmpty())
            .distinctBy { it.id }
            .sortedByDescending { it.fileCreatedAt }
    }

    fun nextUnloadedBucket(): TimeBucketSummary? {
        val loaded = _bucketAssets.value
        return _buckets.value.firstOrNull { it.timeBucket !in loaded }
    }

    /**
     * Next older unloaded month after the oldest month we already have assets for.
     *
     * Used when the mosaic is near the bottom (farther back in time). Prefer this over
     * [nextUnloadedBucket], which fills the first gap near "today" and inserts days *above*
     * the viewport — causing jumpy D-pad navigation deep in the timeline.
     */
    fun nextOlderUnloadedBucket(): TimeBucketSummary? {
        val loaded = _bucketAssets.value.keys
        if (loaded.isEmpty()) {
            return _buckets.value.firstOrNull()
        }
        val oldestLoadedIndex = _buckets.value.indexOfLast { it.timeBucket in loaded }
        if (oldestLoadedIndex < 0) {
            return _buckets.value.firstOrNull { it.timeBucket !in loaded }
        }
        return _buckets.value
            .drop(oldestLoadedIndex + 1)
            .firstOrNull { it.timeBucket !in loaded }
    }

    /**
     * Closest unloaded month newer than [fromDayKey] (toward today).
     *
     * Used when Up hits a sparse-loading gap above the focused island so we bridge
     * forward in time instead of focusing across decades into another loaded island.
     */
    fun nextNewerUnloadedBucket(fromDayKey: String): TimeBucketSummary? {
        val monthKey = monthBucketKey(LocalDate.parse(fromDayKey.take(10)))
        val resolved = resolveBucketKey(monthKey) ?: monthKey
        val list = _buckets.value
        val fromIndex = list.indexOfFirst { it.timeBucket == resolved }
        if (fromIndex <= 0) return null
        val loaded = _bucketAssets.value.keys
        // Newest-first: indices before fromIndex are newer. Prefer the closest (largest index).
        return list.take(fromIndex).lastOrNull { it.timeBucket !in loaded }
    }

    /**
     * Closest unloaded month older than [fromDayKey] (farther back in history).
     *
     * Symmetric to [nextNewerUnloadedBucket]: when Down is blocked by an island gap under the
     * focused day, load holes toward the past instead of only paging past the globally oldest
     * loaded month (which never bridges Aug → Jan when Jan is already loaded).
     */
    fun nextOlderUnloadedBucket(fromDayKey: String): TimeBucketSummary? {
        val monthKey = monthBucketKey(LocalDate.parse(fromDayKey.take(10)))
        val resolved = resolveBucketKey(monthKey) ?: monthKey
        val list = _buckets.value
        val fromIndex = list.indexOfFirst { it.timeBucket == resolved }
        if (fromIndex < 0 || fromIndex >= list.lastIndex) return null
        val loaded = _bucketAssets.value.keys
        // Newest-first: indices after fromIndex are older. Prefer the closest (smallest index).
        return list.drop(fromIndex + 1).firstOrNull { it.timeBucket !in loaded }
    }

    /**
     * True when at least one Immich month bucket between [fromDayKey] and [toDayKey] is still
     * unloaded. Missing buckets (library has no assets that month) do not count — those are
     * real chronological sparsity, not a loading island.
     */
    fun hasUnloadedBucketBetween(fromDayKey: String, toDayKey: String): Boolean {
        val from = runCatching { YearMonth.from(LocalDate.parse(fromDayKey.take(10))) }.getOrNull()
            ?: return false
        val to = runCatching { YearMonth.from(LocalDate.parse(toDayKey.take(10))) }.getOrNull()
            ?: return false
        val lo = if (from.isBefore(to)) from else to
        val hi = if (from.isBefore(to)) to else from
        if (!lo.isBefore(hi)) return false
        val loaded = _bucketAssets.value.keys
        return _buckets.value.any { bucket ->
            val ym = runCatching {
                YearMonth.from(LocalDate.parse(bucket.timeBucket.take(10)))
            }.getOrElse {
                runCatching { YearMonth.parse(bucket.timeBucket.take(7)) }.getOrNull()
            } ?: return@any false
            ym.isAfter(lo) && ym.isBefore(hi) && bucket.timeBucket !in loaded
        }
    }

    /**
     * Next month bucket after [bucketKey] in the newest-first list (older in calendar time).
     * Used by slider load-more so paging continues from the oldest asset already shown —
     * never from the first global unloaded gap near today.
     */
    fun nextBucketAfter(bucketKey: String): TimeBucketSummary? {
        val afterIndex = _buckets.value.indexOfFirst { it.timeBucket == bucketKey }
        if (afterIndex < 0) return null
        return _buckets.value.getOrNull(afterIndex + 1)
    }

    /**
     * Server-provided month bucket that owns [assetId], if that bucket is loaded.
     * Preferred over local calendar month when syncing the scrubber.
     */
    fun bucketKeyForAsset(assetId: String): String? = assetFocusIndex.bucketByAsset[assetId]

    /**
     * Resolve an Immich bucket key for a scrubber/month token. Matches by calendar
     * [YearMonth] so normalized `YYYY-MM-01` finds API keys that may differ slightly.
     */
    fun resolveBucketKey(monthKey: String): String? {
        val target = runCatching { YearMonth.from(LocalDate.parse(monthKey.take(10))) }.getOrNull()
            ?: runCatching { YearMonth.parse(monthKey.take(7)) }.getOrNull()
            ?: return null
        return _buckets.value.firstOrNull { bucket ->
            runCatching {
                YearMonth.from(LocalDate.parse(bucket.timeBucket.take(10)))
            }.getOrElse {
                runCatching { YearMonth.parse(bucket.timeBucket.take(7)) }.getOrNull()
            } == target
        }?.timeBucket
    }

    /**
     * Newest local calendar day among assets already loaded for [timeBucket].
     *
     * Immich 3.3.5 groups buckets by photographer-local month. Still use a day actually
     * returned for this bucket rather than assuming the first calendar day contains photos.
     */
    fun newestDayKeyForBucket(timeBucket: String): String? {
        val assets = _bucketAssets.value[timeBucket].orEmpty()
        if (assets.isEmpty()) return null
        return assets.maxOf { it.localCaptureDate() }.toString()
    }

    fun clearError() {
        _error.value = null
    }

    companion object {
        const val MAX_INITIAL_BUCKET_REQUESTS = 6
        const val PREFETCH_KEY = "timeline-prefetch"
        const val PREFETCH_DEBOUNCE_MS = 300L

        fun monthBucketKey(date: LocalDate): String =
            YearMonth.from(date).atDay(1).toString()
    }
}
