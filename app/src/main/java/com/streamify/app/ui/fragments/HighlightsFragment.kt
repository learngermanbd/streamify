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
import com.streamify.app.databinding.FragmentHighlightsBinding
import com.streamify.app.StreamifyApp
import com.streamify.app.ui.adapters.HighlightAdapter
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.viewmodels.MainSnapshot
import com.streamify.app.ui.viewmodels.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 3 · Step 3.5 — Highlights tab Fragment.
 *
 * Renders a vertical list of [com.streamify.app.data.models.Highlight]
 * 16:9 thumbnail cards via
 * [com.streamify.app.ui.adapters.HighlightAdapter]. The Highlights
 * screen reads directly from the activity-scoped [MainViewModel] —
 * `state.highlights` is the single source of truth that the Splash →
 * Main boot-fetch already populated, so the fragment does NOT spin up
 * its own [HighlightsViewModel] (Step 2.5 introduced one as test
 * surface area; the screen consumes `mainVm.state` per the user's
 * explicit wiring instruction).
 *
 * State flow:
 *   1. Boot: Splash -> MainActivity.onCreate triggers `mainVm.load()`
 *      (Idle -> Loading -> Success<MainSnapshot>).
 *   2. Drawer / future route lands here ([HighlightsFragment]). We
 *      observe `mainVm.state` under `repeatOnLifecycle(STARTED)` and:
 *        - Loading -> show loadingIndicator
 *        - Success -> submitList(state.value.highlights), toggle empty
 *        - Error   -> empty-state message = state.message
 *        - Idle    -> empty-state visible iff list is empty
 *
 *   No SwipeRefresh for now — Step 5.5 Drawer surfaces the
 * Highlights destination; a Fragment-level refresh fetcher would
 * race the activity-scoped boot fetch unless gated specially.
 */
class HighlightsFragment : Fragment() {

    private var _binding: FragmentHighlightsBinding? = null
    private val binding get() = _binding!!

    /**
     * Activity-scoped MainViewModel. Same instance MainActivity already
     * observes; the Highlights tab is just another consumer of that
     * snapshot (it submits `snapshot.highlights` to its own adapter).
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

    private lateinit var adapter: HighlightAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHighlightsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Phase 4 · Step 4.2 — Highlight thumbnail tap fires PlayerActivity
        // with the resolved `Highlight.videoUrl` and title. The Activity
        // short-circuits the channel lookup and starts ExoPlayer directly.
        adapter = HighlightAdapter(onClick = { highlight ->
            com.streamify.app.ui.util.PlayerNavigation.startPlayerForVideo(
                context = requireContext(),
                videoUrl = highlight.videoUrl,
                title = highlight.title
            )
        })
        binding.highlightsRv.layoutManager = LinearLayoutManager(requireContext())
        binding.highlightsRv.adapter = adapter

        // Observer — mainVm.state -> submitList(snapshot.highlights)
        // per the Step 3.5 wiring spec. Other branches handle Loading /
        // Error / Idle so the fragment correctly responds when the user
        // reaches it BEFORE mainVm's boot fetch completes.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainVm.state.collectLatest { state ->
                    when (state) {
                        is UiState.Success -> {
                            @Suppress("UNCHECKED_CAST")
                            val snapshot = state.value as MainSnapshot
                            binding.loadingIndicator.isVisible = false
                            adapter.submitList(snapshot.highlights)
                            // Phase 6 · Step 6.3 — staggered fall-down
                            // cascade on every fresh highlight snapshot.
                            if (snapshot.highlights.isNotEmpty()) binding.highlightsRv.scheduleLayoutAnimation()
                            binding.emptyState.isVisible = snapshot.highlights.isEmpty()
                        }
                        is UiState.Error -> {
                            binding.loadingIndicator.isVisible = false
                            binding.emptyState.isVisible = true
                            binding.emptyState.text = state.message
                        }
                        UiState.Loading -> {
                            binding.loadingIndicator.isVisible = true
                            binding.emptyState.isVisible = false
                        }
                        UiState.Idle -> {
                            binding.loadingIndicator.isVisible = false
                            // No submitList on Idle — keeps the prior
                            // successful rendering if the VM was reset
                            // (e.g. retry() re-gate). Empty pending data
                            // shows the empty-state overlay.
                            binding.emptyState.isVisible = adapter.itemCount == 0
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        // Memory hygiene: drop the adapter reference (so the RecyclerView's
        // internal ViewHolder pool can be GC'd) and reset the binding.
        binding.highlightsRv.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
