package com.streamify.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamify.app.R
import com.streamify.app.data.models.Banner
import com.streamify.app.databinding.ViewItemBannerBinding

/**
 * Phase 3 · Step 3.3 — RecyclerView adapter backing the Home tab's
 * auto-scroll banner [androidx.viewpager2.widget.ViewPager2].
 *
 * Built on [ListAdapter] + [DiffUtil] with stable IDs so a DiffUtil
 * shuffle (e.g. backend pushes a new sortOrder) doesn't reset Glide's
 * in-flight image request on the same slide.
 *
 * Click listener is provided by the screen — Step 4.x will wire it
 * to deep-link navigation via [Banner.linkUrl]. Until then taps
 * are a no-op so the carousel doesn't crash.
 */
class BannerAdapter(
    private val onClick: (Banner) -> Unit
) : ListAdapter<Banner, BannerAdapter.BannerViewHolder>(BannerDiff) {

    init {
        // Stable IDs so Glide image requests stay targeted at the right
        // slide across DiffUtil shuffles (relies on Banner.id uniqueness
        // per the API contract).
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerViewHolder {
        val binding = ViewItemBannerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return BannerViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: BannerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: BannerViewHolder) {
        holder.unbind()
    }

    class BannerViewHolder(
        private val binding: ViewItemBannerBinding,
        private val onClick: (Banner) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * Back-reference to the bound [Banner]; `null` after [unbind] so
         * click listeners cannot fire against a recycled view that no
         * longer holds meaningful data. Listener is attached inside
         * [bind] — not in `init` — so each rebind establishes a live
         * closure (matches the Step 3.3 SHOULDFIX pattern from
         * EventAdapter / CategoryAdapter / HighlightAdapter).
         */
        private var current: Banner? = null

        fun bind(banner: Banner) {
            current = banner
            binding.root.setOnClickListener { current?.let(onClick) }

            val ctx = binding.root.context

            // Title fallback when API sends an empty title.
            val title = banner.title.ifBlank {
                ctx.getString(R.string.view_item_banner_default_title)
            }

            // ContentDescription for TalkBack (overrides the layout's
            // static default so each slide reads distinctly).
            binding.bannerImage.contentDescription = title

            // Image — Glide fetches + centerCrop to fill the 16:7 box;
            // bg_banner_placeholder shows underneath while loading.
            Glide.with(binding.bannerImage)
                .load(banner.imageUrl)
                .placeholder(R.drawable.bg_banner_placeholder)
                .centerCrop()
                .into(binding.bannerImage)

            // The card rootConstraintLayoutDimensionRatio can't be set
            // programmatically, so we mirror the layout's 16:7 ratio on
            // the inner ImageView (caller already sets height = 0dp so
            // the constraint ratio takes effect).
            binding.bannerImage.layoutParams =
                (binding.bannerImage.layoutParams as ConstraintLayout.LayoutParams).apply {
                    dimensionRatio = "16:7"
                }
        }

        /**
         * Reset state on recycle so a stale Banner can't be re-fired by
         * the click listener (closure gates on `current?.let(...)`) and
         * so Glide releases its in-flight image request from this ImageView.
         */
        fun unbind() {
            current = null
            Glide.with(binding.bannerImage).clear(binding.bannerImage)
        }
    }

    private object BannerDiff : DiffUtil.ItemCallback<Banner>() {
        override fun areItemsTheSame(a: Banner, b: Banner): Boolean = a.id == b.id
        override fun areContentsTheSame(a: Banner, b: Banner): Boolean = a == b
    }
}
