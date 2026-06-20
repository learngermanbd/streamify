package com.streamify.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.streamify.app.R
import com.streamify.app.data.models.RecentStreamUrl
import com.streamify.app.databinding.ViewItemRecentUrlBinding

/**
 * Phase 5 · Step 5.3 — RecyclerView adapter for the Network Stream screen's
 * Recently-Used list.
 *
 * Each row shows the URL (ellipsized middle so the protocol is always
 * visible) and a Material 3 chip with the format chosen at save time
 * (AUTO / HLS / DASH). Long-press fires [onRemove] so the user can prune
 * the list without leaving the screen.
 */
class RecentUrlAdapter(
    private val onTap: (RecentStreamUrl) -> Unit,
    private val onRemove: (RecentStreamUrl) -> Unit
) : ListAdapter<RecentStreamUrl, RecentUrlAdapter.RecentUrlViewHolder>(RecentUrlDiff) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        getItem(position).url.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentUrlViewHolder {
        val binding = ViewItemRecentUrlBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RecentUrlViewHolder(binding, onTap, onRemove)
    }

    override fun onBindViewHolder(holder: RecentUrlViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: RecentUrlViewHolder) {
        holder.unbind()
    }

    class RecentUrlViewHolder(
        private val binding: ViewItemRecentUrlBinding,
        private val onTap: (RecentStreamUrl) -> Unit,
        private val onRemove: (RecentStreamUrl) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var current: RecentStreamUrl? = null

        fun bind(entry: RecentStreamUrl) {
            current = entry
            binding.root.setOnClickListener { current?.let(onTap) }
            binding.root.setOnLongClickListener {
                current?.let(onRemove)
                true
            }
            binding.recentUrlText.text = entry.url
            binding.recentUrlText.contentDescription = entry.url
            bindFormatChip(binding.recentUrlFormatChip, entry.formatLabel)
            binding.recentUrlRemoveButton.setOnClickListener { current?.let(onRemove) }
        }

        fun unbind() {
            current = null
            binding.root.setOnClickListener(null)
            binding.root.setOnLongClickListener(null)
            binding.recentUrlRemoveButton.setOnClickListener(null)
        }

        private fun bindFormatChip(chip: Chip, label: String) {
            val ctx = chip.context
            // DASH uses warning_orange (the project palette has no
            // purple). HLS uses the brand primary; AUTO uses the muted
            // text gray so it reads as "fallback / let Media3 decide".
            val (text, colorRes) = when (label) {
                RecentStreamUrl.FORMAT_HLS  -> "HLS"  to R.color.primary
                RecentStreamUrl.FORMAT_DASH -> "DASH" to R.color.warning_orange
                else                        -> "AUTO" to R.color.text_muted
            }
            chip.text = text
            // ContextCompat.getColorStateList handles BOTH `<selector>` XML
            // and solid `<color name="...">` defs. Direct
            // Resources.getColorStateList(int, Theme) throws NotFoundException
            // on resources that aren't state-list.
            chip.chipBackgroundColor = ContextCompat.getColorStateList(ctx, colorRes)
            chip.isVisible = true
        }
    }

    private object RecentUrlDiff : DiffUtil.ItemCallback<RecentStreamUrl>() {
        override fun areItemsTheSame(a: RecentStreamUrl, b: RecentStreamUrl): Boolean =
            a.url == b.url
        override fun areContentsTheSame(a: RecentStreamUrl, b: RecentStreamUrl): Boolean =
            a == b
    }
}
