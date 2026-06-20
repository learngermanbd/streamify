package com.streamify.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamify.app.R
import com.streamify.app.data.models.Highlight
import com.streamify.app.databinding.ViewItemHighlightBinding

/**
 * Phase 3 · Step 3.5 — RecyclerView adapter for the Highlights tab.
 *
 * Built on [ListAdapter] (with [DiffUtil]) so submitting a new list of
 * highlights triggers minimal-rebind updates. Stable IDs are enabled so
 * recycling keeps the same [RecyclerView.ViewHolder] across a rebind of
 * the underlying Highlight (so an in-flight Glide image request keeps
 * its target on the same View).
 *
 * Click listener is provided by the screen — Step 4.2 will wire it to
 * a future PlayerActivity intent. For now the callback is a no-op so
 * taps don't crash.
 */
class HighlightAdapter(
    private val onClick: (Highlight) -> Unit
) : ListAdapter<Highlight, HighlightAdapter.HighlightViewHolder>(HighlightDiff) {

    init {
        // Stable IDs so the same Highlight row stays in the same
        // ViewHolder across rebinds (DiffUtil reorders + inserts, but
        // a stable-ID ViewHolder survives the shuffle so an in-flight
        // Glide request keeps targeting the right ImageView).
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HighlightViewHolder {
        val binding = ViewItemHighlightBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HighlightViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: HighlightViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: HighlightViewHolder) {
        holder.unbind()
    }

    class HighlightViewHolder(
        private val binding: ViewItemHighlightBinding,
        private val onClick: (Highlight) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * Backing reference to the bound Highlight. `null` after [unbind]
         * so the click listener cannot fire on a recycled view that no
         * longer holds meaningful data. The click listener is attached
         * inside [bind] — not `init` — so every rebind re-establishes it
         * across recycles (matches the Step 3.3 SHOULDFIX for
         * EventAdapter).
         */
        private var current: Highlight? = null

        fun bind(highlight: Highlight) {
            current = highlight
            binding.root.setOnClickListener { current?.let(onClick) }

            val ctx = binding.root.context

            // Title fallback when API sends an empty title.
            binding.title.text = highlight.title.ifBlank {
                ctx.getString(R.string.view_item_highlight_default_title)
            }

            // Thumbnail — Glide fetches + centerCrop to fill the 16:9
            // box; the rounded-rect bg_highlight_placeholder shows
            // underneath while the real thumbnailUrl is in flight.
            Glide.with(binding.thumbnail)
                .load(highlight.thumbnailUrl)
                .placeholder(R.drawable.bg_highlight_placeholder)
                .centerCrop()
                .into(binding.thumbnail)

            // Per-row contentDescription for TalkBack (overrides the
            // layout's static `Highlight` which would otherwise read
            // identically for every thumbnail + overlay pair).
            val title = highlight.title.ifBlank {
                ctx.getString(R.string.view_item_highlight_default_title)
            }
            binding.thumbnail.contentDescription = title
            binding.playOverlay.contentDescription = "Play $title"

            // Duration pill — format as mm:ss (or H:mm:ss when >= 1 h).
            binding.duration.text = formatDuration(highlight.duration)

            // Meta line — condensed views count + relative date.
            binding.metaLine.text = buildMetaLine(ctx, highlight)
        }

        /**
         * Reset state on recycle so a stale Highlight can't be re-fired
         * by the click listener (the closure gates on `current?.let(...)`)
         * and so Glide releases its in-flight image request from this
         * ImageView.
         */
        fun unbind() {
            current = null
            Glide.with(binding.thumbnail).clear(binding.thumbnail)
        }

        /**
         * Format the seconds-long [duration] as `m:ss` / `H:mm:ss`.
         *
         * Examples:
         *  - 0  -> "00:00"
         *  - 7  -> "00:07"
         *  - 154 -> "02:34"
         *  - 3725 -> "1:02:05"
         *
         * A negative input falls back to the default duration label.
         */
        private fun formatDuration(seconds: Int): String {
            if (seconds < 0) {
                return binding.root.context.getString(
                    R.string.view_item_highlight_default_duration
                )
            }
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%02d:%02d".format(m, s)
        }

        /**
         * Build the meta line: `12.4K views · 2 days ago`. Either side
         * may be empty (yielding e.g. `· 2 days ago` or `12.4K views ·`).
         */
        private fun buildMetaLine(ctx: android.content.Context, h: Highlight): String {
            val views = formatViewsString(h.views)
            val date = formatRelativeDate(h.date)
            return when {
                views.isNotEmpty() && date.isNotEmpty() -> "$views · $date"
                views.isNotEmpty() -> views
                date.isNotEmpty() -> date
                else -> ""
            }
        }

        /** Compact view-count formatter: 1542 -> 1.5K, 2_400_000 -> 2.4M. */
        private fun formatViewsString(views: Int): String = when {
            views <= 0 -> ""
            views >= 1_000_000 -> "%.1fM views".format(views / 1_000_000.0)
            views >= 1_000 -> "%.1fK views".format(views / 1_000.0)
            else -> "$views views"
        }

        /**
         * Relative date formatter (epoch ms -> human label). Buckets:
         *  - today
         *  - yesterday
         *  - N days ago (under a week)
         *  - N weeks ago (under a month)
         *  - N months ago  (under a year)
         *  - N years ago  (otherwise)
         *
         * Empty/future timestamps return "" so the meta line falls back
         * to views-only.
         */
        private fun formatRelativeDate(epochMs: Long): String {
            if (epochMs <= 0L) return ""
            val diff = System.currentTimeMillis() - epochMs
            if (diff < 0L) return ""
            val days = diff / (24L * 60 * 60 * 1000)
            return when {
                days < 1 -> "today"
                days == 1L -> "yesterday"
                days < 7 -> "$days days ago"
                days < 30 -> "${days / 7} weeks ago"
                days < 365 -> "${days / 30} months ago"
                else -> "${days / 365} years ago"
            }
        }
    }

    /**
     * DiffUtil callback. `areItemsTheSame` compares Highlight.id (unique
     * server-side identifier); `areContentsTheSame` falls back to
     * data-class equality, which catches title / views / date changes
     * automatically.
     */
    private object HighlightDiff : DiffUtil.ItemCallback<Highlight>() {
        override fun areItemsTheSame(a: Highlight, b: Highlight): Boolean = a.id == b.id
        override fun areContentsTheSame(a: Highlight, b: Highlight): Boolean = a == b
    }
}
