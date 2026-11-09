package nl.giejay.android.tv.immich.search

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.prefs.*
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.viewmodel.MediaSliderViewModel
import java.time.LocalDate

/** Independent of GenericAssetFragment: opening this page never enumerates albums or all photos. */
class PhotoSearchFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {
    private val mainAdapter = object : BrowseSupportFragment.MainFragmentAdapter<Fragment>(this) {}
    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> {
        // Leanback 1.2 assigns its host immediately after obtaining this adapter. Posting
        // here also covers assignment after onResume, without polling an absent host.
        scheduleViewReady()
        return mainAdapter
    }
    private val hostReadiness = PhotoSearchHostReadiness()
    private val resultSelection = PhotoSearchResultSelection()
    private var readyView: View? = null
    private var pendingViewReady: Runnable? = null
    private lateinit var session: ApiClientConfig
    private lateinit var model: PhotoSearchViewModel
    private val slider: MediaSliderViewModel by activityViewModels()
    private lateinit var start: Button
    private lateinit var end: Button
    private lateinit var people: Button
    private lateinit var match: Button
    private lateinit var search: Button
    private lateinit var clear: Button
    private lateinit var more: Button
    private lateinit var status: TextView
    private lateinit var grid: VerticalGridView
    private lateinit var cards: PhotoSearchGridAdapter
    private var dialog: AlertDialog? = null
    private var openingPlayer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = PreferenceManager.connectionSnapshot()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PhotoSearchViewModel(
                PhotoSearchRepository(session), session, { PreferenceManager.connectionSnapshot() }
            ) as T
        })[PhotoSearchViewModel::class.java]
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isSaveEnabled = false
            setPadding(dp(28), dp(22), dp(24), dp(12))
            setBackgroundColor(context.getColor(R.color.tv_background))
        }
        root.addView(TextView(context).apply { setText(R.string.search_filters); textSize = 25f; isSaveEnabled = false })
        root.addView(TextView(context).apply {
            text = getString(R.string.search_scope, model.state.value.query.zone.id)
            textSize = 13f; isSaveEnabled = false
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        start = searchButton(context, "", "search_start")
        end = searchButton(context, "", "search_end")
        people = searchButton(context, "", "search_people")
        match = searchButton(context, "", "search_match")
        search = searchButton(context, getString(R.string.search_run), "search_run")
        clear = searchButton(context, getString(R.string.search_clear), "search_clear")
        listOf(listOf(start, end, people), listOf(match, search, clear)).forEach { buttons ->
            val row = LinearLayout(context).apply { isSaveEnabled = false }
            buttons.forEach { row.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
            root.addView(row)
        }
        listOf(start, end, people).forEachIndexed { i, button -> button.nextFocusDownId = listOf(match, search, clear)[i].id }
        listOf(match, search, clear).forEachIndexed { i, button -> button.nextFocusUpId = listOf(start, end, people)[i].id }
        start.setOnClickListener { pickDate(true) }
        end.setOnClickListener { pickDate(false) }
        people.setOnClickListener { pickPeople() }
        match.setOnClickListener {
            val q = model.state.value.query
            model.setQuery(q.copy(match = if (q.match == PeopleMatch.ALL) PeopleMatch.ANY else PeopleMatch.ALL))
        }
        search.setOnClickListener { model.search() }
        clear.setOnClickListener { model.setQuery(PhotoSearchQuery(zone = model.state.value.query.zone)) }
        status = TextView(context).apply { textSize = 14f; isSaveEnabled = false; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(6) })
        cards = PhotoSearchGridAdapter(
            { session === PreferenceManager.connectionSnapshot() && !model.state.value.locked },
            { index -> model.state.value.items.getOrNull(index)?.let { model.selectedAssetId = it.id }; model.onFocused(index) },
            { openPlayer(it) },
            { direction, index ->
                if (direction == KeyEvent.KEYCODE_DPAD_UP) listOf(match, search, clear)[index.coerceAtMost(2)].requestFocus()
                else more.visibility == View.VISIBLE && more.requestFocus()
            }
        )
        grid = VerticalGridView(context).apply {
            id = View.generateViewId(); isSaveEnabled = false
            setNumColumns(4)
            adapter = cards
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        more = searchButton(context, getString(R.string.search_more), "search_more")
        more.setOnClickListener { model.loadNext() }
        more.nextFocusUpId = grid.id
        root.addView(more, LinearLayout.LayoutParams(-1, dp(48)))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        readyView = view
        hostReadiness.beginView(view)
        resultSelection.beginView(view)
        notifyViewReady(view)
        if (readyView !== view || this.view !== view) return
        scheduleViewReady()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.checkSession()
        readyView?.let { notifyViewReady(it) }
    }

    override fun onResume() {
        super.onResume()
        readyView?.let { notifyViewReady(it) }
        scheduleViewReady()
    }

    private fun scheduleViewReady() {
        val expectedView = readyView ?: return
        if (pendingViewReady != null) return
        val task = object : Runnable {
            override fun run() {
                if (pendingViewReady !== this) return
                pendingViewReady = null
                notifyViewReady(expectedView)
            }
        }
        pendingViewReady = task
        expectedView.post(task)
    }

    private fun notifyViewReady(expectedView: View) {
        if (readyView !== expectedView || view !== expectedView) return
        val host = mainAdapter.fragmentHost ?: return
        hostReadiness.notify(expectedView, host,
            isCurrentHost = { readyView === expectedView && view === expectedView && mainAdapter.fragmentHost === host },
            viewCreated = { host.notifyViewCreated(mainAdapter) },
            dataReady = { host.notifyDataReady(mainAdapter) },
            hideTitle = { host.showTitleView(false) }
        )
    }

    private fun render(state: PhotoSearchState) {
        val expectedView = readyView ?: return
        if (view !== expectedView) return
        notifyViewReady(expectedView)
        if (readyView !== expectedView || view !== expectedView) return
        if (!model.checkSession()) {
            cards.submit(emptyList(), model.queryGeneration)
            status.setText(R.string.search_locked)
            listOf(start, end, people, match, search, clear, more).forEach { it.isEnabled = false }
            return
        }
        val q = state.query
        start.text = getString(R.string.search_start, q.start?.toString() ?: getString(R.string.search_any_date))
        end.text = getString(R.string.search_end, q.endInclusive?.toString() ?: getString(R.string.search_any_date))
        people.text = getString(R.string.search_people, q.people.size)
        match.setText(if (q.match == PeopleMatch.ALL) R.string.search_match_all else R.string.search_match_any)
        // Keep the focused control reachable while busy; the ViewModel rejects repeated
        // Search/Continue taps. Disabling it would let Leanback pick an unrelated result.
        search.isEnabled = !openingPlayer && q.valid && q.hasCondition
        status.text = when {
            openingPlayer -> getString(R.string.search_opening)
            state.failure != null -> getString(R.string.search_loaded, state.items.size) + " · " + failureText(state.failure)
            state.loading -> getString(R.string.search_loading, state.items.size)
            !q.valid -> getString(R.string.search_invalid_dates)
            !q.hasCondition -> getString(R.string.search_choose_condition)
            !state.started -> getString(R.string.search_ready)
            state.items.isEmpty() && !state.canLoadMore -> getString(R.string.search_empty)
            else -> getString(R.string.search_loaded, state.items.size) + if (!state.canLoadMore) " · " + getString(R.string.search_end_results) else ""
        }
        val expectedGeneration = model.queryGeneration
        val expectedCards = cards
        val expectedGrid = grid
        resultSelection.beginQuery(expectedView, expectedGeneration)
        cards.submit(state.items, expectedGeneration) { commit ->
            expectedGrid.post {
                fun isCurrentCommit() = readyView === expectedView && view === expectedView && cards === expectedCards && grid === expectedGrid &&
                    expectedCards.isCurrentSubmission(commit) && model.queryGeneration == expectedGeneration && model.checkSession()
                if (!isCurrentCommit()) return@post
                notifyViewReady(expectedView)
                if (!isCurrentCommit()) return@post
                val position = resultSelection.positionAfterCommit(
                    expectedView, expectedGeneration, state.items.map { it.id }, model.selectedAssetId
                )
                // Position only. Never steal focus from filters, the sidebar, or Continue.
                if (position != null) expectedGrid.setSelectedPosition(position)
            }
        }
        val showContinue = PhotoSearchResultSelection.showContinue(state.started, state.canLoadMore)
        if (!showContinue && more.hasFocus()) {
            (if (search.isEnabled) search else start).requestFocus()
        }
        more.visibility = if (showContinue) View.VISIBLE else View.GONE
        more.nextFocusUpId = if (state.items.isEmpty()) search.id else grid.id
        more.isEnabled = !openingPlayer
        more.setText(if (state.failure != null) R.string.search_retry else R.string.search_more)
        val nextTarget = when {
            state.items.isNotEmpty() -> grid.id
            showContinue -> more.id
            else -> View.NO_ID
        }
        listOf(match, search, clear).forEach { it.nextFocusDownId = nextTarget }
    }

    private fun pickDate(isStart: Boolean) {
        if (!model.checkSession() || dialog != null) return
        val query = model.state.value.query
        val picker = TvDateSelector(requireContext(), (if (isStart) query.start else query.endInclusive) ?: LocalDate.now(query.zone))
        val popup = AlertDialog.Builder(requireContext())
            .setTitle(if (isStart) R.string.search_start_title else R.string.search_end_title).setView(picker).create()
        fun choose(date: LocalDate?) {
            val current = model.state.value.query
            model.setQuery(if (isStart) current.copy(start = date) else current.copy(endInclusive = date))
            popup.dismiss()
        }
        picker.applyButton.setOnClickListener { choose(picker.date) }
        picker.clearButton.setOnClickListener { choose(null) }
        picker.cancelButton.setOnClickListener { popup.dismiss() }
        popup.setOnDismissListener { dialog = null }
        dialog = popup
        popup.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        popup.show()
        popup.window?.setLayout(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT)
        picker.findViewWithTag<Button>("date_plus_0").requestFocus()
    }

    private fun pickPeople() {
        if (!model.checkSession() || dialog != null) return
        val selected = model.state.value.query.people.toMutableSet()
        val content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; isSaveEnabled = false; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val hint = TextView(requireContext()).apply { isSaveEnabled = false; textSize = 14f }
        val list = ListView(requireContext()).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE; isSaveEnabled = false; id = View.generateViewId() }
        val names = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_multiple_choice, mutableListOf<String>()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).apply { isSaveEnabled = false }
        }
        list.adapter = names
        val next = searchButton(requireContext(), getString(R.string.search_people_more), "search_people_more")
        content.addView(hint)
        content.addView(list, LinearLayout.LayoutParams(-1, dp(240)))
        content.addView(next, LinearLayout.LayoutParams(-1, dp(48)))
        next.setOnClickListener { model.loadPeople() }
        list.setOnItemClickListener { _, _, position, _ ->
            model.people.value.items.getOrNull(position)?.id?.let { id ->
                if (list.isItemChecked(position)) selected.add(id) else selected.remove(id)
            }
        }
        val popup = AlertDialog.Builder(requireContext()).setTitle(R.string.search_people_title).setView(content)
            .setPositiveButton(R.string.search_apply) { _, _ -> model.setQuery(model.state.value.query.copy(people = selected.toSet())) }
            .setNeutralButton(R.string.search_clear) { _, _ -> model.setQuery(model.state.value.query.copy(people = emptySet())) }
            .setNegativeButton(android.R.string.cancel, null).create()
        var watch: Job? = null
        popup.setOnDismissListener { watch?.cancel(); dialog = null }
        dialog = popup
        popup.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        popup.show()
        popup.window?.setLayout(dp(600), ViewGroup.LayoutParams.WRAP_CONTENT)
        watch = viewLifecycleOwner.lifecycleScope.launch {
            model.people.collect { state ->
                if (!model.checkSession()) { popup.dismiss(); return@collect }
                val labels = state.items.map { it.name.orEmpty() }
                if (names.count != labels.size || labels.indices.any { names.getItem(it) != labels[it] }) {
                    val position = list.firstVisiblePosition
                    names.clear(); names.addAll(labels); names.notifyDataSetChanged()
                    labels.indices.forEach { list.setItemChecked(it, state.items[it].id in selected) }
                    if (position > 0) list.setSelection(position)
                }
                hint.text = when {
                    state.failure != null -> failureText(state.failure)
                    state.loading -> getString(R.string.search_people_loading)
                    state.items.isEmpty() -> getString(if (state.hasNextPage) R.string.search_people_no_names_yet else R.string.search_people_empty)
                    else -> getString(R.string.search_people_hint)
                }
                next.visibility = if (state.hasNextPage) View.VISIBLE else View.GONE
                next.isEnabled = !state.loading
                next.setText(if (state.failure != null) R.string.search_retry else R.string.search_people_more)
            }
        }
        if (!model.people.value.started) model.loadPeople()
    }

    private fun openPlayer(asset: Asset) {
        if (!model.checkSession() || openingPlayer) return
        val state = model.state.value
        val generation = model.queryGeneration
        val assets = state.items
        if (assets.none { it.id == asset.id }) return
        model.selectedAssetId = asset.id
        openingPlayer = true
        render(state)
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            try {
                // The player is a fixed snapshot of loaded results. It cannot load another page.
                val items = withContext(Dispatchers.Default) { assets.toSliderItems(keepOrder = true, mergePortrait = false) }
                if (!isAdded || !model.checkSession() || model.queryGeneration != generation || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                slider.configuration = MediaSliderConfiguration(
                    startPosition = items.indexOfFirst { it.ids().contains(asset.id) }.coerceAtLeast(0),
                    interval = PreferenceManager.get(SLIDER_INTERVAL),
                    isOnlyUseThumbnails = PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
                    isVideoSoundEnable = true, items = items, loadMore = null,
                    animationSpeedMillis = PreferenceManager.get(SLIDER_ANIMATION_SPEED),
                    maxCutOffHeight = PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
                    maxCutOffWidth = PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
                    glideTransformation = PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
                    gradiantOverlay = false,
                    enableSlideAnimation = PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
                    metaDataConfig = PreferenceManager.getAllMetaData(MetaDataScreen.VIEWER),
                    zoomAndScrollPanorama = PreferenceManager.get(SLIDER_ZOOM_SCROLL_PANORAMAS),
                    zoomEffectPercent = PreferenceManager.get(SLIDER_ZOOM_EFFECT),
                    panEffectPercent = PreferenceManager.get(SLIDER_PAN_EFFECT),
                    useLargeVideoBuffer = PreferenceManager.get(SLIDER_FORCE_ORIGINAL_VIDEO),
                    dpadSeeksInVideo = PreferenceManager.get(SLIDER_DPAD_SEEK_IN_VIDEO),
                    showDateTopLeft = PreferenceManager.get(SLIDER_SHOW_DATE_TOP_LEFT)
                )
                findNavController().navigate(HomeFragmentDirections.actionToPhotoSlider())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                openingPlayer = false
                if (isAdded && view != null) {
                    render(model.state.value)
                    if (failed && model.checkSession()) status.setText(R.string.search_invalid_response)
                }
            }
        }
    }

    private fun failureText(failure: SearchFailure) = getString(when (failure) {
        SearchFailure.PERMISSION -> R.string.search_permission
        SearchFailure.PEOPLE_PERMISSION -> R.string.search_people_permission
        SearchFailure.UNSUPPORTED -> R.string.search_unsupported
        SearchFailure.VERSION_UNAVAILABLE -> R.string.search_version_unavailable
        SearchFailure.INVALID_RESPONSE -> R.string.search_invalid_response
        SearchFailure.REPEATED_CURSOR -> R.string.search_repeated_cursor
        SearchFailure.CONNECTION -> R.string.search_connection_error
    })

    override fun onStop() {
        dialog?.dismiss()
        model.pause()
        super.onStop()
    }

    override fun onDestroyView() {
        // Invalidate first: dismiss/detach and host callbacks may synchronously re-enter.
        val oldView = readyView
        readyView = null
        pendingViewReady?.let { oldView?.removeCallbacks(it) }
        pendingViewReady = null
        oldView?.let { hostReadiness.destroyView(it); resultSelection.destroyView(it) }
        cards.invalidate()
        dialog?.dismiss()
        grid.adapter = null
        super.onDestroyView()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
