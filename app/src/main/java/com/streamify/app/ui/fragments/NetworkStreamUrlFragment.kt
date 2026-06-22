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
import com.streamify.app.databinding.FragmentNetworkStreamUrlBinding
import com.streamify.app.ui.adapters.RecentUrlAdapter
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.util.PlayerNavigation
import com.streamify.app.ui.viewmodels.NetworkStreamViewModel
import kotlinx.coroutines.launch

class NetworkStreamUrlFragment : Fragment() {

    private var _binding: FragmentNetworkStreamUrlBinding? = null
    private val binding get() = _binding!!
    private val safeBinding get() = _binding

    private val networkStreamVm: NetworkStreamViewModel by lazy {
        val app = requireActivity().application as StreamifyApp
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                NetworkStreamViewModel(PlayerPrefs(app.applicationContext)) as T
        })[NetworkStreamViewModel::class.java]
    }

    private lateinit var recentAdapter: RecentUrlAdapter
    private var pendingUndoRemove: RecentStreamUrl? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNetworkStreamUrlBinding.inflate(inflater, container, false)
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

        // Wire paste via the TextInputLayout's end icon
        binding.networkStreamUrlInputLayout.setEndIconOnClickListener { onPasteClicked() }
        // Wire play via the parent fragment's FAB (communicated through activity)
        // The FAB is in the parent NetworkStreamFragment

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
        binding.networkStreamRecentRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun prefillFromRecent(entry: RecentStreamUrl) {
        val b = safeBinding ?: return
        b.networkStreamUrlInput.setText(entry.url)
        b.networkStreamUrlInput.setSelection(entry.url.length)
        val chipId = when (entry.formatLabel) {
            RecentStreamUrl.FORMAT_HLS -> R.id.networkStreamFormatChipHls
            RecentStreamUrl.FORMAT_DASH -> R.id.networkStreamFormatChipAuto
            else -> R.id.networkStreamFormatChipAuto
        }
        b.networkStreamFormatChips.check(chipId)
    }

    private fun onRecentRemoveClicked(entry: RecentStreamUrl) {
        pendingUndoRemove = entry
        networkStreamVm.remove(entry.url)
        val b = safeBinding ?: return
        Snackbar.make(b.root, getString(R.string.network_stream_recent_removed_template, entry.url), Snackbar.LENGTH_LONG)
            .setAction(getString(R.string.network_stream_undo)) {
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

    fun getUrl(): String = safeBinding?.networkStreamUrlInput?.text?.toString()?.trim().orEmpty()

    fun selectedFormatLabel(): String = when (safeBinding?.networkStreamFormatChips?.checkedChipId) {
        R.id.networkStreamFormatChipHls -> RecentStreamUrl.FORMAT_HLS
        else -> RecentStreamUrl.FORMAT_AUTO
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
