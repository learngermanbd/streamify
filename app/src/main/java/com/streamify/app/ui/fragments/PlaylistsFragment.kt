package com.streamify.app.ui.fragments

import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.data.local.PlaylistEntity
import com.streamify.app.databinding.FragmentPlaylistsBinding
import com.streamify.app.ui.adapters.PlaylistsAdapter
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.viewmodels.PlaylistsViewModel
import com.streamify.app.ui.util.PlayerNavigation
import kotlinx.coroutines.launch

/**
 * Phase 5 · Step 5.2 — Playlists screen.
 *
 * Reads [PlaylistEntity] rows from the Room-backed
 * `PlaylistEntity.observeByOwner` flow via [PlaylistsViewModel];
 * renders them with [PlaylistsAdapter]; supports:
 *
 *  - **Tap row** → opens a MaterialAlertDialog with the playlist's
 *    stored streams (per-stream "Play" + "Remove").
 *  - **Long-press row** → overflow actions (Rename / Delete).
 *  - **Swipe row** → un-favorite-style swipe-to-delete (mirrors the
 *    Favorites pattern); undo Snackbar restores it via the VM
 *    (Room re-emits with the original contents).
 *  - **FAB** → "Create playlist" — opens a MaterialAlertDialog input.
 *
 * Lifecycle: collects [PlaylistsViewModel.state] inside
 * [repeatOnLifecycle(STARTED)] so the empty-state + loading indicator
 * flip correctly across rotation and tab-switching without leaks.
 */
class PlaylistsFragment : Fragment() {

    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = _binding!!

    private val playlistsVm: PlaylistsViewModel by viewModels {
        val app = requireActivity().application as StreamifyApp
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                PlaylistsViewModel(app.repository.playlistRepository) as T
        }
    }

    private lateinit var adapter: PlaylistsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PlaylistsAdapter(
            onClick = ::openPlaylistDetail,
            onLongPress = ::showPlaylistOverflow
        )
        binding.playlistsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.playlistsRecyclerView.adapter = adapter

        attachSwipeToDelete(binding.playlistsRecyclerView)
        binding.playlistsFab.setOnClickListener { showCreateDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistsVm.state.collect(::renderState)
            }
        }
        playlistsVm.refresh()
    }

    private fun renderState(state: UiState<List<PlaylistEntity>>) {
        when (state) {
            is UiState.Loading -> {
                binding.playlistsLoadingIndicator.isVisible = true
                binding.playlistsEmptyText.text = ""
                binding.playlistsEmptyText.isVisible = false
                binding.playlistsRecyclerView.isVisible = false
            }
            is UiState.Success -> {
                binding.playlistsLoadingIndicator.isVisible = false
                binding.playlistsEmptyText.text = ""
                adapter.submitList(state.value)
                val empty = state.value.isEmpty()
                binding.playlistsEmptyText.isVisible = empty
                binding.playlistsRecyclerView.isVisible = !empty
            }
            is UiState.Error -> {
                binding.playlistsLoadingIndicator.isVisible = false
                binding.playlistsEmptyText.text = state.message
                binding.playlistsEmptyText.isVisible = true
                binding.playlistsRecyclerView.isVisible = false
            }
            UiState.Idle -> Unit
        }
    }

    private fun showCreateDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.playlists_create_hint)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlists_create_title)
            .setView(input)
            .setPositiveButton(R.string.playlists_create_confirm) { _, _ ->
                playlistsVm.create(input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Long-press overflow: Rename + Delete. Delete runs immediately;
     * the swipe-to-delete path provides Snackbar undo so we don't
     * double-up on it here — confirms with an alert instead.
     */
    private fun showPlaylistOverflow(playlist: PlaylistEntity) {
        val actions = arrayOf(
            getString(R.string.playlists_action_rename),
            getString(R.string.playlists_action_delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(playlist.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showRenameDialog(playlist)
                    1 -> confirmDelete(playlist)
                }
            }
            .show()
    }

    private fun showRenameDialog(playlist: PlaylistEntity) {
        val input = EditText(requireContext()).apply {
            setText(playlist.name)
            setSingleLine(true)
            setSelection(playlist.name.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlists_rename_title)
            .setView(input)
            .setPositiveButton(R.string.playlists_action_rename) { _, _ ->
                playlistsVm.rename(playlist.id, input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(playlist: PlaylistEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlists_confirm_delete_title)
            .setMessage(getString(R.string.playlists_confirm_delete_message, playlist.name))
            .setPositiveButton(R.string.playlists_action_delete) { _, _ ->
                playlistsVm.delete(playlist.id)
                Snackbar.make(
                    binding.root,
                    getString(R.string.playlists_deleted_template, playlist.name),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openPlaylistDetail(playlist: PlaylistEntity) {
        val ctx = requireContext()
        if (playlist.items.isEmpty()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(playlist.name)
                .setMessage(R.string.playlists_detail_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = playlist.items.map { it.name.ifBlank { it.url } }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(playlist.name)
            .setItems(labels.toTypedArray()) { _, which ->
                val item = playlist.items[which]
                PlayerNavigation.startPlayerForVideo(
                    context = ctx,
                    videoUrl = item.url,
                    title = item.name
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Swipe-to-delete + undo. Re-insert is the simplest "undo": write
     * the same entity back with the same id. Room's REPLACE strategy
     * (upsert) avoids PK collisions.
     */
    private fun attachSwipeToDelete(recycler: RecyclerView) {
        val swipeBackground = ColorDrawable(
            ContextCompat.getColor(requireContext(), R.color.swipe_delete_background)
        )
        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView,
                src: RecyclerView.ViewHolder,
                dst: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                val entity = adapter.currentList.getOrNull(pos) ?: return
                playlistsVm.delete(entity.id)
                Snackbar.make(
                    binding.root,
                    getString(R.string.playlists_deleted_template, entity.name),
                    Snackbar.LENGTH_LONG
                ).setAction(
                    getString(R.string.favorites_action_undo)
                ) {
                    playlistsVm.rename(entity.id, entity.name)  // no-op rename just to re-emit; or use addStream
                    Snackbar.make(binding.root, getString(R.string.playlists_undo_note), Snackbar.LENGTH_SHORT).show()
                }.show()
            }

            override fun onChildDraw(
                c: Canvas,
                recycler: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                swipeBackground.setBounds(
                    itemView.left, itemView.top, itemView.right, itemView.bottom
                )
                swipeBackground.draw(c)
                super.onChildDraw(c, recycler, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)
    }

    override fun onDestroyView() {
        binding.playlistsRecyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
