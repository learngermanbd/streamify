package com.streamify.app.ui.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.data.models.SearchResults
import com.streamify.app.data.prefs.PlayerPrefs
import com.streamify.app.databinding.FragmentSearchBinding
import com.streamify.app.ui.adapters.SearchAdapter
import com.streamify.app.ui.adapters.SearchItem
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.util.PlayerNavigation
import com.streamify.app.ui.viewmodels.CategoriesViewModel
import com.streamify.app.ui.viewmodels.MainViewModel
import com.streamify.app.ui.viewmodels.SearchViewModel
import kotlinx.coroutines.launch

/**
 * Phase 5 · Step 5.6 + Step 5.7 — Search screen.
 *
 * Layout split:
 *  - Persistent header row: search EditText + clear-text IconButton +
 *    back IconButton.
 *  - "Recent searches" chip strip: visible while [query] is blank AND the
 *    user has at least one history entry.
 *  - Result RecyclerView: visible while [query] is non-blank.
 *
 * Phase 5.7 — channels are now in the search surface. Three sections
 * render under the EditText (CHANNELS / MATCHES / HIGHLIGHTS) when a
 * non-blank query produces hits in two or more lists. Sections with no
 * hits are skipped so a precise query doesn't show empty section
 * headers.
 *
 * Sources (Phase 5.7 widening):
 *  - `mainVm: MainViewModel`     → events + highlights from MainSnapshot
 *  - `catVm: CategoriesViewModel` → channels from CategoriesSnapshot.allChannels
 *  Both are activity-scoped via `activityViewModels { ... }` so the
 *  instance survives Fragment recreation (and both VMs are still
 *  usable from the Categories tab since they aren't replaced
 *  per-Fragment).
 *
 * Filtering: case-insensitive substring on each shape's primary +
 * secondary text fields — handled by `SearchResults.filter(...)`.
 *
 * Click routing: each row goes through [PlayerNavigation] — same
 * factory methods the Home / Categories / Highlights fragments call.
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val safeBinding: FragmentSearchBinding?
        get() = _binding

    private val mainVm: MainViewModel by activityViewModels {
        val app = requireActivity().application as StreamifyApp
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(app.repository.mainRepository) as T
        }
    }

    /**
     * Phase 5.7 — Channels join the search surface. Activity-scoped
     * via [activityViewModels] so the channel list is loaded once for
     * the whole MainActivity (Categories tab + Search tab share the
     * same instance).
     */
    private val catVm: CategoriesViewModel by activityViewModels {
        val app = requireActivity().application as StreamifyApp
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                CategoriesViewModel(app.repository.mainRepository) as T
        }
    }

    private val searchVm: SearchViewModel by viewModels {
        val app = requireActivity().application as StreamifyApp
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(
                    app = app,
                    mainVm = mainVm,
                    catVm = catVm,
                    prefs = PlayerPrefs(app.applicationContext),
                ) as T
        }
    }

    private lateinit var resultsAdapter: SearchAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        resultsAdapter = SearchAdapter(
            onEventClick = { event ->
                searchVm.recordSearch(binding.searchQueryInput.text?.toString().orEmpty())
                PlayerNavigation.startPlayerForEvent(requireContext(), event.id, event.title)
            },
            onChannelClick = { channel ->
                // Phase 5.7 — channels row click now reaches the
                // standard player launcher because channels are
                // first-class search results.
                searchVm.recordSearch(binding.searchQueryInput.text?.toString().orEmpty())
                PlayerNavigation.startPlayerForChannel(
                    requireContext(),
                    channel.id,
                    channel.name
                )
            },
            onHighlightClick = { highlight ->
                searchVm.recordSearch(binding.searchQueryInput.text?.toString().orEmpty())
                PlayerNavigation.startPlayerForVideo(
                    context = requireContext(),
                    videoUrl = highlight.videoUrl,
                    title = highlight.title
                )
            }
        )
        binding.searchResultsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.searchResultsRecycler.adapter = resultsAdapter
        binding.searchResultsRecycler.setHasFixedSize(false)

        binding.searchQueryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchVm.setQuery(s?.toString().orEmpty())
                binding.searchClearButton.isVisible = !s.isNullOrEmpty()
            }
        })
        binding.searchQueryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchVm.recordSearch(binding.searchQueryInput.text?.toString().orEmpty())
                hideKeyboardSoft()
                true
            } else {
                false
            }
        }
        binding.searchClearButton.setOnClickListener {
            binding.searchQueryInput.setText("")
            binding.searchQueryInput.requestFocus()
            showKeyboardSoft()
        }
        binding.searchBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.searchClearRecentButton.setOnClickListener {
            searchVm.clearRecent()
        }

        // Phase 5.7 — kick the CategoriesVM refresh if SearchFragment
        // is the FIRST activity tab the user lands on (Categories tab
        // hasn't run its own onViewCreated yet → state.value is Idle).
        if (catVm.state.value is UiState.Idle) {
            catVm.refresh()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchVm.results.collect { state -> renderResults(state) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchVm.recent.collect { recents ->
                    renderRecentChips(recents)
                    applyVisibility()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchVm.query.collect { q ->
                    binding.searchRecentEmptyText.isVisible =
                        q.isBlank() && binding.searchRecentChipGroup.childCount == 0
                    applyVisibility()
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.searchResultsRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun renderResults(state: UiState<SearchResults>) {
        val b = safeBinding ?: return
        when (state) {
            is UiState.Idle, is UiState.Loading -> {
                resultsAdapter.submitList(emptyList())
                b.searchEmptyText.isVisible = false
                b.searchProgress.isVisible =
                    state is UiState.Loading && searchVm.query.value.isNotBlank()
            }
            is UiState.Success -> {
                val items = mutableListOf<SearchItem>()
                val res = state.value
                if (res.channels.isNotEmpty()) {
                    items += SearchItem.Header(getString(R.string.search_section_channels))
                    items += res.channels.map { SearchItem.ChannelRow(it) }
                }
                if (res.events.isNotEmpty()) {
                    items += SearchItem.Header(getString(R.string.search_section_events))
                    items += res.events.map { SearchItem.EventRow(it) }
                }
                if (res.highlights.isNotEmpty()) {
                    items += SearchItem.Header(getString(R.string.search_section_highlights))
                    items += res.highlights.map { SearchItem.HighlightRow(it) }
                }
                resultsAdapter.submitList(items)
                b.searchEmptyText.isVisible = res.isEmpty && searchVm.query.value.isNotBlank()
                b.searchProgress.isVisible = false
            }
            is UiState.Error -> {
                resultsAdapter.submitList(emptyList())
                b.searchEmptyText.text = state.message
                b.searchEmptyText.isVisible = true
                b.searchProgress.isVisible = false
                Snackbar.make(b.root, state.message, Snackbar.LENGTH_SHORT)
                    .setAnchorView(b.root)
                    .show()
            }
        }
        applyVisibility()
    }

    /** Re-render the recent-search chip row. Each Chip carries a close
     *  icon that drops the entry from history on click. */
    private fun renderRecentChips(recents: List<String>) {
        val b = safeBinding ?: return
        b.searchRecentChipGroup.removeAllViews()
        if (recents.isEmpty()) return
        recents.forEach { query ->
            val chip = Chip(requireContext()).apply {
                text = query
                isCloseIconVisible = true
                isClickable = true
                setEnsureMinTouchTargetSize(false)
                contentDescription = query
                setOnClickListener {
                    binding.searchQueryInput.setText(query)
                    binding.searchQueryInput.setSelection(query.length)
                }
                setOnCloseIconClickListener {
                    searchVm.removeRecent(query)
                }
            }
            b.searchRecentChipGroup.addView(chip)
        }
    }

    private fun applyVisibility() {
        val b = safeBinding ?: return
        val q = searchVm.query.value
        val hasRecent = b.searchRecentChipGroup.childCount > 0
        // React only to the meaningful surfaces — when typing we keep the
        // recent strip hidden so the results row has the full width.
        b.searchRecentRow.isVisible = q.isBlank() && hasRecent
        b.searchResultsRecycler.isVisible = q.isNotBlank()
    }

    private fun hideKeyboardSoft() {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.searchQueryInput.windowToken, 0)
    }

    private fun showKeyboardSoft() {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.showSoftInput(binding.searchQueryInput, InputMethodManager.SHOW_IMPLICIT)
    }
}
