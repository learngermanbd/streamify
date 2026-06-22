package com.streamify.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.databinding.FragmentHomeBinding
import com.streamify.app.ui.adapters.EventAdapter
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.viewmodels.HomeViewModel
import com.streamify.app.ui.viewmodels.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 3 · Step 3.3 — Home tab Fragment.
 *
 * Real content: a SwipeRefresh wrapping a RecyclerView of [Event] rows
 * (each row renders via [com.streamify.app.ui.adapters.EventAdapter]).
 *
 * Data flow (two collectors, both `repeatOnLifecycle(STARTED)`):
 *
 *   1. `mainVm.state` (activity-scoped, owned by MainActivity) is
 *      observed. On `UiState.Success<MainSnapshot>` we call
 *      `homeVm.bindFromSnapshot(snapshot)` which re-emits the home list
 *      on `homeVm.state`.
 *
 *   2. `homeVm.state` (fragment-scoped) is observed. Each emission
 *      triggers one of:
 *        - `submitList(events)` to the [EventAdapter],
 *        - show/hide empty / loading overlays,
 *        - clear `swipeRefresh.isRefreshing`.
 *
 * Swipe-to-refresh calls `mainVm.load()` (\u2014 the activity-scoped boot
 * fetch) so the entire repository graph re-fires through the live + events
 * fetches and the home list re-derives naturally.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * Fragment-scoped HomeViewModel. We use raw `ViewModelProvider` (not
     * the `by viewModels { \u2026 }` Kotlin DSL) to avoid adding
     * `androidx.fragment:fragment-ktx` for this single call site, mirroring
     * the pattern already established by [SplashActivity] and
     * [com.streamify.app.ui.activities.MainActivity].
     */
    private val homeVm: HomeViewModel by lazy {
        val app = requireActivity().application as StreamifyApp
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(app.repository.mainRepository) as T
        }
        ViewModelProvider(this, factory)[HomeViewModel::class.java]
    }

    /**
     * Activity-scoped MainViewModel. Same instance MainActivity already
     * observes; the Home tab is just another consumer of that snapshot.
     */
    private val mainVm: MainViewModel by lazy {
        val activity = requireActivity()
        val app = activity.application as StreamifyApp
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(app.repository.mainRepository) as T
        }
        @Suppress("UNCHECKED_CAST")
        ViewModelProvider(activity, factory)[MainViewModel::class.java]
    }

    private lateinit var adapter: EventAdapter

    /** Current active category filter (null = All). */
    private var selectedCategory: String? = null

    /** Current active status filter. */
    private var selectedStatus: StatusFilter = StatusFilter.RECENT

    private enum class StatusFilter { RECENT, LIVE, UPCOMING, ALL }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = EventAdapter(onClick = { event ->
            com.streamify.app.ui.util.PlayerNavigation.startPlayerForEvent(
                context = requireContext(),
                eventId = event.id.orEmpty(),
                title = event.title
            )
        })
        binding.homeRv.layoutManager = LinearLayoutManager(requireContext())
        binding.homeRv.adapter = adapter

        // Category chip selection
        binding.categoryChips.setOnCheckedStateChangeListener { group, _ ->
            val checkedId = group.checkedChipId
            selectedCategory = if (checkedId == View.NO_ID || checkedId == R.id.chipAll) {
                null
            } else {
                val chip = group.findViewById<Chip>(checkedId)
                chip?.text?.toString()?.replace(Regex("[⚡🏏⚽🥊🏎️ ]"), "")?.trim()
            }
            // Re-filter the current list
            homeVm.filterByCategory(selectedCategory)
        }

        // Status filter pills
        binding.filterRecent.setOnClickListener { selectStatus(StatusFilter.RECENT) }
        binding.filterLive.setOnClickListener { selectStatus(StatusFilter.LIVE) }
        binding.filterUpcoming.setOnClickListener { selectStatus(StatusFilter.UPCOMING) }
        binding.filterAll.setOnClickListener { selectStatus(StatusFilter.ALL) }

        // Swipe-to-refresh re-triggers the activity-scoped boot fetch.
        binding.swipeRefresh.setColorSchemeResources(R.color.primary, R.color.live_red)
        binding.swipeRefresh.setOnRefreshListener { mainVm.load() }

        // Collector #1 — wire mainVm.state -> homeVm derived state.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainVm.state.collectLatest { state ->
                    if (state is UiState.Success) {
                        @Suppress("UNCHECKED_CAST")
                        val snapshot = state.value as com.streamify.app.ui.viewmodels.MainSnapshot
                        homeVm.bindFromSnapshot(snapshot)
                    }
                }
            }
        }

        // Collector #2 — wire homeVm.state -> Adapter + overlays.
        // This is the canonical "mainVm.state.Success -> homeRv.adapter
        // swap" wiring the user requested.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeVm.state.collectLatest { state ->
                    when (state) {
                        is UiState.Success -> {
                            binding.loadingIndicator.isVisible = false
                            binding.swipeRefresh.isRefreshing = false
                            adapter.submitList(state.value)
                            // Phase 6 · Step 6.3 — re-trigger the
                            // staggered fall-down cascade on every fresh
                            // dataset (PairNull skip on empty so the
                            // empty-state overlay doesn't shimmer).
                            if (state.value.isNotEmpty()) binding.homeRv.scheduleLayoutAnimation()
                            binding.emptyState.isVisible = state.value.isEmpty()
                            // Banners are bound from Collector #1 (mainVm.state)
                            // — homeVm.state.value is List<Event>, not MainSnapshot,
                            // so banners stay driven by the activity-scoped snapshot.
                        }
                        is UiState.Error -> {
                            binding.loadingIndicator.isVisible = false
                            binding.swipeRefresh.isRefreshing = false
                            binding.emptyState.isVisible = true
                            binding.emptyState.text = state.message
                        }
                        UiState.Loading -> {
                            binding.loadingIndicator.isVisible = true
                            binding.emptyState.isVisible = false
                        }
                        UiState.Idle -> {
                            binding.loadingIndicator.isVisible = false
                            binding.swipeRefresh.isRefreshing = false
                            binding.emptyState.isVisible = adapter.itemCount == 0
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.homeRv.adapter = null
        _binding = null
        super.onDestroyView()
    }

    /**
     * Update the visual state of the status filter pills.
     */
    private fun selectStatus(filter: StatusFilter) {
        selectedStatus = filter
        val b = _binding ?: return

        // Reset all pills to inactive
        b.filterRecent.setBackgroundResource(R.drawable.bg_status_pill)
        b.filterRecent.setTextColor(resources.getColor(R.color.text_muted, null))
        b.filterLive.setBackgroundResource(R.drawable.bg_status_pill)
        b.filterLive.setTextColor(resources.getColor(R.color.text_muted, null))
        b.filterUpcoming.setBackgroundResource(R.drawable.bg_status_pill)
        b.filterUpcoming.setTextColor(resources.getColor(R.color.text_muted, null))
        b.filterAll.setBackgroundResource(R.drawable.bg_status_pill)
        b.filterAll.setTextColor(resources.getColor(R.color.text_muted, null))

        // Activate the selected one
        when (filter) {
            StatusFilter.RECENT -> {
                b.filterRecent.setBackgroundResource(R.drawable.bg_status_pill_active)
                b.filterRecent.setTextColor(resources.getColor(R.color.primary, null))
            }
            StatusFilter.LIVE -> {
                b.filterLive.setBackgroundResource(R.drawable.bg_status_pill_live)
                b.filterLive.setTextColor(resources.getColor(R.color.live_red, null))
            }
            StatusFilter.UPCOMING -> {
                b.filterUpcoming.setBackgroundResource(R.drawable.bg_status_pill_active)
                b.filterUpcoming.setTextColor(resources.getColor(R.color.primary, null))
            }
            StatusFilter.ALL -> {
                b.filterAll.setBackgroundResource(R.drawable.bg_status_pill_active)
                b.filterAll.setTextColor(resources.getColor(R.color.primary, null))
            }
        }
        homeVm.filterByStatus(filter.name)
    }
}
