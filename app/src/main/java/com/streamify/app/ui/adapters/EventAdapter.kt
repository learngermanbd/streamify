package com.streamify.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamify.app.R
import com.streamify.app.data.models.Event
import com.streamify.app.databinding.ViewItemMatchBinding

/**
 * Phase 3 · Step 3.3 — RecyclerView adapter for the Home tab's match list.
 *
 * Built on [ListAdapter] (with [DiffUtil]) so submitting a new list
 * triggers minimal-rebind updates. Stable IDs are enabled so Recycling
 * keeps the same [RecyclerView.ViewHolder] across a rebind of the
 * underlying Event (so the LIVE pulse animation does NOT restart every
 * time the same row redraws).
 *
 * Click listener is provided by the screen — Step 4.2 will wire it to
 * [MainActivity.startActivity(PlayerActivity intent)]. For now the
 * callback is a no-op so taps don't crash.
 */
class EventAdapter(
    private val onClick: (Event) -> Unit
) : ListAdapter<Event, EventAdapter.EventViewHolder>(EventDiff) {

    init {
        // Stable IDs so the same Event row stays in the same ViewHolder
        // across rebinds (DiffUtil reorders + inserts, but the underlying
        // ViewHolder for a stable ID stays attached).
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        // Event.id is a string. hashCode().toLong() is the standard pattern
        // for stable IDs in RecyclerView — collision risk for our scale (~hundreds
        // of events) is negligible.
        getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ViewItemMatchBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return EventViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: EventViewHolder) {
        // Clear the LIVE pulse animation when the row is recycled so the
        // next bind starts from a fresh state.
        holder.unbind()
    }

    class EventViewHolder(
        private val binding: ViewItemMatchBinding,
        private val onClick: (Event) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * Backing reference to the bound Event. `null` after [unbind] so
         * the click listener cannot fire on a recycled view that no longer
         * holds meaningful data. The click listener is set inside [bind]
         * (NOT in `init`) because ViewHolders are not recreated on rebind
         * — `init` runs once per instance, so a click listener attached
         * there would only fire for the FIRST bind of a recycled
         * ViewHolder. Re-attaching in `bind` keeps the listener live
         * across all binds.
         */
        private var current: Event? = null

        fun bind(event: Event) {
            current = event
            binding.root.setOnClickListener { current?.let(onClick) }
            val ctx = binding.root.context

            // Title fallback if the API sends an empty title.
            binding.title.text = event.title.ifBlank {
                ctx.getString(R.string.view_item_match_default_title)
            }
            binding.subtitle.text = buildSubtitle(ctx, event)
            binding.vsText.text = ctx.getString(R.string.view_item_match_vs)

            // Team names ("Team A" / "Team B") — fall back to "TBD" when
            // the backend leaves team names empty.
            binding.teamAName.text = event.teamA.name.ifBlank {
                ctx.getString(R.string.view_item_match_default_team)
            }
            binding.teamBName.text = event.teamB.name.ifBlank {
                ctx.getString(R.string.view_item_match_default_team)
            }

            // Team logos. Glide fetches + circle-crops; the round
            // bg_team_logo_placeholder draws underneath while loading.
            Glide.with(binding.teamALogo)
                .load(event.teamA.logoUrl)
                .placeholder(R.drawable.bg_team_logo_placeholder)
                .circleCrop()
                .into(binding.teamALogo)

            Glide.with(binding.teamBLogo)
                .load(event.teamB.logoUrl)
                .placeholder(R.drawable.bg_team_logo_placeholder)
                .circleCrop()
                .into(binding.teamBLogo)

            // Per-row contentDescription for TalkBack — the layout's
            // static `Team A logo` / `Team B logo` would otherwise read
            // identically for every row. Fall back to the TBD sentinel
            // when the API sends an empty team name (matches the
            // teamAName / teamBName fall-back below).
            binding.teamALogo.contentDescription =
                event.teamA.name.ifBlank { ctx.getString(R.string.view_item_match_default_team) }
            binding.teamBLogo.contentDescription =
                event.teamB.name.ifBlank { ctx.getString(R.string.view_item_match_default_team) }

            // LIVE badge — visible iff isLive, and start the pulse
            // animation on first bind of a LIVE row. Stable IDs keep the
            // ViewHolder across rebinds so the animation is NOT restarted
            // when DiffUtil shuffles a LIVE row around the list.
            if (event.isLive) {
                binding.liveBadge.isVisible = true
                if (binding.liveBadge.animation == null) {
                    binding.liveBadge.startAnimation(
                        AnimationUtils.loadAnimation(ctx, R.anim.live_pulse)
                    )
                }
            } else {
                binding.liveBadge.isVisible = false
                binding.liveBadge.clearAnimation()
            }
        }

        /**
         * Reset state on recycle so a stale Event can't be re-fired by the
         * click listener (the closure already gates on `current?.let(...)`),
         * and so memory-heavy listeners (Glide image requests, pulse
         * animation) drop their references. We deliberately leave the
         * OnClickListener attached — `bind()` re-establishes `current` and
         * the closure becomes live again on the next bind without needing
         * a fresh listener registration.
         */
        fun unbind() {
            current = null
            binding.liveBadge.clearAnimation()
            Glide.with(binding.teamALogo).clear(binding.teamALogo)
            Glide.with(binding.teamBLogo).clear(binding.teamBLogo)
        }

        private fun buildSubtitle(ctx: android.content.Context, event: Event): CharSequence {
            val category = event.category.takeIf { it.isNotBlank() }
            val when_ = "${event.date} ${event.time}".trim().takeIf { it.isNotBlank() }
            return when {
                category != null && when_ != null -> "$category · $when_"
                category != null -> category
                when_ != null -> when_
                else -> ""
            }
        }
    }

    /**
     * DiffUtil callback. `areItemsTheSame` compares the Event.id (unique
     * server-side identifier); `areContentsTheSame` falls back to data-class
     * equality, which catches title / isLive / team changes automatically.
     */
    private object EventDiff : DiffUtil.ItemCallback<Event>() {
        override fun areItemsTheSame(a: Event, b: Event): Boolean = a.id == b.id
        override fun areContentsTheSame(a: Event, b: Event): Boolean = a == b
    }
}
