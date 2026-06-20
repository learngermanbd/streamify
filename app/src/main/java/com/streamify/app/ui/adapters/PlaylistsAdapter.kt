package com.streamify.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamify.app.data.local.PlaylistEntity
import com.streamify.app.databinding.ViewItemPlaylistBinding

/**
 * Phase 5 · Step 5.2 — RecyclerView adapter for the Playlists list.
 *
 * Each row is a [PlaylistEntity] card: title + item count + overflow
 * actions (rename + delete) via long-press context menu (Favorites
 * didn't get this in Step 5.1 — play it forward in polish).
 *
 * Stable IDs + id-keyed DiffUtil so the Snackbar undo path in the
 * parent RecyclerView's ItemTouchHelper keeps the right row bound to
 * the right playlist entity across reorders.
 */
class PlaylistsAdapter(
    private val onClick: (PlaylistEntity) -> Unit,
    private val onLongPress: (PlaylistEntity) -> Unit
) : ListAdapter<PlaylistEntity, PlaylistsAdapter.PlaylistViewHolder>(PlaylistDiff) {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long =
        getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ViewItemPlaylistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PlaylistViewHolder(binding, onClick, onLongPress)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PlaylistViewHolder) {
        holder.unbind()
    }

    class PlaylistViewHolder(
        private val binding: ViewItemPlaylistBinding,
        private val onClick: (PlaylistEntity) -> Unit,
        private val onLongPress: (PlaylistEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var current: PlaylistEntity? = null

        fun bind(playlist: PlaylistEntity) {
            current = playlist
            val ctx = binding.root.context
            binding.playlistName.text = playlist.name.ifBlank {
                ctx.getString(com.streamify.app.R.string.playlists_default_name)
            }
            binding.playlistItemCount.text = ctx.resources.getQuantityString(
                com.streamify.app.R.plurals.playlists_item_count,
                playlist.items.size,
                playlist.items.size
            )
            binding.playlistRowRoot.setOnClickListener { current?.let(onClick) }
            binding.playlistRowRoot.setOnLongClickListener {
                current?.let(onLongPress)
                true
            }
        }

        fun unbind() {
            current = null
            binding.playlistRowRoot.setOnClickListener(null)
            binding.playlistRowRoot.setOnLongClickListener(null)
        }
    }

    private object PlaylistDiff : DiffUtil.ItemCallback<PlaylistEntity>() {
        override fun areItemsTheSame(a: PlaylistEntity, b: PlaylistEntity): Boolean =
            a.id == b.id
        override fun areContentsTheSame(a: PlaylistEntity, b: PlaylistEntity): Boolean = a == b
    }
}
