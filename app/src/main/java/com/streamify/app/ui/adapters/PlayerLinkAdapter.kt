package com.streamify.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamify.app.data.models.StreamLink
import com.streamify.app.databinding.ViewPlayerLinkChipBinding

/**
 * Phase 4 · Step 4.2 — RecyclerView adapter for the player links bar.
 *
 * Renders one chip per [StreamLink]. Checked state reflects the currently
 * active link (data-driven; Activity keeps selection). Stable IDs so
 * DiffUtil flips don't churn the ViewHolder pool when the user switches
 * links mid-playback.
 */
class PlayerLinkAdapter(
    private val onClick: (StreamLink) -> Unit
) : ListAdapter<StreamLink, PlayerLinkAdapter.LinkViewHolder>(LinkDiff) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        // StreamLink doesn't carry a stable id field — use name+url hash.
        getItem(position).let { (it.name + it.url).hashCode().toLong() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinkViewHolder {
        val binding = ViewPlayerLinkChipBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return LinkViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: LinkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LinkViewHolder(
        private val binding: ViewPlayerLinkChipBinding,
        private val onClick: (StreamLink) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var current: StreamLink? = null

        fun bind(link: StreamLink) {
            current = link
            val name = link.name.ifBlank { link.quality.name }
            binding.linkChip.text = "$name \u00b7 ${link.quality.name}"
            binding.linkChip.isChecked = false  // Activity drives selection
            binding.linkChip.setOnClickListener { current?.let(onClick) }
            // TTS announcement on select for screen readers.
            binding.linkChip.contentDescription = "Stream $name quality ${link.quality.name}"
        }
    }

    private object LinkDiff : DiffUtil.ItemCallback<StreamLink>() {
        override fun areItemsTheSame(a: StreamLink, b: StreamLink): Boolean =
            a.name == b.name && a.url == b.url
        override fun areContentsTheSame(a: StreamLink, b: StreamLink): Boolean = a == b
    }
}
