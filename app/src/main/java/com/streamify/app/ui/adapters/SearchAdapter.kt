package com.streamify.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamify.app.R
import com.streamify.app.data.models.Channel
import com.streamify.app.data.models.Event
import com.streamify.app.data.models.Highlight

/**
 * Phase 5 · Step 5.6 — Multi-type search results RecyclerView adapter.
 *
 * `SearchItem` is the sealed type fed to this adapter. The adapter is a
 * `ListAdapter` + `DiffUtil` over the flat SearchItem list. Section
 * boundaries are drawn by [SearchItem.Header] rows; everything else is a
 * regular data row.
 *
 * We use a single RecyclerView with 4 view types rather than
 * ConcatAdapter because:
 *  - DiffUtil cross-section moves (e.g. a previously-matching Channel
 *    losing its match), need one global item-position model.
 *  - Stable IDs let the framework animate header changes cleanly when
 *    a section grows from 0 → 1 (HEADER row appears) and back.
 *
 * ViewHolders are kept small (bind() + click-prop); all formatting
 * (relative date, duration) happens upstream in the model layer
 * (Highlight companions + adapters elsewhere) — we re-use
 * `formatDurationN`/`formatViewsN` lambdas here for parity with
 * HighlightsAdapter.
 */
class SearchAdapter(
    private val onEventClick: (Event) -> Unit,
    private val onChannelClick: (Channel) -> Unit,
    private val onHighlightClick: (Highlight) -> Unit
) : ListAdapter<SearchItem, RecyclerView.ViewHolder>(Diff) {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).stableId

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchItem.Header         -> VIEW_TYPE_HEADER
        is SearchItem.EventRow       -> VIEW_TYPE_EVENT
        is SearchItem.ChannelRow     -> VIEW_TYPE_CHANNEL
        is SearchItem.HighlightRow   -> VIEW_TYPE_HIGHLIGHT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderVH(
                inflater.inflate(R.layout.view_search_section_header, parent, false)
            )
            VIEW_TYPE_EVENT -> EventVH(
                inflater.inflate(R.layout.view_search_event, parent, false)
            )
            VIEW_TYPE_CHANNEL -> ChannelVH(
                inflater.inflate(R.layout.view_search_channel, parent, false)
            )
            VIEW_TYPE_HIGHLIGHT -> HighlightVH(
                inflater.inflate(R.layout.view_search_highlight, parent, false)
            )
            else -> throw IllegalStateException("Unknown viewType=$viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchItem.Header -> (holder as HeaderVH).bind(item)
            is SearchItem.EventRow -> (holder as EventVH).bind(item)
            is SearchItem.ChannelRow -> (holder as ChannelVH).bind(item)
            is SearchItem.HighlightRow -> (holder as HighlightVH).bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is HighlightVH) {
            Glide.with(holder.itemView.context).clear(holder.thumbnail)
        }
        super.onViewRecycled(holder)
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.searchSectionHeaderText)
        fun bind(item: SearchItem.Header) {
            title.text = item.title
        }
    }

    inner class EventVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.searchEventTitle)
        private val subtitle: TextView = view.findViewById(R.id.searchEventSubtitle)
        private val teamA: TextView = view.findViewById(R.id.searchEventTeamA)
        private val teamB: TextView = view.findViewById(R.id.searchEventTeamB)
        private var current: SearchItem.EventRow? = null

        init {
            view.setOnClickListener { current?.let { onEventClick(it.event) } }
        }

        fun bind(item: SearchItem.EventRow) {
            current = item
            val ctx = itemView.context
            title.text = item.event.title.ifBlank { ctx.getString(R.string.view_item_match_default_title) }
            subtitle.text = listOf(item.event.category, item.event.date, item.event.time)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            teamA.text = item.event.teamA.name.ifBlank { ctx.getString(R.string.view_item_match_default_team) }
            teamB.text = item.event.teamB.name.ifBlank { ctx.getString(R.string.view_item_match_default_team) }
        }
    }

    inner class ChannelVH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.searchChannelName)
        private val category: TextView = view.findViewById(R.id.searchChannelCategory)
        private var current: SearchItem.ChannelRow? = null

        init {
            view.setOnClickListener { current?.let { onChannelClick(it.channel) } }
        }

        fun bind(item: SearchItem.ChannelRow) {
            current = item
            val ctx = itemView.context
            name.text = item.channel.name.ifBlank { ctx.getString(R.string.view_item_channel_default_name) }
            category.text = item.channel.category.ifBlank {
                ctx.getString(R.string.view_item_favorite_default_category)
            }
        }
    }

    inner class HighlightVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.searchHighlightTitle)
        private val meta: TextView = view.findViewById(R.id.searchHighlightMeta)
        private val duration: TextView = view.findViewById(R.id.searchHighlightDuration)
        val thumbnail: android.widget.ImageView = view.findViewById(R.id.searchHighlightThumb)
        private var current: SearchItem.HighlightRow? = null

        init {
            view.setOnClickListener { current?.let { onHighlightClick(it.highlight) } }
        }

        fun bind(item: SearchItem.HighlightRow) {
            current = item
            val ctx = itemView.context
            title.text = item.highlight.title.ifBlank { ctx.getString(R.string.view_item_highlight_default_title) }
            duration.text = item.highlight.duration.formatDurationN()
            val viewsText = item.highlight.views.toLong().formatViewsAbbrev()
            val dateLabel = if (item.highlight.date > 0)
                item.highlight.date.toRelativeDate() else ""
            meta.text = listOf(viewsText, dateLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            thumbnail.contentDescription = title.text
            Glide.with(ctx)
                .load(item.highlight.thumbnailUrl)
                .placeholder(R.drawable.bg_highlight_placeholder)
                .centerCrop()
                .into(thumbnail)
        }
    }

    private fun Int.formatDurationN(): String {
        if (this <= 0) return "00:00"
        val h = this / 3600
        val m = (this % 3600) / 60
        val s = this % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun Long.formatViewsAbbrev(): String {
        if (this <= 0) return ""
        return when {
            this < 1000 -> this.toString()
            this < 1_000_000 -> "%.1fK".format(this / 1000.0)
            this < 1_000_000_000 -> "%.1fM".format(this / 1_000_000.0)
            else -> "%.1fB".format(this / 1_000_000_000.0)
        }
    }

    private fun Long.toRelativeDate(): String {
        if (this <= 0) return ""
        val diffMs = System.currentTimeMillis() - this
        val days = diffMs / (1000L * 60 * 60 * 24)
        return when {
            days < 1 -> "today"
            days == 1L -> "yesterday"
            days < 7 -> "$days days ago"
            days < 30 -> "${days / 7} weeks ago"
            days < 365 -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
    }

    private object Diff : DiffUtil.ItemCallback<SearchItem>() {
        override fun areItemsTheSame(oldItem: SearchItem, newItem: SearchItem): Boolean =
            oldItem.stableId == newItem.stableId
        override fun areContentsTheSame(oldItem: SearchItem, newItem: SearchItem): Boolean =
            oldItem == newItem
    }

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_EVENT = 1
        const val VIEW_TYPE_CHANNEL = 2
        const val VIEW_TYPE_HIGHLIGHT = 3
    }
}

/**
 * Sealed source-of-truth row type for the search results list.
 * `stableId` is content-derived (header text or model id hash) so
 * DiffUtil can reuse ViewHolders across list updates without flicker.
 */
sealed class SearchItem {
    abstract val stableId: Long

    data class Header(val title: String) : SearchItem() {
        override val stableId: Long = ("hdr:" + title).hashCode().toLong()
    }
    data class EventRow(val event: Event) : SearchItem() {
        override val stableId: Long = ("ev:" + event.id).hashCode().toLong()
    }
    data class ChannelRow(val channel: Channel) : SearchItem() {
        override val stableId: Long = ("ch:" + channel.id).hashCode().toLong()
    }
    data class HighlightRow(val highlight: Highlight) : SearchItem() {
        override val stableId: Long = ("hl:" + highlight.id).hashCode().toLong()
    }
}
