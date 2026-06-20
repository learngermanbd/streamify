package com.streamify.app.ui.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.data.local.PlaylistEntity
import com.streamify.app.data.models.StreamLink
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.viewmodels.PlaylistsViewModel
import kotlinx.coroutines.launch

/**
 * Phase 5 · Step 5.2 — Add-to-playlist bottom-sheet.
 *
 * Hosted by [PlayerActivity]. Lets the user pick an existing playlist
 * for the currently-streaming channel/link (or create a new one). On
 * pick, calls back via [Listener.onPicked] with the playlist id;
 * PlayerActivity then invokes the VM's [PlaylistsViewModel.addStream].
 *
 * The caller is responsible for owning the live `StreamLink` (URL,
 * name, quality). This dialog has no Player knowledge — it just
 * relays the choice.
 */
class AddToPlaylistDialogFragment : BottomSheetDialogFragment() {

    interface Listener {
        fun onPicked(playlistId: String, playlistName: String)
    }

    private val playlistsVm: PlaylistsViewModel by viewModels {
        val app = requireActivity().application as StreamifyApp
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                PlaylistsViewModel(app.repository.playlistRepository) as T
        }
    }

    private var pendingStream: StreamLink? = null
    private var listener: Listener? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.layout_add_to_playlist, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val title = view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.addToPlaylistTitle)
        val subtitle = view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.addToPlaylistSubtitle)
        val createBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.addToPlaylistCreateBtn)
        val radioGroup = view.findViewById<RadioGroup>(R.id.addToPlaylistRadioGroup)
        val scroll = view.findViewById<View>(R.id.addToPlaylistScroll)
        val emptyText = view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.addToPlaylistEmptyText)
        val cancelBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.addToPlaylistCancelBtn)

        pendingStream?.let { stream ->
            subtitle.text = getString(R.string.add_to_playlist_subtitle_template, stream.name)
        }

        createBtn.setOnClickListener { showCreateThenReselect() }
        cancelBtn.setOnClickListener { dismissAllowingStateLoss() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistsVm.state.collect { state ->
                    if (state is UiState.Success) populateRadios(radioGroup, scroll, emptyText, state.value)
                }
            }
        }
        playlistsVm.refresh()
    }

    private fun populateRadios(
        radioGroup: RadioGroup,
        scroll: View,
        emptyText: com.google.android.material.textview.MaterialTextView,
        playlists: List<PlaylistEntity>
    ) {
        radioGroup.removeAllViews()
        if (playlists.isEmpty()) {
            scroll.isVisible = false
            emptyText.isVisible = true
            return
        }
        scroll.isVisible = true
        emptyText.isVisible = false
        playlists.forEachIndexed { idx, playlist ->
            val row = RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = "${playlist.name} (${playlist.items.size})"
                isChecked = (idx == 0)
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        requireContext(),
                        R.color.text
                    )
                )
                setOnClickListener { onPickedRadio(playlist) }
            }
            radioGroup.addView(row)
        }
    }

    private fun onPickedRadio(playlist: PlaylistEntity) {
        val stream = pendingStream ?: run { dismissAllowingStateLoss(); return }
        // Add through VM (Room write) then notify caller. Listeners
        // (PlayerActivity) call this before the Flow re-emits, so the
        // UI sees the same list (with +1 item) on next attach.
        playlistsVm.addStream(playlist.id, stream)
        listener?.onPicked(playlist.id, playlist.name)
        dismissAllowingStateLoss()
    }

    private fun showCreateThenReselect() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.playlists_create_hint)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlists_create_title)
            .setView(input)
            .setPositiveButton(R.string.playlists_create_confirm) { _, _ ->
                // Persist the new playlist through the VM. The Flow
                // collector above will re-emit once Room writes the row
                // and `populateRadios` will rebuild the radio list in
                // place — the user sees the new name without any
                // postDelayed re-fresh.
                playlistsVm.create(input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        /**
         * Show the dialog with a pending stream + listener. Caller must
         * hold a FragmentManager reference.
         */
        fun show(
            fm: androidx.fragment.app.FragmentManager,
            stream: StreamLink,
            listener: Listener
        ) {
            AddToPlaylistDialogFragment().apply {
                pendingStream = stream
                this.listener = listener
            }.show(fm, FRAGMENT_TAG)
        }

        private const val FRAGMENT_TAG = "AddToPlaylistDialogFragment"
    }
}
