package nl.giejay.android.tv.immich.timeline

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrandedSupportFragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.BrowseFrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import arrow.core.Either
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.album.AlbumDetailsFragmentDirections
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.model.Memory
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.MetaDataScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ANIMATE_ASSET_SLIDE
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ANIMATION_SPEED
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_FORCE_ORIGINAL_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_GLIDE_TRANSFORMATION
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_DPAD_SEEK_IN_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_HEIGHT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_WIDTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ONLY_USE_THUMBNAILS
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_PAN_EFFECT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_SHOW_DATE_TOP_LEFT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ZOOM_EFFECT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ZOOM_SCROLL_PANORAMAS
import nl.giejay.android.tv.immich.shared.fragment.VerticalCardGridFragment
import nl.giejay.android.tv.immich.shared.util.Debouncer
import nl.giejay.android.tv.immich.shared.util.Utils.pmap
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.util.LoadMore
import nl.giejay.mediaslider.util.LoadMoreResult
import nl.giejay.mediaslider.viewmodel.MediaSliderViewModel
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Vertical Immich-style mosaic timeline: day headers + justified asset rows,
 * with a right-edge year/month scrubber. Up/Down previews a month; Enter jumps while staying
 * on the scrubber; Left jumps and returns focus to the mosaic.
 */
class TimelineFragment : BrandedSupportFragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private lateinit var apiClient: ApiClient
    private lateinit var viewModel: TimelineViewModel
    private val sliderViewModel: MediaSliderViewModel by activityViewModels()

    private var recyclerView: RecyclerView? = null
    private var statusView: View? = null
    private var statusMessage: TextView? = null
    private var retryButton: Button? = null
    private var progressBar: ProgressBar? = null
    private var scrubberView: TimelineScrubberView? = null
    private var mosaicAdapter: TimelineMosaicAdapter? = null
    private var focusNavigator: TimelineFocusNavigator? = null
    private var focusVideoPreview: TimelineFocusVideoPreview? = null

    private var selectionRestored = false
    private val hostReadiness = TimelineHostReadiness()
    private var restoreTargetJob: Job? = null
    private var restoreTargetAssetId: String? = null
    private var restoreFailedAssetId: String? = null
    private var loadingNextBucket = false
    private var loadingNewerBucket = false
    private var contentWidthPx = 0
    private var contentWidthListener: View.OnLayoutChangeListener? = null
    private var lastStatus: TimelineStatus? = null
    private var rowHeightPx = 0
    private var gapPx = 0

    /** Skip rememberSelection while we re-land focus after the menu. */
    private var ignoreSelectionMemory = false

    /** Month key waiting for assets after a scrubber jump (`YYYY-MM-01`). */
    private var pendingJumpMonth: String? = null
    /** After a committed scrubber jump, focus a mosaic cell once that month is scrolled into view. */
    private var pendingJumpFocus = false
    /**
     * Keep re-applying the jump scroll while the scrubber stays focused after Enter.
     * Neighbor bucket loads insert newer months above the target (newest-first), which would
     * otherwise leave February visible after an Enter jump to January.
     */
    private var stickyJumpBucketKey: String? = null
    /** True after Up/Down on the scrubber while focused — Left should commit a jump. */
    private var scrubberPreviewMoved = false

    private val mainFragmentAdapter: BrowseSupportFragment.MainFragmentAdapter<Fragment> =
        object : BrowseSupportFragment.MainFragmentAdapter<Fragment>(this) {}

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> =
        mainFragmentAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (PreferenceManager.isLoggedId()) {
            apiClient = ApiClient.getClient(
                ApiClientConfig.fromPrefs()
            )
        } else {
            Toast.makeText(
                ImmichApplication.appContext,
                "Invalid Immich server settings, redirecting to login screen.",
                Toast.LENGTH_SHORT
            ).show()
            findNavController().navigate(HomeFragmentDirections.actionGlobalSignInFragment())
            return
        }

        // Root graph survives the photo viewer, but is replaced on lock/profile switch.
        // Activity scope would retain the previous account's timeline and API client.
        viewModel = ViewModelProvider(
            findNavController().getBackStackEntry(R.id.nav_graph),
            TimelineViewModelFactory(apiClient)
        )[TimelineViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.timeline_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!::viewModel.isInitialized) return
        clearBackground()
        // Fragment host is usually still null here; hide again after notifyViewCreated.
        hideBrowseTitle()

        progressBar = view.findViewById(R.id.browse_progressbar)
        statusView = view.findViewById(R.id.timeline_status)
        statusMessage = view.findViewById(R.id.timeline_status_message)
        retryButton = view.findViewById<Button>(R.id.timeline_retry).apply {
            setOnClickListener {
                restoreFailedAssetId = null
                selectionRestored = false
                viewLifecycleOwner.lifecycleScope.launch {
                    if (viewModel.initialSearchPending.value && viewModel.error.value == null) {
                        viewModel.continueInitialLoad()
                    } else {
                        viewModel.loadBucketList(refresh = true)
                    }
                }
            }
        }
        recyclerView = view.findViewById(R.id.timeline_recycler)
        scrubberView = view.findViewById(R.id.timeline_scrubber)

        gapPx = dp(6)
        rowHeightPx = dp(180)

        scrubberView?.onCommit = { monthKey, exitToMosaic ->
            commitJumpFromScrubber(monthKey, exitToMosaic = exitToMosaic)
        }
        scrubberView?.onPreviewMoved = {
            stickyJumpBucketKey = null
            scrubberPreviewMoved = true
        }

        focusVideoPreview = TimelineFocusVideoPreview(requireContext())

        val adapter = TimelineMosaicAdapter(
            gapPx = gapPx,
            onCellClick = { cell ->
                viewModel.rememberSelection(cell.dayKey, cell.asset.id)
                onItemClicked(cell.asset.id)
            },
            onCellFocus = { cell, cellView ->
                viewModel.prefetchAroundDay(cell.dayKey)
                // Don't overwrite sticky leave-off while the menu owns focus or before restore.
                if (!ignoreSelectionMemory &&
                    !isBrowseShowingHeaders() &&
                    TimelineLeaveOff.shouldUpdateLiveLeaveOff(selectionRestored)
                ) {
                    viewModel.lastSelectedMemoryId = null
                    focusNavigator?.onCellFocused(cell.dayKey, cell.asset.id)
                    viewModel.rememberSelection(cell.dayKey, cell.asset.id)
                    syncScrubberToAsset(cell.asset.id, cell.dayKey)
                }
                focusVideoPreview?.onCellFocus(cell, cellView)
            },
            onCellBlur = { cell, _ ->
                focusVideoPreview?.onCellBlur(cell)
            },
            onCellDetached = { cellView ->
                focusVideoPreview?.onHostDetached(cellView)
            },
            onCellKey = { v, keyCode, event ->
                focusNavigator?.onCellKey(v, keyCode, event) == true
            },
            onMemoryClicked = { memory -> onMemoryClicked(memory) },
            onMemoryFocused = { memory ->
                if (!ignoreSelectionMemory &&
                    !isBrowseShowingHeaders() &&
                    TimelineLeaveOff.shouldUpdateLiveLeaveOff(selectionRestored)
                ) {
                    viewModel.lastSelectedMemoryId = memory.id
                }
            }
        )
        mosaicAdapter = adapter

        val rv = recyclerView!!
        rv.layoutManager = TimelineMosaicLayoutManager(requireContext())
        rv.adapter = adapter
        rv.setHasFixedSize(false)
        rv.itemAnimator = null

        focusNavigator = TimelineFocusNavigator(
            rv,
            adapter,
            onFocused = { dayKey, assetId ->
                viewModel.prefetchAroundDay(dayKey)
                if (!ignoreSelectionMemory &&
                    !isBrowseShowingHeaders() &&
                    TimelineLeaveOff.shouldUpdateLiveLeaveOff(selectionRestored)
                ) {
                    viewModel.lastSelectedMemoryId = null
                    viewModel.rememberSelection(dayKey, assetId)
                }
                maybeLoadMoreNearEnd()
            },
            onExitRightToScrubber = { focusScrubberFromMosaic() },
            // Bridge unload holes under focus only when Down is blocked — not on every
            // focus/scroll (that eagerly loads mid-timeline and makes the mosaic creep).
            onReachContentEnd = {
                if (!maybeBridgeOlderFromFocus()) {
                    maybeLoadMoreNearEnd()
                }
            },
            onReachContentStart = { maybeLoadMoreNearStart() }
        )

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                maybeLoadMoreNearEnd()
                updateScrubberFromVisibleMonth()
            }
        })

        val frame = view.findViewById<BrowseFrameLayout>(R.id.timeline_frame)
        frame.onFocusSearchListener = BrowseFrameLayout.OnFocusSearchListener { focused, direction ->
            if (focused is TimelineScrubberView) {
                return@OnFocusSearchListener if (direction == View.FOCUS_LEFT) {
                    focused.selectedMonthKey()?.let {
                        commitJumpFromScrubber(it, exitToMosaic = true)
                    }
                    focused
                } else {
                    focused
                }
            }
            if (focused?.getTag(R.id.timeline_mosaic_cell_asset_id) == null) {
                return@OnFocusSearchListener null
            }
            val assetId = focused.getTag(R.id.timeline_mosaic_cell_asset_id) as? String
                ?: return@OnFocusSearchListener null
            val n = focusNavigator?.neighbors?.get(assetId) ?: return@OnFocusSearchListener null
            val targetId = when (direction) {
                View.FOCUS_LEFT -> n.leftAssetId
                View.FOCUS_RIGHT -> n.rightAssetId
                View.FOCUS_UP -> n.upAssetId
                View.FOCUS_DOWN -> n.downAssetId
                else -> null
            }
            when {
                targetId != null -> {
                    focusNavigator?.focusAsset(targetId, adjustScroll = false, lockScroll = false)
                    focused
                }
                direction == View.FOCUS_RIGHT -> {
                    focusScrubberFromMosaic()
                    focused
                }
                direction == View.FOCUS_UP &&
                    mosaicAdapter?.hasMemoriesRow() == true &&
                    mosaicAdapter?.isInFirstMosaicRow(assetId) == true -> {
                    focusNavigator?.focusMemoriesRow()
                    focused
                }
                direction == View.FOCUS_DOWN -> focused
                else -> null
            }
        }

        // Our loading/error UI is ready now. Leanback must not hide this page until the API finishes.
        notifyTimelineViewReady()
        // Host is attached by now; keep Browse from flashing/animating the "Timeline" title.
        hideBrowseTitle()

        (parentFragment as? BrowseSupportFragment)?.setBrowseTransitionListener(
            object : BrowseSupportFragment.BrowseTransitionListener() {
                override fun onHeadersTransitionStart(withHeaders: Boolean) {
                    if (!isAdded) return
                    if (withHeaders) {
                        // Opening menu — remember the mosaic cell before Leanback steals focus.
                        captureResumeState()
                        beginResumeFocusLock()
                    } else if (
                        viewModel.pendingResumeAssetId != null ||
                        viewModel.lastSelectedMemoryId != null ||
                        viewModel.lastSelectedAssetId != null
                    ) {
                        // Re-entering with a prior cell — block children so Leanback cannot flash
                        // a neighboring item before we hand focus back.
                        beginResumeFocusLock()
                    }
                }

                override fun onHeadersTransitionStop(withHeaders: Boolean) {
                    if (!isAdded) return
                    if (withHeaders) {
                        // Snapshot already taken on start — don't recapture with focus gone.
                        selectionRestored = false
                    } else {
                        hideBrowseTitle()
                        restoreSelectionIfNeeded()
                    }
                }
            }
        )

        var initialMeasuredWidth = true
        contentWidthListener = rv.observeTimelineContentWidth { measuredWidth ->
            contentWidthPx = measuredWidth
            // Recreated while the menu is already open (e.g. Photos → Timeline): never auto-enter.
            // Leave-off was captured when the user left Timeline; do not wipe it with a null focus.
            if (initialMeasuredWidth && isBrowseShowingHeaders()) {
                beginResumeFocusLock()
                selectionRestored = false
            }
            initialMeasuredWidth = false

            viewModel.setLayoutConfig(
                widthPx = contentWidthPx,
                rowHeightPx = rowHeightPx,
                gapPx = gapPx,
                todayLabel = getString(R.string.today),
                yesterdayLabel = getString(R.string.yesterday)
            )
            viewModel.triggerRecomputeLayout()
            lastStatus?.let(::renderStatus)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.layout.collect { layout ->
                        bindLayout(layout)
                    }
                }
                launch {
                    combine(viewModel.isLoading, viewModel.initialLoadComplete,
                        viewModel.error, viewModel.layout, viewModel.initialSearchPending) { loading, complete, error, layout, pending ->
                        TimelineStatus(loading, complete, error, layout.days.any { it.assets.isNotEmpty() }, pending)
                    }.collect { renderStatus(it) }
                }
                launch {
                    viewModel.buckets.collect { buckets ->
                        scrubberView?.setBuckets(buckets)
                    }
                }
                launch {
                    viewModel.memoriesReady.collect { ready ->
                        // Empty/failed memories may leave layout equal, so layout alone cannot
                        // wake a restore waiting for an old memory card to disappear.
                        if (ready && viewModel.lastSelectedMemoryId != null) {
                            selectionRestored = false
                            restoreSelectionIfNeeded()
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            viewModel.loadBucketList()
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            viewModel.loadMemories()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::viewModel.isInitialized) return
        notifyTimelineViewReady() // Also covers a Browse host attached after onViewCreated.
        clearBackground()
        // Leanback may restore focus to the opened mosaic cell before leave-off runs.
        if (TimelineLeaveOff.shouldForceLeaveOffRestore(viewModel.pendingResumeAssetId)) {
            selectionRestored = false
        }
        restoreSelectionIfNeeded()
    }

    override fun onPause() {
        focusVideoPreview?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        // Do not captureResumeState here — focus is already cleared and would wipe a sticky
        // memory/mosaic leave-off set when opening the slider. Menu open already snapshots.
        (parentFragment as? BrowseSupportFragment)?.setBrowseTransitionListener(null)
        focusVideoPreview?.release()
        focusVideoPreview = null
        contentWidthListener?.let { recyclerView?.removeOnLayoutChangeListener(it) }
        contentWidthListener = null
        contentWidthPx = 0
        lastStatus = null
        hostReadiness.reset()
        restoreTargetJob?.cancel()
        restoreTargetJob = null
        restoreTargetAssetId = null
        restoreFailedAssetId = null
        selectionRestored = false
        super.onDestroyView()
        recyclerView = null
        progressBar = null
        loadingNextBucket = false
        loadingNewerBucket = false
        statusView = null
        statusMessage = null
        retryButton = null
        scrubberView = null
        mosaicAdapter = null
        focusNavigator = null
        pendingJumpMonth = null
        pendingJumpFocus = false
        stickyJumpBucketKey = null
        scrubberPreviewMoved = false
    }

    private data class TimelineStatus(
        val loading: Boolean, val complete: Boolean, val error: String?, val hasAssets: Boolean,
        val moreMonthsPending: Boolean
    )

    private fun notifyTimelineViewReady() {
        if (view == null) return
        val host = mainFragmentAdapter.fragmentHost ?: return
        hostReadiness.notify(host,
            viewCreated = { host.notifyViewCreated(mainFragmentAdapter) },
            dataReady = { host.notifyDataReady(mainFragmentAdapter) }
        )
    }

    private fun renderStatus(state: TimelineStatus) {
        lastStatus = state
        val loading = state.loading || contentWidthPx <= 0
        progressBar?.visibility = if (loading) View.VISIBLE else View.GONE
        val showStatus = !loading && state.complete && (state.error != null || !state.hasAssets)
        statusView?.visibility = if (showStatus) View.VISIBLE else View.GONE
        retryButton?.isEnabled = !loading
        retryButton?.setText(if (state.moreMonthsPending && state.error == null) R.string.timeline_continue else R.string.timeline_retry)
        if (!loading && state.complete && !selectionRestored) restoreSelectionIfNeeded()
        if (!showStatus) return
        // Keep already loaded photos usable when a later month fails.
        (statusView?.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.gravity = if (state.hasAssets) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            statusView?.layoutParams = params
        }
        statusMessage?.setText(when {
            state.error == null && state.moreMonthsPending -> R.string.timeline_more_months
            state.error == null -> R.string.timeline_empty
            state.error.contains("403") || state.error.contains("required permission", ignoreCase = true) -> R.string.timeline_permission_error
            state.error.contains("401") -> R.string.timeline_auth_error
            state.error.contains("404") -> R.string.timeline_endpoint_error
            state.error.contains("400") -> R.string.timeline_version_error
            else -> R.string.timeline_load_error
        })
        notifyTimelineViewReady()
        if (!state.hasAssets && !isBrowseShowingHeaders()) retryButton?.requestFocus()
    }

    private fun clearBackground() {
        Debouncer.cancel(VerticalCardGridFragment.BACKGROUND_DEBOUNCE_KEY)
        val backgroundManager = BackgroundManager.getInstance(activity)
        if (!backgroundManager.isAttached) {
            backgroundManager.attach(requireActivity().window)
        }
        backgroundManager.drawable = ColorDrawable(
            ContextCompat.getColor(requireContext(), R.color.gray_dark)
        )
    }

    /** Enter jumps and keeps scrubber focus; Left jumps then moves focus into the mosaic. */
    private fun commitJumpFromScrubber(monthKey: String, exitToMosaic: Boolean) {
        if (!isAdded) return
        val bucketKey = viewModel.resolveBucketKey(monthKey) ?: monthKey

        // Accidental scrubber focus: Left returns to the current mosaic cell without jumping.
        if (exitToMosaic && !scrubberPreviewMoved && stickyJumpBucketKey == null) {
            scrubberPreviewMoved = false
            pendingJumpMonth = null
            pendingJumpFocus = false
            returnFocusToLastMosaicCell()
            return
        }

        // Enter already scrolled the mosaic; Left should only hand off focus — no second scroll.
        if (exitToMosaic && stickyJumpBucketKey == bucketKey) {
            stickyJumpBucketKey = null
            scrubberPreviewMoved = false
            pendingJumpMonth = null
            pendingJumpFocus = false
            returnFocusFromScrubber()
            return
        }

        scrubberPreviewMoved = false
        pendingJumpMonth = bucketKey
        pendingJumpFocus = exitToMosaic
        // Enter: keep re-anchoring after neighbor loads insert content above the target.
        stickyJumpBucketKey = if (exitToMosaic) null else bucketKey
        scrubberView?.setIndicatorMonthKey(bucketKey)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            viewModel.loadBucket(bucketKey)
            // A jump loads only its requested month. Adjacent months wait for actual scrolling.
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                // Adapter may lag StateFlow by a frame; try now and again after layout.
                if (!attemptPendingJumpScroll()) {
                    recyclerView?.post { attemptPendingJumpScroll() }
                }
            }
        }
    }

    /** @return true if the pending scrubber jump scrolled successfully. */
    private fun attemptPendingJumpScroll(): Boolean {
        val month = pendingJumpMonth ?: return false
        if (!scrollToMonth(month)) return false
        finishJumpFocus()
        return true
    }

    /** Re-apply Enter jump after adapter updates so neighbor inserts don't shift the viewport. */
    private fun reanchorStickyJumpIfNeeded() {
        val key = stickyJumpBucketKey ?: return
        if (scrubberView?.hasFocus() != true) {
            stickyJumpBucketKey = null
            return
        }
        scrollToMonth(key)
    }

    private fun scrollToMonth(monthKey: String): Boolean {
        val adapter = mosaicAdapter ?: return false
        val rv = recyclerView ?: return false
        // Prefer a local day that actually belongs to this Immich UTC month bucket.
        val preferredDay = viewModel.newestDayKeyForBucket(monthKey)
        val position = adapter.positionForScrubberMonth(monthKey, preferredDay)
        if (position == RecyclerView.NO_POSITION) return false
        pendingJumpMonth = null
        val lm = rv.layoutManager as? LinearLayoutManager ?: return false
        lm.scrollToPositionWithOffset(position, dp(24))
        scrubberView?.setIndicatorMonthKey(monthKey)
        // Enter keeps focus on the scrubber — update leave-off so Back→menu restores here.
        rememberLeaveOffForScrubberMonth(monthKey)
        return true
    }

    /**
     * Scrubber Enter does not focus a mosaic cell, so [onAssetFocused] never runs.
     * Pin leave-off to the jump target (right-most cell of the newest day in that month).
     */
    private fun rememberLeaveOffForScrubberMonth(monthKey: String) {
        val target = leaveOffTargetForScrubberMonth(monthKey) ?: return
        viewModel.lastSelectedMemoryId = null
        viewModel.rememberSelection(target.first, target.second)
    }

    /** @return dayKey to assetId for the natural scrubber entry cell, if loaded. */
    private fun leaveOffTargetForScrubberMonth(monthKey: String?): Pair<String, String>? {
        if (monthKey.isNullOrBlank()) return null
        val adapter = mosaicAdapter ?: return null
        val bucketKey = viewModel.resolveBucketKey(monthKey) ?: monthKey
        val preferredDay = viewModel.newestDayKeyForBucket(bucketKey)
            ?: viewModel.flatAssetIndex()
                .firstOrNull { it.first.startsWith(monthKey.take(7)) }
                ?.first
            ?: return null
        val assetId = adapter.rightmostAssetIdForDay(preferredDay) ?: return null
        return preferredDay to assetId
    }

    private fun finishJumpFocus() {
        if (!pendingJumpFocus) return
        pendingJumpFocus = false
        stickyJumpBucketKey = null
        scrubberPreviewMoved = false
        returnFocusFromScrubber()
    }

    private fun focusScrubberFromMosaic() {
        scrubberPreviewMoved = false
        val focused = recyclerView?.findFocus()
        val assetId = focused?.getTag(R.id.timeline_mosaic_cell_asset_id) as? String
            ?: viewModel.lastSelectedAssetId
        val dayKey = focused?.getTag(R.id.timeline_mosaic_cell_day_key) as? String
            ?: viewModel.lastSelectedDayKey
        val monthKey = when {
            assetId != null -> viewModel.bucketKeyForAsset(assetId)
            dayKey != null -> runCatching {
                TimelineViewModel.monthBucketKey(LocalDate.parse(dayKey))
            }.getOrNull()
            else -> null
        }
        scrubberView?.prepareFocusForMonth(monthKey)
        scrubberView?.requestFocus()
    }

    /** Restore the cell that had focus before the scrubber — no scroll. */
    private fun returnFocusToLastMosaicCell() {
        val navigator = focusNavigator ?: return
        val assetId = viewModel.lastSelectedAssetId ?: run {
            returnFocusFromScrubber()
            return
        }
        val position = mosaicAdapter?.positionOfAsset(assetId) ?: RecyclerView.NO_POSITION
        if (position >= 0) {
            navigator.focusAsset(assetId, adjustScroll = false, lockScroll = true)
            return
        }
        returnFocusFromScrubber()
    }

    /**
     * Hand focus to the right-most mosaic cell of the jump target day (closest to the scrubber),
     * without changing scroll.
     */
    private fun returnFocusFromScrubber() {
        val navigator = focusNavigator ?: return
        val monthKey = scrubberView?.selectedMonthKey()
        val adapter = mosaicAdapter ?: return

        if (monthKey != null) {
            val bucketKey = viewModel.resolveBucketKey(monthKey) ?: monthKey
            val preferredDay = viewModel.newestDayKeyForBucket(bucketKey)
                ?: viewModel.flatAssetIndex()
                    .firstOrNull { it.first.startsWith(monthKey.take(7)) }
                    ?.first
            val assetId = preferredDay?.let { adapter.rightmostAssetIdForDay(it) }
            if (assetId != null) {
                navigator.focusAsset(assetId, adjustScroll = false, lockScroll = true)
                return
            }
        }
        adapter.firstRowRightmostAssetId()?.let {
            navigator.focusAsset(it, adjustScroll = false, lockScroll = true)
        }
    }

    private fun syncScrubberToAsset(assetId: String, dayKey: String) {
        if (scrubberView?.hasFocus() == true) return
        val bucketKey = viewModel.bucketKeyForAsset(assetId)
            ?: runCatching {
                TimelineViewModel.monthBucketKey(LocalDate.parse(dayKey))
            }.getOrNull()
        scrubberView?.setIndicatorMonthKey(bucketKey)
    }

    private fun updateScrubberFromVisibleMonth() {
        if (scrubberView?.hasFocus() == true) return
        val rv = recyclerView ?: return
        val adapter = mosaicAdapter ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val dayKey = adapter.dayKeyAtAdapterPosition(first) ?: return
        val assetId = adapter.firstAssetIdAtAdapterPosition(first)
        if (assetId != null) {
            syncScrubberToAsset(assetId, dayKey)
        } else {
            scrubberView?.setIndicatorMonthKey(
                TimelineViewModel.monthBucketKey(LocalDate.parse(dayKey))
            )
        }
    }

    /**
     * Binds a pre-calculated layout to the mosaic RecyclerView.
     *
     * This avoids performing layout and neighbor calculation on the main thread.
     * It also handles anchoring the scroll position to the currently focused or
     * last selected asset so that background loads don't cause the viewport to jump.
     */
    private fun bindLayout(layout: TimelineLayout) {
        if (!isAdded) return
        val adapter = mosaicAdapter ?: return
        val days = layout.days
        val itemsWithMemories = layout.items
        focusNavigator?.updateNeighbors(layout.neighbors)

        // Anchor scroll to the focused/selected cell so inserting older/newer months in
        // recomputeLayout doesn't yank the viewport when deep in the timeline.
        val rv = recyclerView
        val lm = rv?.layoutManager as? LinearLayoutManager
        val anchorAssetId = rv?.findFocus()?.getTag(R.id.timeline_mosaic_cell_asset_id) as? String
            ?: viewModel.lastSelectedAssetId
        val anchorPos = when {
            anchorAssetId != null -> adapter.positionOfAsset(anchorAssetId)
            else -> lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        }
        val anchorOffset = if (anchorPos != RecyclerView.NO_POSITION) {
            lm?.findViewByPosition(anchorPos)?.top ?: 0
        } else {
            0
        }

        adapter.submitList(itemsWithMemories) {
            if (!isAdded || recyclerView !== rv || mosaicAdapter !== adapter) return@submitList
            if (days.isNotEmpty()) {
                notifyTimelineViewReady()
                hideBrowseTitle()
            }

            val layoutManager = recyclerView?.layoutManager as? LinearLayoutManager
            val deferAnchor = TimelineLeaveOff.shouldDeferBindAnchorScroll(
                allowScrollAdjust = viewModel.leaveOffAllowScrollAdjust
            )
            if (anchorAssetId != null &&
                layoutManager != null &&
                !deferAnchor &&
                scrubberView?.hasFocus() != true
            ) {
                val newPos = adapter.positionOfAsset(anchorAssetId)
                if (newPos != RecyclerView.NO_POSITION) {
                    layoutManager.scrollToPositionWithOffset(newPos, anchorOffset)
                }
            }

            when {
                // Side menu owns focus — never requestFocus into the mosaic (auto-enters Timeline).
                isBrowseShowingHeaders() -> {
                    selectionRestored = false
                    beginResumeFocusLock()
                }
                scrubberView?.hasFocus() == true -> {
                    selectionRestored = true
                }
                // Slider leave-off wins over Leanback restoring focus to the *opened* cell.
                TimelineLeaveOff.shouldForceLeaveOffRestore(viewModel.pendingResumeAssetId) -> {
                    selectionRestored = false
                    restoreSelectionIfNeeded()
                }
                anchorAssetId != null &&
                    adapter.positionOfAsset(anchorAssetId) >= 0 &&
                    recyclerView?.findFocus()?.getTag(R.id.timeline_mosaic_cell_asset_id) != null -> {
                    // Already browsing the mosaic; rebind kept a focused cell — retain it.
                    selectionRestored = true
                    setFocusScrollSuppressed(true)
                    val cell = adapter.findCellView(recyclerView!!, anchorAssetId)
                    if (cell != null) {
                        cell.requestFocus()
                        setFocusScrollSuppressed(false)
                    } else {
                        focusNavigator?.focusAsset(
                            anchorAssetId,
                            adjustScroll = false,
                            lockScroll = true
                        )
                    }
                }
                else -> {
                    selectionRestored = false
                    restoreSelectionIfNeeded()
                }
            }
            if (!attemptPendingJumpScroll()) {
                reanchorStickyJumpIfNeeded()
            }
        }
    }

    private fun captureResumeState() {
        val focused = recyclerView?.findFocus()
        val focusedMosaicAssetId = focused?.getTag(R.id.timeline_mosaic_cell_asset_id) as? String
        // Scrubber Enter leaves focus on the rail — treat the jumped month as mosaic leave-off.
        val scrubberLeaveOffAssetId =
            if (focusedMosaicAssetId.isNullOrBlank() && scrubberView?.hasFocus() == true) {
                leaveOffTargetForScrubberMonth(scrubberView?.selectedMonthKey())?.second
            } else {
                null
            }
        viewModel.applyLeaveOffSnapshot(
            TimelineLeaveOff.captureForMenu(
                focusedMemoryId = focused?.getTag(R.id.timeline_memory_id) as? String,
                focusedMosaicAssetId = focusedMosaicAssetId ?: scrubberLeaveOffAssetId,
                // Left from a memory steals focus before this runs — keep the sticky id.
                stickyMemoryId = viewModel.lastSelectedMemoryId,
                lastAssetId = viewModel.lastSelectedAssetId
            )
        )
    }

    private fun setFocusScrollSuppressed(suppressed: Boolean) {
        (recyclerView?.layoutManager as? TimelineMosaicLayoutManager)?.suppressFocusScroll = suppressed
    }

    private fun beginResumeFocusLock() {
        ignoreSelectionMemory = true
        setFocusScrollSuppressed(true)
        val rv = recyclerView ?: return
        rv.isFocusable = true
        rv.isFocusableInTouchMode = true
        // Children cannot take focus — Leanback may park on the recycler itself (no cell flash).
        rv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        scrubberView?.isFocusable = false
    }

    private fun finishResumeFocusLock() {
        ignoreSelectionMemory = false
        setFocusScrollSuppressed(false)
        recyclerView?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        scrubberView?.isFocusable = true
        scrubberView?.isFocusableInTouchMode = true
    }

    private fun restoreSelectionIfNeeded() {
        if (!isAdded) return
        // Pending slider leave-off must re-run even if a prior bind retained the open cell.
        if (selectionRestored &&
            !TimelineLeaveOff.shouldForceLeaveOffRestore(viewModel.pendingResumeAssetId)
        ) {
            return
        }
        val navigator = focusNavigator ?: return
        val adapter = mosaicAdapter ?: return

        // Never pull focus while the side menu is still open — wait until the user
        // presses Enter/Right (BrowseTransitionListener → restoreSelectionIfNeeded).
        if (isBrowseShowingHeaders()) return

        when (val target = TimelineLeaveOff.resolveRestore(viewModel.leaveOffSnapshot())) {
            is TimelineLeaveOff.Target.Memory -> {
                when (TimelineRestorePolicy.memoryAction(
                    hasAdapterRow = adapter.hasMemoriesRow(),
                    hasMemories = viewModel.memories.value.isNotEmpty(),
                    memoriesReady = viewModel.memoriesReady.value
                )) {
                    TimelineRestorePolicy.MemoryAction.Wait -> {
                        // Unlock navigation, but keep restore pending for the adapter commit.
                        selectionRestored = false
                        finishResumeFocusLock()
                        return
                    }
                    TimelineRestorePolicy.MemoryAction.Forget -> {
                        viewModel.lastSelectedMemoryId = null
                        selectionRestored = false
                        finishResumeFocusLock()
                        restoreSelectionIfNeeded()
                        return
                    }
                    TimelineRestorePolicy.MemoryAction.Focus -> Unit
                }
                selectionRestored = true
                val rv = recyclerView ?: return
                setFocusScrollSuppressed(true)
                rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                scrubberView?.isFocusable = false
                rv.scrollToPosition(0)
                fun tryFocus() {
                    if (!isAdded || recyclerView !== rv) return
                    if (!adapter.focusMemory(rv, target.id)) {
                        rv.post {
                            if (isAdded && recyclerView === rv) adapter.focusMemory(rv, target.id)
                        }
                    }
                }
                rv.post { tryFocus() }
                rv.post {
                    if (isAdded && recyclerView === rv) finishResumeFocusLock()
                }
                return
            }
            is TimelineLeaveOff.Target.Mosaic -> {
                val assetId = target.assetId
                val position = adapter.positionOfAsset(assetId)
                if (position < 0) {
                    recoverMissingAssetTarget(assetId)
                    return
                }

                selectionRestored = true
                val rv = recyclerView ?: return
                setFocusScrollSuppressed(true)
                rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                scrubberView?.isFocusable = false
                val lm = rv.layoutManager as? LinearLayoutManager
                val cell = adapter.findCellView(rv, assetId)
                val first = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                val last = lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
                val visible = first != RecyclerView.NO_POSITION && position in first..last
                // Land on the leave-off asset (last viewed in the slider, or the one just
                // opened). Do not reinstate open-time LayoutManager state — that fights
                // scrubber jumps and adapter changes while the slider was open.
                when (
                    TimelineLeaveOff.mosaicFocusMode(
                        cellBound = cell != null,
                        rowVisible = visible,
                        allowScrollAdjust = target.allowScrollAdjust
                    )
                ) {
                    TimelineLeaveOff.MosaicFocusMode.RequestFocusOnly -> cell!!.requestFocus()
                    TimelineLeaveOff.MosaicFocusMode.AdjustScrollIntoView ->
                        navigator.focusAsset(assetId, adjustScroll = true, lockScroll = true)
                    TimelineLeaveOff.MosaicFocusMode.BindWithoutAdjust ->
                        navigator.focusAsset(assetId, adjustScroll = false, lockScroll = true)
                }
                viewModel.pendingResumeAssetId = null
                viewModel.rememberSelectionByAssetId(assetId)
                // Hold scroll-suppress through focus scale so Leanback cannot inch the page.
                // Keep allowScrollAdjust=false until unlock so bindDays cannot yank focus away.
                rv.postDelayed({
                    if (!isAdded || recyclerView !== rv) return@postDelayed
                    viewModel.leaveOffAllowScrollAdjust = true
                    finishResumeFocusLock()
                }, TimelineLeaveOff.MOSAIC_FOCUS_LOCK_RELEASE_MS)
                return
            }
            TimelineLeaveOff.Target.None -> Unit
        }

        // Optional memories never block photos that are already ready for D-pad navigation.
        if (!TimelineRestorePolicy.hasFocusableContent(adapter.hasMemoriesRow(), adapter.firstAssetId() != null)) {
            finishResumeFocusLock()
            if (statusView?.visibility == View.VISIBLE) retryButton?.requestFocus()
            return
        }

        selectionRestored = true
        finishResumeFocusLock()
        val ownerView = recyclerView
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isAdded || recyclerView !== ownerView) return@postDelayed
            if (isBrowseShowingHeaders()) {
                selectionRestored = false
                return@postDelayed
            }
            focusInitialEntry()
        }, 100)
    }

    private fun recoverMissingAssetTarget(assetId: String) {
        finishResumeFocusLock()
        // The adapter can lag the ViewModel by one diff/layout. Do not re-fetch known data.
        if (viewModel.bucketKeyForAsset(assetId) != null) return
        if (restoreFailedAssetId == assetId) {
            if (statusView?.visibility == View.VISIBLE) retryButton?.requestFocus()
            return
        }
        if (restoreTargetJob?.isActive == true && restoreTargetAssetId == assetId) return
        val month = viewModel.lastSelectedDayKey?.let { day ->
            runCatching { TimelineViewModel.monthBucketKey(LocalDate.parse(day)) }.getOrNull()
        }
        val bucketKey = viewModel.lastSelectedBucketKey?.takeIf { key -> viewModel.buckets.value.any { it.timeBucket == key } }
            ?: month?.let(viewModel::resolveBucketKey)
        if (bucketKey == null) {
            // Only a completed, successful index can establish that this month disappeared.
            if (!viewModel.initialLoadComplete.value || viewModel.isLoading.value) return
            if (viewModel.error.value != null) {
                preserveFailedRestoreTarget(assetId)
            } else {
                forgetMissingAssetTarget(assetId)
                restoreSelectionIfNeeded()
            }
            return
        }

        val ownerView = recyclerView ?: return
        restoreTargetJob?.cancel()
        restoreTargetAssetId = assetId
        val job = viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(Dispatchers.IO) { viewModel.loadBucket(bucketKey) }
                if (!isAdded || recyclerView !== ownerView) return@launch
                val currentTarget = TimelineLeaveOff.resolveRestore(viewModel.leaveOffSnapshot())
                if (currentTarget !is TimelineLeaveOff.Target.Mosaic || currentTarget.assetId != assetId) return@launch
                val succeeded = result is Either.Right
                val found = result.fold({ false }, { assets -> assets.any { it.id == assetId } })
                if (TimelineRestorePolicy.shouldForgetMissingAsset(succeeded, found)) {
                    forgetMissingAssetTarget(assetId)
                    restoreSelectionIfNeeded()
                } else if (!succeeded) {
                    preserveFailedRestoreTarget(assetId)
                } else {
                    restoreSelectionIfNeeded()
                }
            } catch (cancelled: CancellationException) {
                if (recyclerView === ownerView) finishResumeFocusLock()
                throw cancelled
            } finally {
                if (restoreTargetJob === coroutineContext[Job]) {
                    restoreTargetJob = null
                    restoreTargetAssetId = null
                }
            }
        }
        restoreTargetJob = job
        job.start()
    }

    private fun forgetMissingAssetTarget(assetId: String) {
        if (viewModel.pendingResumeAssetId == assetId) viewModel.pendingResumeAssetId = null
        if (viewModel.lastSelectedAssetId == assetId) {
            viewModel.lastSelectedAssetId = null
            viewModel.lastSelectedDayKey = null
        }
        restoreFailedAssetId = null
        selectionRestored = false
        finishResumeFocusLock()
    }

    private fun preserveFailedRestoreTarget(assetId: String) {
        restoreFailedAssetId = assetId
        // Stop forcing a failed restore, but keep its target until retry or deliberate navigation.
        if (viewModel.pendingResumeAssetId == assetId) viewModel.pendingResumeAssetId = null
        viewModel.lastSelectedAssetId = assetId
        selectionRestored = true
        finishResumeFocusLock()
        if (statusView?.visibility == View.VISIBLE) retryButton?.requestFocus()
    }

    private fun isBrowseShowingHeaders(): Boolean =
        (parentFragment as? BrowseSupportFragment)?.isShowingHeaders == true

    /** Timeline has no page title — hide immediately so Leanback cannot flash/animate one. */
    private fun hideBrowseTitle() {
        showTitle(false)
        mainFragmentAdapter.fragmentHost?.showTitleView(false)
        (parentFragment as? BrowseSupportFragment)?.showTitle(false)
    }

    /** Prefer the first memory card; fall back to the first mosaic cell. */
    private fun focusInitialEntry() {
        val rv = recyclerView ?: return
        val adapter = mosaicAdapter ?: return
        if (adapter.hasMemoriesRow()) {
            if (adapter.focusFirstMemory(rv)) return
            rv.scrollToPosition(0)
            rv.post {
                if (!isAdded || recyclerView !== rv) return@post
                if (adapter.focusFirstMemory(rv)) return@post
                adapter.firstAssetId()?.let {
                    focusNavigator?.focusAsset(it, adjustScroll = true)
                }
            }
            return
        }
        adapter.firstAssetId()?.let { focusNavigator?.focusAsset(it, adjustScroll = true) }
    }

    /**
     * When Down has no neighbor (island gap under focus), load the closest unloaded older
     * month under that day — same role as [maybeLoadMoreNearStart] for Up.
     * @return true if a load was started (or already in flight).
     */
    private fun maybeBridgeOlderFromFocus(): Boolean {
        if (loadingNextBucket) return true
        val rv = recyclerView ?: return false
        val focused = rv.findFocus() ?: return false
        val assetId = focused.getTag(R.id.timeline_mosaic_cell_asset_id) as? String ?: return false
        // Still movable Down — do not fill remote holes under an ordinary mid-timeline cell.
        if (focusNavigator?.neighbors?.get(assetId)?.downAssetId != null) return false
        val dayKey = focused.getTag(R.id.timeline_mosaic_cell_day_key) as? String
            ?: viewModel.lastSelectedDayKey
            ?: return false
        val next = viewModel.nextOlderUnloadedBucket(dayKey) ?: return false
        loadingNextBucket = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            withTimelineViewLoad(rv, { recyclerView },
                load = { viewModel.loadBucket(next.timeBucket) },
                finished = { loadingNextBucket = false }
            )
        }
        return true
    }

    private fun maybeLoadMoreNearEnd() {
        if (loadingNextBucket) return
        val rv = recyclerView ?: return
        val adapter = mosaicAdapter ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = lm.findLastVisibleItemPosition()
        // Count rows, not date headings: a single busy day can contain thousands of rows.
        val focusedPos = rv.findFocus()?.let { focused ->
            val id = focused.getTag(R.id.timeline_mosaic_cell_asset_id) as? String
            id?.let { adapter.positionOfAsset(it) }
        } ?: RecyclerView.NO_POSITION
        if (!TimelineLoadPolicy.nearEnd(lastVisible, focusedPos, adapter.itemCount)) return

        // Extend farther back in time — never fill newest-end gaps while at the oldest end.
        val next = viewModel.nextOlderUnloadedBucket() ?: return
        loadingNextBucket = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            withTimelineViewLoad(rv, { recyclerView },
                load = { viewModel.loadBucket(next.timeBucket) },
                finished = { loadingNextBucket = false }
            )
        }
    }

    /**
     * When Up has no safe neighbor (calendar gap above the focused island), load the closest
     * unloaded newer month so focus can walk forward continuously instead of teleports.
     * @return true if a load was started (or already in flight).
     */
    private fun maybeLoadMoreNearStart(): Boolean {
        if (loadingNewerBucket) return true
        val rv = recyclerView ?: return false
        val dayKey = rv.findFocus()?.getTag(R.id.timeline_mosaic_cell_day_key) as? String
            ?: viewModel.lastSelectedDayKey
            ?: return false
        val next = viewModel.nextNewerUnloadedBucket(dayKey) ?: return false
        loadingNewerBucket = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            withTimelineViewLoad(rv, { recyclerView },
                load = { viewModel.loadBucket(next.timeBucket) },
                finished = { loadingNewerBucket = false }
            )
        }
        return true
    }

    private fun onItemClicked(assetId: String) {
        // Scrubber Enter jump must not re-anchor after we leave for the slider.
        stickyJumpBucketKey = null
        pendingJumpMonth = null
        pendingJumpFocus = false
        selectionRestored = false
        viewModel.applyLeaveOffSnapshot(TimelineLeaveOff.afterOpeningMosaic(assetId))
        viewModel.rememberSelectionByAssetId(assetId)

        val bucketKey = viewModel.bucketKeyForAsset(assetId) ?: return

        progressBar?.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.Default) {
            // Seed with just the current bucket and its neighbors for fast startup.
            val initialAssets = viewModel.assetsAroundBucket(bucketKey)
            if (initialAssets.isEmpty()) return@launch

            // Slideshow entries may outlive the bounded timeline month cache. Keep only
            // their return coordinates here, so Back can reload the exact evicted month.
            val sliderPositions = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
            initialAssets.forEach { asset ->
                viewModel.bucketKeyForAsset(asset.id)?.let { key ->
                    sliderPositions[asset.id] = asset.localCaptureDate().toString() to key
                }
            }
            val sliderItems = initialAssets.pmap { it.toSliderItemViewHolder() }
            val startIndex = sliderItems.indexOfFirst { it.ids().contains(assetId) }.coerceAtLeast(0)

            // Tracks the newest bucket currently in the slider so we can page forwards.
            var lastBucketKey: String? = initialAssets.lastOrNull()?.let { asset ->
                viewModel.bucketKeyForAsset(asset.id)
            }

            val loadMore: LoadMore = suspend loadMore@{
                val afterKey = lastBucketKey ?: return@loadMore LoadMoreResult(emptyList(), false)
                val next = viewModel.nextBucketAfter(afterKey) ?: return@loadMore LoadMoreResult(emptyList(), false)
                val knownIds = initialAssets.map { it.id }.toSet()
                viewModel.loadBucket(next.timeBucket)
                lastBucketKey = next.timeBucket
                val items = viewModel.bucketAssets.value[next.timeBucket].orEmpty()
                    .filter { it.id !in knownIds }
                    .onEach { asset -> sliderPositions[asset.id] = asset.localCaptureDate().toString() to next.timeBucket }
                    .pmap { it.toSliderItemViewHolder() }
                LoadMoreResult(items, items.isNotEmpty() && viewModel.nextBucketAfter(next.timeBucket) != null)
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                progressBar?.visibility = View.GONE
                val config = MediaSliderConfiguration(
                    startIndex,
                    PreferenceManager.get(SLIDER_INTERVAL),
                    PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
                    isVideoSoundEnable = true,
                    sliderItems,
                    loadMore,
                    { item ->
                        // Keep leave-off on the asset the user is currently viewing so Back
                        // lands on that cell — not the one that opened the slider.
                        viewModel.applyLeaveOffSnapshot(
                            TimelineLeaveOff.afterOpeningMosaic(item.mainItem.id)
                        )
                        sliderPositions[item.mainItem.id]?.let { (day, month) ->
                            viewModel.rememberSelection(day, item.mainItem.id, month)
                        } ?: viewModel.rememberSelectionByAssetId(item.mainItem.id)
                    },
                    animationSpeedMillis = PreferenceManager.get(SLIDER_ANIMATION_SPEED),
                    maxCutOffHeight = PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
                    maxCutOffWidth = PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
                    glideTransformation = PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
                    enableSlideAnimation = PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
                    gradiantOverlay = false,
                    metaDataConfig = PreferenceManager.getAllMetaData(MetaDataScreen.VIEWER)
                        .filter { it.type != MetaDataType.MEDIA_COUNT },
                    zoomAndScrollPanorama = PreferenceManager.get(SLIDER_ZOOM_SCROLL_PANORAMAS),
                    zoomEffectPercent = PreferenceManager.get(SLIDER_ZOOM_EFFECT),
                    panEffectPercent = PreferenceManager.get(SLIDER_PAN_EFFECT),
                    useLargeVideoBuffer = PreferenceManager.get(SLIDER_FORCE_ORIGINAL_VIDEO),
                    dpadSeeksInVideo = PreferenceManager.get(SLIDER_DPAD_SEEK_IN_VIDEO),
                    showDateTopLeft = PreferenceManager.get(SLIDER_SHOW_DATE_TOP_LEFT)
                )
                sliderViewModel.configuration = config
                findNavController().navigate(
                    AlbumDetailsFragmentDirections.actionToPhotoSlider()
                )
            }
        }
    }

    /** Opens a memory's assets in the slider, auto-playing — bounded set, no [LoadMore]. */
    private fun onMemoryClicked(memory: Memory) {
        if (memory.assets.isEmpty()) return
        selectionRestored = false
        viewModel.applyLeaveOffSnapshot(
            TimelineLeaveOff.afterOpeningMemory(memory.id, viewModel.lastSelectedAssetId)
        )
        val sliderItems = memory.assets.toSliderItems(
            keepOrder = true,
            // Memory payloads omit EXIF/dimensions; portrait pairing is unreliable and can
            // bind the wrong asset's city/country into the details strip.
            mergePortrait = false
        )
        val config = MediaSliderConfiguration(
            0,
            PreferenceManager.get(SLIDER_INTERVAL),
            PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
            isVideoSoundEnable = true,
            sliderItems,
            null,
            animationSpeedMillis = PreferenceManager.get(SLIDER_ANIMATION_SPEED),
            maxCutOffHeight = PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
            maxCutOffWidth = PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
            glideTransformation = PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
            enableSlideAnimation = PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
            gradiantOverlay = false,
            metaDataConfig = PreferenceManager.getAllMetaData(MetaDataScreen.VIEWER)
                .filter { it.type != MetaDataType.MEDIA_COUNT },
            zoomAndScrollPanorama = PreferenceManager.get(SLIDER_ZOOM_SCROLL_PANORAMAS),
            zoomEffectPercent = PreferenceManager.get(SLIDER_ZOOM_EFFECT),
            panEffectPercent = PreferenceManager.get(SLIDER_PAN_EFFECT),
            useLargeVideoBuffer = PreferenceManager.get(SLIDER_FORCE_ORIGINAL_VIDEO),
            dpadSeeksInVideo = PreferenceManager.get(SLIDER_DPAD_SEEK_IN_VIDEO),
            showDateTopLeft = PreferenceManager.get(SLIDER_SHOW_DATE_TOP_LEFT)
        )
        sliderViewModel.configuration = config
        findNavController().navigate(
            AlbumDetailsFragmentDirections.actionToPhotoSlider(
                timelineView = true
            )
        )
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).roundToInt()

}

class TimelineViewModelFactory(
    private val apiClient: ApiClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TimelineViewModel::class.java)) {
            return TimelineViewModel(
                fetchBuckets = { apiClient.getTimeBuckets() },
                fetchBucket = { apiClient.getTimeBucket(it) },
                fetchMemories = { apiClient.getMemories() }
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
