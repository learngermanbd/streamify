package com.streamify.app.ui.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView
import com.streamify.app.R
import com.streamify.app.data.models.Notice
import com.streamify.app.data.models.NoticeAttachment
import com.streamify.app.data.models.NoticeAttachmentType
import com.streamify.app.data.models.NoticeSection

/**
 * Phase 5 · Step 5.5 v2 — multi-section RecyclerView adapter.
 *
 * Sealed [Row] model lets us mix section dividers and notice cards
 * in a single list. DiffUtil + stable IDs:
 *   - hashCode long IDs derived from the `Row` discriminant so
 *     re-binding does NOT crash ProgressBar animations.
 *   - onViewRecycled() clears the Glide-bound ImageView so cross-row
 *     bucket promotion can't leak a stale thumbnail.
 *
 * Click handlers are set inside bind() (not init) so a recycled
 * `current: Notice?` reference can't fire for the wrong row.
 *
 * Phase 5 · Step 5.7 — IMAGE-attachment thumbnails now load via Glide
 * (mirrors how [com.streamify.app.ui.adapters.HighlightAdapter] does
 * its 16:9 thumbnails). The placeholder for missing images is
 * `bg_glass_card` (the same generic glass frame Step 1.5 introduced)
 * so the loading state is consistent across screens.
 *
 * Layouts: see `res/layout/view_notice_section.xml` (header) and
 * `res/layout/view_notice_card.xml` (notice + attachments).
 */
class NoticeAdapter(
    private val onNoticeClick: (Notice) -> Unit,
    private val onAttachmentClick: (NoticeAttachment) -> Unit,
) : ListAdapter<NoticeAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    sealed class Row {
        abstract val stableId: Long

        data class Header(val section: NoticeSection, val count: Int) : Row() {
            override val stableId: Long = ("h:" + section.name).hashCode().toLong()
        }

        data class NoticeRow(val notice: Notice) : Row() {
            override val stableId: Long = ("n:" + notice.id).hashCode().toLong()
        }
    }

    init { setHasStableIds(true) }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Header   -> VIEW_TYPE_HEADER
        is Row.NoticeRow -> VIEW_TYPE_NOTICE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderVH(inf.inflate(R.layout.view_notice_section, parent, false))
            else             -> NoticeVH(inf.inflate(R.layout.view_notice_card, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header   -> (holder as HeaderVH).bind(row)
            is Row.NoticeRow -> (holder as NoticeVH).bind(row.notice)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is NoticeVH) holder.clearImage()
    }

    // ────────────── Header ──────────────

    internal class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.noticeSectionTitle)
        private val count: TextView = view.findViewById(R.id.noticeSectionCount)
        fun bind(row: Row.Header) {
            title.text = when (row.section) {
                NoticeSection.ALERT -> itemView.context.getString(R.string.notice_v2_section_alert)
                NoticeSection.INFO  -> itemView.context.getString(R.string.notice_v2_section_info)
                NoticeSection.PROMO -> itemView.context.getString(R.string.notice_v2_section_promo)
            }
            count.text = itemView.resources.getQuantityString(
                R.plurals.notice_v2_section_count, row.count, row.count
            )
        }
    }

    // ────────────── Notice card ──────────────

    internal inner class NoticeVH(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.noticeCard)
        private val sectionBadge: TextView = view.findViewById(R.id.noticeCardSectionBadge)
        private val priorityChip: View = view.findViewById(R.id.noticeCardPriorityChip)
        private val titleView: TextView = view.findViewById(R.id.noticeCardTitle)
        private val bodyView: TextView = view.findViewById(R.id.noticeCardBody)
        private val timestamp: TextView = view.findViewById(R.id.noticeCardTimestamp)
        private val pushDot: View = view.findViewById(R.id.noticeCardPushDot)
        private val attachmentsStrip: LinearLayout = view.findViewById(R.id.noticeCardAttachments)
        private val thumbnail: ImageView = view.findViewById(R.id.noticeCardThumbnail)
        private var currentNotice: Notice? = null

        fun bind(notice: Notice) {
            currentNotice = notice
            titleView.text = notice.title
            bodyView.text = notice.body
            timestamp.text = formatRelative(notice.createdAt)

            // Section badge + tint
            sectionBadge.text = when (notice.section) {
                NoticeSection.ALERT -> itemView.context.getString(R.string.notice_v2_section_alert_short)
                NoticeSection.INFO  -> itemView.context.getString(R.string.notice_v2_section_info_short)
                NoticeSection.PROMO -> itemView.context.getString(R.string.notice_v2_section_promo_short)
            }
            val badgeColor = when (notice.section) {
                NoticeSection.ALERT -> 0xFFEF4444.toInt()  // red
                NoticeSection.INFO  -> 0xFF1CCBD4.toInt()  // primary
                NoticeSection.PROMO -> 0xFFA78BFA.toInt()  // purple
            }
            sectionBadge.setBackgroundColor(badgeColor)

            // Priority chip visible only if priority > 0
            priorityChip.visibility = if (notice.priority > 0) View.VISIBLE else View.GONE

            // Push dot only when this notice was sourced from FCM
            pushDot.visibility = if (notice.isPushSourced) View.VISIBLE else View.GONE

            // Attachments — clear any prior children, then render fresh.
            attachmentsStrip.removeAllViews()
            renderAttachments(notice.attachments, attachmentsStrip)

            // Thumbnail: first IMAGE attachment wins (center-cropped preview).
            // Glide handles placeholder + error + cleared-on-recycle for us.
            val firstImage = notice.attachments.firstOrNull { it.type == NoticeAttachmentType.IMAGE }
            if (firstImage != null) {
                thumbnail.visibility = View.VISIBLE
                Glide.with(thumbnail.context)
                    .load(firstImage.url)
                    .placeholder(R.drawable.bg_glass_card)
                    .error(R.drawable.bg_glass_card)
                    .centerCrop()
                    .into(thumbnail)
            } else {
                // Ensure no stale image survives from a previous bind.
                Glide.with(thumbnail.context).clear(thumbnail)
                thumbnail.visibility = View.GONE
            }

            card.setOnClickListener { currentNotice?.let(onNoticeClick) }
        }

        fun clearImage() {
            currentNotice = null
            // Cancel any in-flight Glide load and reset the placeholder so
            // a previous thumbnail can't bleed into the next row.
            Glide.with(thumbnail.context).clear(thumbnail)
            thumbnail.visibility = View.GONE
            attachmentsStrip.removeAllViews()
        }

        private fun renderAttachments(items: List<NoticeAttachment>, strip: LinearLayout) {
            if (items.isEmpty()) {
                strip.visibility = View.GONE
                return
            }
            strip.visibility = View.VISIBLE
            val inf = LayoutInflater.from(strip.context)
            items.forEach { att ->
                val chip = inf.inflate(R.layout.view_notice_attachment_chip, strip, false)
                val label: TextView = chip.findViewById(R.id.attachmentChipLabel)
                chip.findViewById<ImageView>(R.id.attachmentChipIcon)
                    .setImageResource(R.drawable.ic_notice_section_info)

                label.text = att.label ?: when (att.type) {
                    NoticeAttachmentType.IMAGE -> strip.context.getString(R.string.notice_v2_attachment_image)
                    NoticeAttachmentType.LINK  -> strip.context.getString(R.string.notice_v2_attachment_link)
                    NoticeAttachmentType.FILE  -> strip.context.getString(R.string.notice_v2_attachment_file)
                }
                chip.setOnClickListener {
                    // Avoid floating Uri.parse errors for invalid URLs.
                    runCatching {
                        val uri = Uri.parse(att.url)
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        strip.context.startActivity(intent)
                    }.onFailure {
                        // Silent: malformed URL won't crash but also won't fire onAttachmentClick.
                        onAttachmentClick(att)
                    }
                }
                strip.addView(chip)
            }
        }

        private fun formatRelative(epochMs: Long): String {
            val diff = System.currentTimeMillis() - epochMs
            return when {
                diff < 60_000L       -> itemView.context.getString(R.string.notice_v2_relative_now)
                diff < 60 * 60_000L  -> itemView.context.getString(R.string.notice_v2_relative_hours_template, (diff / 60_000L).toInt())
                diff < 24 * 60 * 60_000L -> itemView.context.getString(R.string.notice_v2_relative_hours_template, (diff / (60 * 60_000L)).toInt())
                else -> itemView.context.getString(R.string.notice_v2_relative_days_template, (diff / (24 * 60 * 60_000L)).toInt())
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_NOTICE = 1

        /**
         * Builds the [Row] list from a [NoticeSectionBlock] list.
         * Stable order: ALERT → INFO → PROMO. Kotlin-layer sorting
         * is the canonical ordering source — the SQL CASE in
         * [com.streamify.app.data.local.NoticeDao.observeAll] orders
         * the 3 known sections by enum name with `ELSE 3` fall-through,
         * but Section.ordinal is the durable truth.
         */
        fun rowsOf(blocks: List<com.streamify.app.ui.viewmodels.NoticeSectionBlock>): List<Row> {
            val ordered = blocks.sortedBy { it.section.ordinal }
            val out = ArrayList<Row>(ordered.sumOf { it.notices.size + 1 })
            ordered.forEach { block ->
                out += Row.Header(block.section, block.notices.size)
                block.notices.forEach { out += Row.NoticeRow(it) }
            }
            return out
        }

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem.stableId == newItem.stableId
            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem == newItem
        }
    }
}
