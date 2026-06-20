package com.streamify.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamify.app.R
import com.streamify.app.data.models.Channel
import com.streamify.app.databinding.ViewItemChannelBinding

/**
 * Phase 3 · Step 3.4 — RecyclerView adapter for the Categories grid.
 *
 * Built on [ListAdapter] (with [DiffUtil]) so submitting a new list of
 * channels triggers minimal-rebind updates. Stable IDs are enabled so a
 * LIVE dot toggled `gone -> visible` on an already-rendered row does NOT
 * churn the entire ViewHolder.
 *
 * Click listener is provided by the screen \u2014 Step 4.2 will wire it to
 * [com.streamify.app.ui.activities.MainActivity.startActivity(PlayerActivity intent)].
 * For now the callback is a no-op so taps don't crash.
 */
class CategoryAdapter(
    private val onClick: (Channel) -> Unit
) : ListAdapter<Channel, CategoryAdapter.ChannelViewHolder>(ChannelDiff) {

    init {
        // Stable IDs so the same Channel row stays in the same ViewHolder
        // across rebinds (DiffUtil reorders + inserts, but a stable-ID
        // ViewHolder survives the shuffle).
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ViewItemChannelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChannelViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ChannelViewHolder) {
        holder.unbind()
    }

    class ChannelViewHolder(
        private val binding: ViewItemChannelBinding,
        private val onClick: (Channel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * Backing reference to the bound Channel. `null` after [unbind] so
         * the click listener cannot fire on a recycled view that no longer
         * holds meaningful data. The click listener is attached inside
         * [bind] \u2014 not `init` \u2014 so every rebind re-establishes it across
         * recycles (matches the Step 3.3 SHOULDFIX for EventAdapter).
         */
        private var current: Channel? = null

        fun bind(channel: Channel) {
            current = channel
            binding.root.setOnClickListener { current?.let(onClick) }

            val ctx = binding.root.context

            // Name + category fallback to a generic label.
            binding.channelName.text = channel.name.ifBlank {
                ctx.getString(R.string.view_item_channel_default_name)
            }
            binding.categoryLabel.text = channel.category

            // Channel logo: Glide fetches + circle-crops; the oval
            // bg_team_logo_placeholder is the round shape underneath
            // while the logo is loading.
            Glide.with(binding.channelLogo)
                .load(channel.logoUrl)
                .placeholder(R.drawable.bg_team_logo_placeholder)
                .circleCrop()
                .into(binding.channelLogo)

            // Per-row contentDescription for TalkBack (overrides the
            // layout's static `Channel logo` which would otherwise read
            // identically for every category card).
            binding.channelLogo.contentDescription =
                channel.name.ifBlank { ctx.getString(R.string.view_item_channel_default_name) }

            // LIVE dot \u2014 only shown when channel.isActive is true.
            // Channel.isActive mirrors the backend's per-channel toggle,
            // so a freshly-tagged "currently broadcasting" channel lights
            // up without needing an `isLive` field on the model.
            binding.liveDot.isVisible = channel.isActive
        }

        /**
         * Reset state on recycle so a stale Channel can't be re-fired
         * by the click listener (the closure gates on `current?.let(...)`)
         * and so Glide releases its image request.
         */
        fun unbind() {
            current = null
            Glide.with(binding.channelLogo).clear(binding.channelLogo)
        }
    }

    /**
     * DiffUtil callback. `areItemsTheSame` compares Channel.id (unique
     * server-side identifier); `areContentsTheSame` falls back to
     * data-class equality, which catches name / isActive / category
     * changes automatically.
     */
    private object ChannelDiff : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(a: Channel, b: Channel): Boolean = a.id == b.id
        override fun areContentsTheSame(a: Channel, b: Channel): Boolean = a == b
    }
}
