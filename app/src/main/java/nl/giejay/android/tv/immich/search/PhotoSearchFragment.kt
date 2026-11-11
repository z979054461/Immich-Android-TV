package nl.giejay.android.tv.immich.search

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import java.util.UUID
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
    private lateinit var previous: Button
    private lateinit var album: Button
    private lateinit var city: Button
    private lateinit var tag: Button
    private lateinit var fileName: Button
    private lateinit var filterRows: List<List<Button>>
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
        album = searchButton(context, "", "library_album")
        city = searchButton(context, "", "library_city")
        tag = searchButton(context, "", "library_tag")
        fileName = searchButton(context, "", "library_filename")
        filterRows = listOf(listOf(start, end, people), listOf(album, city, tag), listOf(fileName, match, search, clear))
        filterRows.forEach { buttons ->
            val row = LinearLayout(context).apply { isSaveEnabled = false }
            buttons.forEach { row.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
            root.addView(row)
        }
        filterRows.forEachIndexed { rowIndex, row -> row.forEachIndexed { column, button ->
            filterRows.getOrNull(rowIndex + 1)?.let { button.nextFocusDownId = it[column.coerceAtMost(it.lastIndex)].id }
            filterRows.getOrNull(rowIndex - 1)?.let { button.nextFocusUpId = it[column.coerceAtMost(it.lastIndex)].id }
        } }
        album.setOnClickListener { pickLibrary(LibraryChoiceKind.ALBUM) }
        city.setOnClickListener { pickLibrary(LibraryChoiceKind.CITY) }
        tag.setOnClickListener { pickLibrary(LibraryChoiceKind.TAG) }
        fileName.setOnClickListener { pickFileName() }
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
                if (direction == KeyEvent.KEYCODE_DPAD_UP) filterRows.last()[index.coerceAtMost(filterRows.last().lastIndex)].requestFocus()
                else when { more.visibility == View.VISIBLE -> more.requestFocus(); previous.visibility == View.VISIBLE -> previous.requestFocus(); else -> false }
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
        previous = searchButton(context, getString(R.string.library_previous_page), "library_previous")
        previous.setOnClickListener { model.loadPrevious() }
        previous.nextFocusUpId = grid.id
        val paging = LinearLayout(context).apply { isSaveEnabled = false }
        paging.addView(previous, LinearLayout.LayoutParams(0, dp(48), 1f))
        paging.addView(more, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(paging, LinearLayout.LayoutParams(-1, dp(48)))
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
            (filterRows.flatten() + listOf(more, previous)).forEach { it.isEnabled = false }
            return
        }
        val q = state.query
        start.text = getString(R.string.search_start, q.start?.toString() ?: getString(R.string.search_any_date))
        end.text = getString(R.string.search_end, q.endInclusive?.toString() ?: getString(R.string.search_any_date))
        people.text = getString(R.string.search_people, q.people.size)
        album.text = getString(R.string.library_album, getString(if (q.album == null) R.string.library_any else R.string.library_selected))
        city.text = getString(R.string.library_city, q.city ?: getString(R.string.library_any))
        tag.text = getString(R.string.library_tag, getString(if (q.tag == null) R.string.library_any else R.string.library_selected))
        fileName.text = getString(R.string.library_filename, q.fileNamePrefix ?: getString(R.string.library_any))
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
        val expectedGeneration = model.queryGeneration * 1_000_003L + state.pageNumber
        val expectedQueryGeneration = model.queryGeneration
        val expectedCards = cards
        val expectedGrid = grid
        resultSelection.beginQuery(expectedView, expectedGeneration)
        cards.submit(state.items, expectedGeneration) { commit ->
            expectedGrid.post {
                fun isCurrentCommit() = readyView === expectedView && view === expectedView && cards === expectedCards && grid === expectedGrid &&
                    expectedCards.isCurrentSubmission(commit) && model.queryGeneration == expectedQueryGeneration && model.state.value.pageNumber == state.pageNumber && model.checkSession()
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
        more.setText(if (state.failure != null && !state.retryPrevious) R.string.search_retry else R.string.library_next_page)
        if (!state.canLoadPrevious && previous.hasFocus()) search.requestFocus()
        previous.visibility = if (state.canLoadPrevious) View.VISIBLE else View.GONE
        previous.isEnabled = !openingPlayer
        previous.setText(if (state.failure != null && state.retryPrevious) R.string.search_retry else R.string.library_previous_page)
        previous.nextFocusUpId = if (state.items.isEmpty()) search.id else grid.id
        if (state.started) status.text = getString(R.string.library_page_status, state.pageNumber) + " · " + status.text
        val nextTarget = when {
            state.items.isNotEmpty() -> grid.id
            showContinue -> more.id
            state.canLoadPrevious -> previous.id
            else -> View.NO_ID
        }
        filterRows.last().forEach { it.nextFocusDownId = nextTarget }
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

    private fun privateTextInput() = EditText(requireContext()).apply {
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        setSingleLine(true)
    }

    private fun pickFileName() {
        if (!model.checkSession() || dialog != null) return
        val input = privateTextInput().apply {
            setText(model.state.value.query.fileNamePrefix.orEmpty())
            hint = getString(R.string.library_filename_hint)
        }
        val popup = AlertDialog.Builder(requireContext()).setTitle(R.string.library_filename_title).setView(input)
            .setPositiveButton(R.string.search_apply) { _, _ ->
                model.setQuery(model.state.value.query.copy(fileNamePrefix = input.text.toString().trim().ifEmpty { null }))
            }
            .setNeutralButton(R.string.search_clear) { _, _ -> model.setQuery(model.state.value.query.copy(fileNamePrefix = null)) }
            .setNegativeButton(android.R.string.cancel, null).create()
        popup.setOnDismissListener { input.text.clear(); dialog = null }
        dialog = popup
        popup.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        popup.show()
        popup.window?.setLayout(dp(600), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Directory metadata only; choosing an entry requests a single asset page from this NAS. */
    private fun pickLibrary(kind: LibraryChoiceKind) {
        if (!model.checkSession() || dialog != null) return
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; isSaveEnabled = false
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val input = privateTextInput().apply { hint = getString(R.string.library_filter_names) }
        val hint = TextView(requireContext()).apply { isSaveEnabled = false; setText(R.string.library_loading) }
        val list = ListView(requireContext()).apply { isSaveEnabled = false }
        val labels = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1, mutableListOf<String>()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).apply { isSaveEnabled = false }
        }
        list.adapter = labels
        content.addView(input); content.addView(hint)
        content.addView(list, LinearLayout.LayoutParams(-1, dp(240)))
        val retry = searchButton(requireContext(), getString(R.string.search_retry), "library_retry")
        retry.visibility = View.GONE
        content.addView(retry, LinearLayout.LayoutParams(-1, dp(48)))
        var choices = emptyList<LibraryChoice>()
        var visible = emptyList<LibraryChoice>()
        var loaded = false
        var job: Job? = null
        fun applyChoice(choice: LibraryChoice?) {
            if (!model.checkSession()) return
            val q = model.state.value.query
            model.setQuery(when (kind) {
                LibraryChoiceKind.ALBUM -> q.copy(album = choice?.key?.let(UUID::fromString))
                LibraryChoiceKind.TAG -> q.copy(tag = choice?.key?.let(UUID::fromString))
                LibraryChoiceKind.CITY -> q.copy(city = choice?.key)
            })
            if (choice != null) model.search()
        }
        val title = when (kind) {
            LibraryChoiceKind.ALBUM -> R.string.library_album_title
            LibraryChoiceKind.CITY -> R.string.library_city_title
            LibraryChoiceKind.TAG -> R.string.library_tag_title
        }
        val popup = AlertDialog.Builder(requireContext()).setTitle(title).setView(content)
            .setNeutralButton(R.string.search_clear) { _, _ -> applyChoice(null) }
            .setNegativeButton(android.R.string.cancel, null).create()
        fun renderChoices() {
            val matches = filterLibraryChoices(choices, input.text.toString())
            visible = matches.take(200)
            labels.clear(); labels.addAll(visible.map { it.label }); labels.notifyDataSetChanged()
            if (loaded) hint.text = when {
                matches.isEmpty() -> getString(R.string.library_no_matches)
                matches.size > visible.size -> getString(R.string.library_narrow_names, visible.size, matches.size)
                else -> getString(R.string.library_choice_count, matches.size)
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderChoices() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            visible.getOrNull(position)?.let { applyChoice(it); popup.dismiss() }
        }
        fun load() {
            if (job?.isActive == true || !model.checkSession()) return
            hint.setText(R.string.library_loading); retry.visibility = View.GONE
            job = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = model.choices(kind)
                    if (dialog !== popup || !model.checkSession()) return@launch
                    choices = result; loaded = true; renderChoices()
                    if (visible.isNotEmpty()) list.requestFocus()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (dialog === popup && model.checkSession()) {
                        hint.text = failureText((error as? SearchException)?.failure ?: SearchFailure.CONNECTION)
                        retry.visibility = View.VISIBLE
                    }
                }
            }
        }
        retry.setOnClickListener { load() }
        popup.setOnDismissListener { job?.cancel(); choices = emptyList(); visible = emptyList(); labels.clear(); input.text.clear(); dialog = null }
        dialog = popup
        popup.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        popup.show()
        popup.window?.setLayout(dp(650), ViewGroup.LayoutParams.WRAP_CONTENT)
        load()
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
