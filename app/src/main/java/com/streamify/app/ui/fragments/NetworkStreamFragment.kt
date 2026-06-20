package com.streamify.app.ui.fragments

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.data.models.RecentStreamUrl
import com.streamify.app.data.prefs.PlayerPrefs
import com.streamify.app.databinding.FragmentNetworkStreamBinding
import com.streamify.app.ui.adapters.RecentUrlAdapter
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.util.PlayerNavigation
import com.streamify.app.ui.viewmodels.NetworkStreamViewModel
import kotlinx.coroutines.launch

/**
 * Phase 5 · Step 5.3 — Network Stream screen.
 *
 * Tap-flow per the strict-plan: user types (or pastes) a URL, picks a
 * format hint (AUTO / HLS / DASH), taps PLAY → [PlayerNavigation.startPlayerForVideo]
 * opens PlayerActivity with `EXTRA_VIDEO_URL` + the typed title, then the
 * VM records the URL into [PlayerPrefs.addRecentNetworkUrl]. Recent URLs
 * are observed live via [NetworkStreamViewModel.state] which mirrors the
 * DataStore-backed Flow; tapping a row re-populates the URL field + the
 * format selector, and long-pressing (or tapping the remove icon) deletes
 * it with a brief undo-friendly Snackbar.
 */
class NetworkStreamFragment : Fragment() {

    private var _binding: FragmentNetworkStreamBinding? = null
    private val binding get() = _binding!!

    /**
     * Null-safe view-binding accessor for helper methods invoked AFTER
     * [onDestroyView] has nulled [_binding]. Returning `null` instead of
     * throwing matches the Fragment lifecycle contract used by the
     * in-flight Snackbar undo path on a destroy-mid-action race.
     */
    private val safeBinding: FragmentNetworkStreamBinding?
        get() = _binding

    private val networkStreamVm: NetworkStreamViewModel by viewModels {
        val app = requireActivity().application as StreamifyApp
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                NetworkStreamViewModel(PlayerPrefs(app.applicationContext)) as T
        }
    }

    private lateinit var recentAdapter: RecentUrlAdapter

    /**
     * Tracks the URL to undo on a "Remove" snackbar. Captured BEFORE the
     * VM-row delete so we can re-add with the same `formatLabel` if the
     * user taps UNDO.
     */
    private var pendingUndoRemove: RecentStreamUrl? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNetworkStreamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recentAdapter = RecentUrlAdapter(
            onTap = { entry -> prefillFromRecent(entry) },
            onRemove = { entry -> onRecentRemoveClicked(entry) }
        )
        binding.networkStreamRecentRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.networkStreamRecentRecycler.adapter = recentAdapter

        binding.networkStreamPasteButton.setOnClickListener { onPasteClicked() }
        binding.networkStreamPlayButton.setOnClickListener { onPlayClicked() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkStreamVm.state.collect { state ->
                    if (state is UiState.Success) renderRecentUrls(state.value)
                }
            }
        }
        networkStreamVm.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.networkStreamRecentRecycler.adapter = null
        _binding = null
    }

    /** Push a recent entry back into the URL + format selector fields. */
    private fun prefillFromRecent(entry: RecentStreamUrl) {
        val b = safeBinding ?: return
        b.networkStreamUrlInput.setText(entry.url)
        b.networkStreamUrlInput.setSelection(entry.url.length)
        val chipId = when (entry.formatLabel) {
            RecentStreamUrl.FORMAT_HLS  -> R.id.networkStreamFormatChipHls
            RecentStreamUrl.FORMAT_DASH -> R.id.networkStreamFormatChipDash
            else                        -> R.id.networkStreamFormatChipAuto
        }
        b.networkStreamFormatChips.check(chipId)
    }

    private fun onRecentRemoveClicked(entry: RecentStreamUrl) {
        // Capture BEFORE delete so UNDO has the full payload.
        pendingUndoRemove = entry
        networkStreamVm.remove(entry.url)
        val b = safeBinding ?: return
        Snackbar.make(
            b.root,
            getString(R.string.network_stream_recent_removed_template, entry.url),
            Snackbar.LENGTH_LONG
        ).setAction(getString(R.string.network_stream_undo)) {
            val captured = pendingUndoRemove
            pendingUndoRemove = null
            if (captured != null) networkStreamVm.add(captured.url, captured.formatLabel)
        }.show()
    }

    private fun renderRecentUrls(list: List<RecentStreamUrl>) {
        val b = safeBinding ?: return
        if (list.isEmpty()) {
            b.networkStreamEmptyText.isVisible = true
            b.networkStreamRecentRecycler.isVisible = false
            recentAdapter.submitList(emptyList())
        } else {
            b.networkStreamEmptyText.isVisible = false
            b.networkStreamRecentRecycler.isVisible = true
            recentAdapter.submitList(list)
        }
    }

    private fun selectedFormatLabel(): String = when (safeBinding?.networkStreamFormatChips?.checkedChipId) {
        R.id.networkStreamFormatChipHls  -> RecentStreamUrl.FORMAT_HLS
        R.id.networkStreamFormatChipDash -> RecentStreamUrl.FORMAT_DASH
        else                              -> RecentStreamUrl.FORMAT_AUTO
    }

    private fun onPlayClicked() {
        val b = safeBinding ?: return
        val typed = b.networkStreamUrlInput.text?.toString()?.trim().orEmpty()
        if (typed.isBlank()) {
            b.networkStreamUrlInputLayout.error = getString(R.string.network_stream_url_required)
            return
        }
        if (!(typed.startsWith("http://") || typed.startsWith("https://"))) {
            b.networkStreamUrlInputLayout.error = getString(R.string.network_stream_url_invalid_scheme)
            return
        }
        b.networkStreamUrlInputLayout.error = null
        val formatLabel = selectedFormatLabel()
        // Persist + start playback. PlayerNavigation's startPlayerForVideo
        // uses NAMED param `videoUrl` (matches the EXTRA_VIDEO_URL extras
        // key on PlayerActivity).
        networkStreamVm.add(typed, formatLabel)
        PlayerNavigation.startPlayerForVideo(
            context = requireContext(),
            videoUrl = typed,
            title = getString(R.string.network_stream_default_title)
        )
    }

    private fun onPasteClicked() {
        val b = safeBinding ?: return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0 ||
            !clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) {
            Snackbar.make(b.root, R.string.network_stream_paste_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).coerceToText(requireContext())?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Snackbar.make(b.root, R.string.network_stream_paste_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        b.networkStreamUrlInput.setText(text)
        b.networkStreamUrlInput.setSelection(text.length)
        b.networkStreamUrlInputLayout.error = null
    }
}
