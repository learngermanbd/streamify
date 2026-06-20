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
import androidx.viewpager2.widget.ViewPager2
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.databinding.FragmentHomeBinding
import com.streamify.app.ui.adapters.BannerAdapter
import com.streamify.app.ui.adapters.EventAdapter
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.viewmodels.HomeViewModel
import com.streamify.app.ui.viewmodels.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
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
    private lateinit var bannerAdapter: BannerAdapter

    /**
     * Plan Step 3.3: 5 s auto-scroll interval for the banner ViewPager2.
     * Driven by a [Job] in [viewLifecycleOwner.lifecycleScope] rather than a
     * Handler+Runnable pair so that lifecycle cancellation replaces the
     * manual `removeCallbacks` pattern (Code Reviewer major fix).
     */
    private val bannerAutoScrollIntervalMs = 5_000L
    private var autoScrollJob: Job? = null

    /**
     * ViewPager2 page-change callback — pauses auto-scroll while the user
     * is actively dragging the carousel. STEP 3.3 reviewer fix #2; without
     * this, every 5 s the carousel yanks the user mid-flick.
     */
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrollStateChanged(state: Int) {
            // SCROLL_STATE_DRAGGING == 1, IDLE == 0, SETTLING == 2.
            // We only want to count "user is touching the carousel" — not
            // programmatic settling (which our own auto-scroll triggers —
            // we want the timer to keep running across those).
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                autoScrollJob?.cancel()
                autoScrollJob = null
            } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                // Restart the timer so the user gets a full 5 s after
                // finishing their flick before auto-scroll resumes.
                restartAutoScroll()
            }
        }
    }

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

        // Phase 4 · Step 4.2 — Event row tap fires the PlayerActivity with
        // the Event's id routed through EXTRA_EVENT_ID. PlayerActivity
        // distinguishes this branch (Phase 4.x task: resolve Event ->
        // first Channel; for v1 the activity shows a friendly "Loading…"
        // placeholder rather than crashing).
        adapter = EventAdapter(onClick = { event ->
            com.streamify.app.ui.util.PlayerNavigation.startPlayerForEvent(
                context = requireContext(),
                eventId = event.id.orEmpty(),
                title = event.title
            )
        })
        binding.homeRv.layoutManager = LinearLayoutManager(requireContext())
        binding.homeRv.adapter = adapter

        // Step 3.3 — banner carousel. Click is a no-op for v1 (deep-link
        // wiring lands in Step 4.x). The adapter is bound to the live
        // Snapshot.banners via the mainVm.state collector below.
        bannerAdapter = BannerAdapter(onClick = { _banner -> /* Phase 4 deep-link */ })
        binding.bannerPager.adapter = bannerAdapter
        binding.bannerPager.offscreenPageLimit = 1
        // Register the drag-detect callback so auto-scroll pauses while
        // the user is actively flicking the carousel. unregistered on
        // destroyView below.
        binding.bannerPager.registerOnPageChangeCallback(pageChangeCallback)

        // Swipe-to-refresh re-triggers the activity-scoped boot fetch.
        // The downstream HomeViewModel re-derives automatically when
        // mainVm.state transitions back to Success.
        binding.swipeRefresh.setColorSchemeResources(R.color.primary, R.color.live_red)
        binding.swipeRefresh.setOnRefreshListener { mainVm.load() }

        // Collector #1 — wire mainVm.state -> homeVm derived state.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainVm.state.collectLatest { state ->
                    if (state is UiState.Success) {
                        // UiState.Success<T> where T = MainSnapshot.
                        // `state.value` is `Any` after the smart-cast (the
                        // sealed-class success variant is generic but Kotlin
                        // can't reify it), so we unchecked-cast back to
                        // MainSnapshot. Safe because MainViewModel only ever
                        // emits Success<MainSnapshot>.
                        @Suppress("UNCHECKED_CAST")
                        val snapshot = state.value as com.streamify.app.ui.viewmodels.MainSnapshot
                        homeVm.bindFromSnapshot(snapshot)
                        bindBannersFromSnapshot(snapshot)
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
        // Step 3.3 — lifecycle cancellation of the auto-scroll coroutine
        // happens automatically when viewLifecycleOwner.lifecycleScope
        // is cancelled. We additionally drop the Job reference so a
        // never-started Job can't leak.
        autoScrollJob = null

        // Unregister the page-change callback so the lifecycle-bound
        // bannerPager doesn't keep a reference to a torn-down Fragment.
        binding.bannerPager.unregisterOnPageChangeCallback(pageChangeCallback)

        // Memory hygiene: drop adapter references (so the RecyclerView's
        // internal ViewHolder pool + the ViewPager2's offscreen page pool
        // can be GC'd) and reset the binding.
        binding.homeRv.adapter = null
        binding.bannerPager.adapter = null
        _binding = null
        super.onDestroyView()
    }

    /**
     * Submit the snapshot's banner list to the [BannerAdapter] and start /
     * restart the 5 s auto-scroll timer. Empty list hides the pager.
     */
    private fun bindBannersFromSnapshot(snapshot: com.streamify.app.ui.viewmodels.MainSnapshot) {
        val banners = snapshot.banners
        binding.bannerPager.isVisible = banners.isNotEmpty()
        bannerAdapter.submitList(banners)
        restartAutoScroll()
    }

    /**
     * Cancel any in-flight auto-scroll Job then start a fresh 5 s loop
     * tied to the fragment's lifecycle. Lifecycle cancellation handles
     * tear-down so no manual removeCallbacks is needed.
     */
    private fun restartAutoScroll() {
        autoScrollJob?.cancel()
        val banners = bannerAdapter.itemCount
        if (banners <= 1) return
        autoScrollJob = viewLifecycleOwner.lifecycleScope.launch {
            // First delay gives the user a full 5 s of idle viewing before
            // the first auto-advance (so a freshly-bound carousel doesn't
            // jerk immediately).
            while (isActive) {
                delay(bannerAutoScrollIntervalMs)
                val pager = _binding?.bannerPager ?: return@launch
                val count = bannerAdapter.itemCount
                if (count <= 1) return@launch
                val next = (pager.currentItem + 1) % count
                pager.setCurrentItem(next, true)
            }
        }
    }
}
