package com.streamify.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamify.app.R
import com.streamify.app.data.local.FavoriteEntity
import com.streamify.app.databinding.ViewItemFavoriteBinding

/**
 * Phase 5 · Step 5.1 — RecyclerView adapter for the Favorites screen.
 *
 * Uses [ListAdapter] + [DiffUtil] keyed on channelId so a re-order
 * triggered by the Room flow doesn't churn the ViewHolder pool.
 *
 * Two click callbacks:
 *  - [onClick]    — the row itself, opens PlayerActivity.
 *  - [onFavorite] — the heart IconButton (right-side), toggles the
 *    favorite state. Pairs with the swipe-to-delete undo Snackbar so
 *    the user can remove an entry from either gesture.
 *
 * Stable IDs are enabled so [ItemTouchHelper]-driven swipe animations
 * keep the right ViewHolder bound to the right entity after the live
 * Room flow re-emits.
 */
class FavoriteAdapter(
    private val onClick: (FavoriteEntity) -> Unit,
    private val onFavorite: (FavoriteEntity) -> Unit
) : ListAdapter<FavoriteEntity, FavoriteAdapter.FavoriteViewHolder>(FavoriteDiff) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        getItem(position).channelId.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val binding = ViewItemFavoriteBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FavoriteViewHolder(binding, onClick, onFavorite)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: FavoriteViewHolder) {
        holder.unbind()
    }

    class FavoriteViewHolder(
        private val binding: ViewItemFavoriteBinding,
        private val onClick: (FavoriteEntity) -> Unit,
        private val onFavorite: (FavoriteEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var current: FavoriteEntity? = null

        fun bind(entity: FavoriteEntity) {
            current = entity
            val ctx = binding.root.context

            binding.root.setOnClickListener { current?.let(onClick) }

            binding.favoriteChannelName.text = entity.name.ifBlank {
                ctx.getString(R.string.view_item_channel_default_name)
            }
            binding.favoriteChannelCategory.text = entity.category.ifBlank {
                ctx.getString(R.string.view_item_favorite_default_category)
            }

            Glide.with(binding.favoriteChannelLogo)
                .load(entity.logoUrl)
                .placeholder(R.drawable.bg_team_logo_placeholder)
                .circleCrop()
                .into(binding.favoriteChannelLogo)

            binding.favoriteChannelLogo.contentDescription = entity.name.ifBlank {
                ctx.getString(R.string.view_item_channel_default_name)
            }

            // Heart is ALWAYS filled on the Favorites screen — these rows
            // are by definition already-favorited. The click toggles
            // (un-favorite) and animates via the on-screen
            // ItemTouchHelper swipe + undo Snackbar.
            binding.favoriteHeartButton.setIconResource(R.drawable.ic_heart_filled)
            binding.favoriteHeartButton.contentDescription =
                ctx.getString(R.string.favorites_heart_remove_desc)
            binding.favoriteHeartButton.setOnClickListener { current?.let(onFavorite) }
        }

        fun unbind() {
            current = null
            Glide.with(binding.favoriteChannelLogo).clear(binding.favoriteChannelLogo)
        }
    }

    private object FavoriteDiff : DiffUtil.ItemCallback<FavoriteEntity>() {
        override fun areItemsTheSame(a: FavoriteEntity, b: FavoriteEntity): Boolean =
            a.channelId == b.channelId

        override fun areContentsTheSame(a: FavoriteEntity, b: FavoriteEntity): Boolean = a == b
    }
}
