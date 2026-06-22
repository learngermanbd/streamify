package com.streamify.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.data.models.Event
import com.streamify.app.databinding.FragmentHomeBinding
import com.streamify.app.ui.adapters.EventAdapter
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.viewmodels.HomeViewModel
import com.streamify.app.ui.viewmodels.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Home tab Fragment — Sportzfy design.
 *
 * Layout includes:
 *  - Dismissible promo/ad banner
 *  - Marquee scrolling notice
 *  - Circular category badges (All, Cricket, Football, Boxing, Motor)
 *  - Status filter pills with dynamic counts (Recent, Live, Upcoming, All)
 *  - RecyclerView match list
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeVm: HomeViewModel by lazy {
        val app = requireActivity().application as StreamifyApp
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(app.repository.mainRepository) as T
        }
        ViewModelProvider(this, factory)[HomeViewModel::class.java]
    }

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

    /** All events for counting. */
    private var allEvents: List<Event> = emptyList()

    /** Badge emoji views and labels for direct access during selection. */
    private data class BadgeRef(val emojiView: TextView, val labelView: TextView, val counterView: TextView)
    private lateinit var badgeRefs: Map<String, BadgeRef>

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

        // Setup dismissible ad banner
        binding.adBannerClose.setOnClickListener {
            binding.adBannerCard.isVisible = false
        }

        // Setup circular category badges — store direct view references
        // for clean selection logic (no fragile parent/child traversal).
        badgeRefs = mapOf(
            "All" to BadgeRef(
                (binding.badgeAll.getChildAt(0) as ViewGroup).getChildAt(0) as TextView,
                binding.badgeAll.getChildAt(1) as TextView,
                binding.badgeAllCount
            ),
            "Cricket" to BadgeRef(
                (binding.badgeCricket.getChildAt(0) as ViewGroup).getChildAt(0) as TextView,
                binding.badgeCricket.getChildAt(1) as TextView,
                binding.badgeCricketCount
            ),
            "Football" to BadgeRef(
                (binding.badgeFootball.getChildAt(0) as ViewGroup).getChildAt(0) as TextView,
                binding.badgeFootball.getChildAt(1) as TextView,
                binding.badgeFootballCount
            ),
            "Boxing" to BadgeRef(
                (binding.badgeBoxing.getChildAt(0) as ViewGroup).getChildAt(0) as TextView,
                binding.badgeBoxing.getChildAt(1) as TextView,
                binding.badgeBoxingCount
            ),
            "Motor" to BadgeRef(
                (binding.badgeMotor.getChildAt(0) as ViewGroup).getChildAt(0) as TextView,
                binding.badgeMotor.getChildAt(1) as TextView,
                binding.badgeMotorCount
            )
        )

        // Category badge click handlers
        binding.badgeAll.setOnClickListener { selectCategory(null, binding.badgeAll) }
        binding.badgeCricket.setOnClickListener { selectCategory("Cricket", binding.badgeCricket) }
        binding.badgeFootball.setOnClickListener { selectCategory("Football", binding.badgeFootball) }
        binding.badgeBoxing.setOnClickListener { selectCategory("Boxing", binding.badgeBoxing) }
        binding.badgeMotor.setOnClickListener { selectCategory("Motor", binding.badgeMotor) }

        // Status filter pills
        binding.filterRecent.setOnClickListener { selectStatus(StatusFilter.RECENT) }
        binding.filterLive.setOnClickListener { selectStatus(StatusFilter.LIVE) }
        binding.filterUpcoming.setOnClickListener { selectStatus(StatusFilter.UPCOMING) }
        binding.filterAll.setOnClickListener { selectStatus(StatusFilter.ALL) }

        // Swipe-to-refresh
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

        // Collector #2 — wire homeVm.state -> Adapter + overlays + counts.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeVm.state.collectLatest { state ->
                    when (state) {
                        is UiState.Success -> {
                            binding.loadingIndicator.isVisible = false
                            binding.swipeRefresh.isRefreshing = false
                            allEvents = state.value
                            adapter.submitList(state.value)
                            if (state.value.isNotEmpty()) binding.homeRv.scheduleLayoutAnimation()
                            binding.emptyState.isVisible = state.value.isEmpty()
                            updateCounts()
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
     * Select a category badge and update visual state.
     * Clears all badges to unselected state, then highlights the selected one.
     */
    /**
     * Select a category badge. Resets all badges to unselected state,
     * then highlights the selected one using stored [BadgeRef] references.
     */
    private fun selectCategory(category: String?, selectedBadge: View) {
        selectedCategory = category

        // Determine which key was clicked
        val selectedKey = when (selectedBadge.id) {
            R.id.badgeAll -> "All"
            R.id.badgeCricket -> "Cricket"
            R.id.badgeFootball -> "Football"
            R.id.badgeBoxing -> "Boxing"
            R.id.badgeMotor -> "Motor"
            else -> "All"
        }

        // Reset all badges
        for ((key, ref) in badgeRefs) {
            ref.emojiView.setBackgroundResource(R.drawable.bg_category_badge)
            ref.labelView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
        }

        // Highlight selected badge
        badgeRefs[selectedKey]?.let { ref ->
            ref.emojiView.setBackgroundResource(R.drawable.bg_category_badge_selected)
            ref.labelView.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
        }

        homeVm.filterByCategory(category)
    }

    /**
     * Update the visual state of the status filter pills.
     */
    private fun selectStatus(filter: StatusFilter) {
        selectedStatus = filter
        val b = _binding ?: return

        // Reset all pills to inactive
        b.filterRecent.setBackgroundResource(R.drawable.bg_status_pill)
        b.filterRecent.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
        b.filterLive.setBackgroundResource(R.drawable.bg_status_pill)
        b.filterLive.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
        b.filterUpcoming.setBackgroundResource(R.drawable.bg_status_pill)
        b.filterUpcoming.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
        b.filterAll.setBackgroundResource(R.drawable.bg_status_pill)
        b.filterAll.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))

        // Activate the selected one
        when (filter) {
            StatusFilter.RECENT -> {
                b.filterRecent.setBackgroundResource(R.drawable.bg_status_pill_active)
                b.filterRecent.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            }
            StatusFilter.LIVE -> {
                b.filterLive.setBackgroundResource(R.drawable.bg_status_pill_live)
                b.filterLive.setTextColor(ContextCompat.getColor(requireContext(), R.color.live_red))
            }
            StatusFilter.UPCOMING -> {
                b.filterUpcoming.setBackgroundResource(R.drawable.bg_status_pill_active)
                b.filterUpcoming.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            }
            StatusFilter.ALL -> {
                b.filterAll.setBackgroundResource(R.drawable.bg_status_pill_active)
                b.filterAll.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            }
        }
        homeVm.filterByStatus(filter.name)
    }

    /**
     * Update dynamic event counts on category badges and status filter pills.
     */
    private fun updateCounts() {
        val b = _binding ?: return
        val events = allEvents

        // Category counts
        val allCount = events.size
        val cricketCount = events.count { it.category.contains("Cricket", ignoreCase = true) }
        val footballCount = events.count { it.category.contains("Football", ignoreCase = true) }
        val boxingCount = events.count { it.category.contains("Boxing", ignoreCase = true) }
        val motorCount = events.count {
            it.category.contains("Motor", ignoreCase = true) ||
            it.category.contains("Racing", ignoreCase = true) ||
            it.category.contains("F1", ignoreCase = true) ||
            it.category.contains("MotoGP", ignoreCase = true)
        }

        // Update badge counters
        setBadgeCount(b.badgeAllCount, allCount)
        setBadgeCount(b.badgeCricketCount, cricketCount)
        setBadgeCount(b.badgeFootballCount, footballCount)
        setBadgeCount(b.badgeBoxingCount, boxingCount)
        setBadgeCount(b.badgeMotorCount, motorCount)

        // Status counts
        val recentCount = events.count { !it.isLive }
        val liveCount = events.count { it.isLive }
        val upcomingCount = events.count { !it.isLive } // upcoming = not live

        b.filterRecent.text = "✓ Recent ($recentCount)"
        b.filterLive.text = "● Live ($liveCount)"
        b.filterUpcoming.text = "📅 Upcoming ($upcomingCount)"
        b.filterAll.text = "All ($allCount)"
    }

    private fun setBadgeCount(textView: TextView, count: Int) {
        if (count > 0) {
            textView.text = count.toString()
            textView.isVisible = true
        } else {
            textView.isVisible = false
        }
    }
}
