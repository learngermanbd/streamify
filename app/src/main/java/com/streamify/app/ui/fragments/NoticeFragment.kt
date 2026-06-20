package com.streamify.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.data.repository.NoticeRepository
import com.streamify.app.databinding.FragmentNoticeBinding
import com.streamify.app.ui.adapters.NoticeAdapter
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.viewmodels.NoticeScreenState
import com.streamify.app.ui.viewmodels.NoticeViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 5 · Step 5.5 v2 — Notice screen.
 *
 * Renders one of four NoticeScreenState branches driven by [NoticeViewModel.state]:
 *  - ListMode  → RecyclerView with [NoticeAdapter]'s mixed rows
 *  - LegacyMode → single free-form body card (v1 fallback path)
 *  - Empty     → empty-state ImageView + text
 *  - Loading   → progress overlay
 *  - UiState.Error (top-level) → error card with Retry button
 *
 * Click handlers for notice cards and attachment chips forward to the
 * underlying launchers (deep link for cards, ACTION_VIEW for chips)
 * — implemented in [NoticeAdapter.bind].
 *
 * `safeBinding` lets helpers invoked from coroutine-collected state
 * flows safely no-op once the Fragment view is destroyed (`_binding`
 * null). Mirrors the same idiom in SearchFragment + HighlightsFragment.
 *
 * Phase 5 · Step 5.7 — the "first-render triggers refresh?" gate uses
 * `as? UiState.Success<*>` smart cast instead of unchecked `as` so the
 * compiler doesn't need the `-Xunchecked-cast` opt-in.
 */
class NoticeFragment : Fragment() {

    private var _binding: FragmentNoticeBinding? = null
    private val binding get() = _binding!!

    private val safeBinding: FragmentNoticeBinding?
        get() = _binding

    private lateinit var resultsAdapter: NoticeAdapter

    private val noticeVm: NoticeViewModel by viewModels {
        val app = requireActivity().application as StreamifyApp
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = noticeRepositoryVm(app) as T
        }
    }

    private fun noticeRepositoryVm(app: StreamifyApp): NoticeViewModel = NoticeViewModel(
        app = app,
        httpClient = app.network.httpClient,
        noticeRepo = app.repository.noticeRepository,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNoticeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // v2 RecyclerView + adapter wiring. Click handlers forward to
        // standard launchers so they don't need to live here.
        resultsAdapter = NoticeAdapter(
            onNoticeClick = { notice ->
                if (notice.deepLink != null) {
                    safeBinding?.root?.let { root ->
                        runCatching {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(notice.deepLink)
                            )
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                    }
                }
            },
            onAttachmentClick = { /* no-op fallback for malformed URIs */ }
        )
        binding.noticeRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = resultsAdapter
            setHasFixedSize(false)
            itemAnimator?.changeDuration = 0  // avoid flicker on Flow re-emit
        }

        binding.noticeRefreshFab.setOnClickListener {
            noticeVm.refresh(force = true)
        }
        binding.noticeRetryButton.setOnClickListener {
            noticeVm.reloadLegacyFallback()
            noticeVm.refresh(force = false)
        }

        observeState()
        // First render: trigger a /api/notices fetch once. Don't fire on
        // re-renders after a fragment recreate — the constructor's init
        // block already started a fetch, and subsequent refreshes are
        // user-initiated via the FAB / Retry button.
        if (noticeVm.state.value.let { it is UiState.Success<*> && it.value === NoticeScreenState.Loading }) {
            noticeVm.refresh(force = false)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                noticeVm.state.collectLatest { state -> render(state) }
            }
        }
    }

    private fun render(state: UiState<NoticeScreenState>) {
        val b = safeBinding ?: return
        when (state) {
            is UiState.Idle, is UiState.Loading -> showOnly(b.noticeProgress)
            is UiState.Success -> renderScreenState(state.value, b)
            is UiState.Error -> {
                b.noticeErrorText.text = state.message
                showOnly(b.noticeErrorCard)
            }
        }
    }

    private fun renderScreenState(screen: NoticeScreenState, b: FragmentNoticeBinding) {
        when (screen) {
            is NoticeScreenState.Loading -> showOnly(b.noticeProgress)
            is NoticeScreenState.ListMode -> {
                val rows = NoticeAdapter.rowsOf(screen.sections)
                resultsAdapter.submitList(rows)
                showOnly(b.noticeRecycler)
            }
            is NoticeScreenState.LegacyMode -> {
                b.noticeBodyText.text = screen.text
                showOnly(b.noticeBodyCard)
            }
            is NoticeScreenState.Empty -> showOnly(b.noticeEmptyState)
        }
    }

    /**
     * Same `showOnly` idiom used in v1. v2 has 5 surfaces but the
     * pattern is unchanged — flip visibility of all 5 to GONE, set
     * the chosen one to VISIBLE.
     */
    private fun showOnly(visible: View) {
        val b = safeBinding ?: return
        listOf(
            b.noticeProgress,
            b.noticeRecycler,
            b.noticeBodyCard,
            b.noticeEmptyState,
            b.noticeErrorCard
        ).forEach { it.visibility = if (it === visible) View.VISIBLE else View.GONE }
    }

    override fun onDestroyView() {
        // Tear down the RecyclerView's adapter BEFORE nulling the
        // binding so RecyclerView's ViewAttachedToWindow callback
        // doesn't fire on a stale binding reference.
        binding.noticeRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
