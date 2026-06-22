package com.streamify.app.ui.adapters

import android.os.CountDownTimer
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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * RecyclerView adapter for the Home tab's match list (Sportzfy design).
 *
 * Renders each match card with:
 *  - Row 1: Tournament info (left) + countdown timer or LIVE badge (right)
 *  - Row 2: Sport · League title
 *  - Row 3: (flag) Team A   VS   Team B (flag)
 */
class EventAdapter(
    private val onClick: (Event) -> Unit
) : ListAdapter<Event, EventAdapter.EventViewHolder>(EventDiff) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
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
        holder.unbind()
    }

    class EventViewHolder(
        private val binding: ViewItemMatchBinding,
        private val onClick: (Event) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var current: Event? = null
        private var countDownTimer: CountDownTimer? = null

        fun bind(event: Event) {
            current = event
            binding.root.setOnClickListener { current?.let(onClick) }
            val ctx = binding.root.context

            // Row 1: Tournament info (left side)
            binding.tournamentInfo.text = buildTournamentInfo(event)

            // Row 1: Right side — LIVE badge or countdown
            if (event.isLive) {
                binding.liveBadge.isVisible = true
                binding.timeBadge.isVisible = false
                if (binding.liveBadge.animation == null) {
                    binding.liveBadge.startAnimation(
                        AnimationUtils.loadAnimation(ctx, R.anim.live_pulse)
                    )
                }
            } else {
                binding.liveBadge.isVisible = false
                binding.liveBadge.clearAnimation()
                binding.timeBadge.isVisible = true
                startCountdown(event)
            }

            // Row 2: Sport · League
            binding.leagueInfo.text = buildLeagueInfo(event)

            // Row 3: Team flags + names
            val flagA = countryFlagFor(event.teamA.name)
            val flagB = countryFlagFor(event.teamB.name)
            binding.teamAFlag.text = flagA
            binding.teamBFlag.text = flagB
            binding.teamAFlag.isVisible = flagA.isNotBlank()
            binding.teamBFlag.isVisible = flagB.isNotBlank()

            binding.teamAName.text = event.teamA.name.ifBlank {
                ctx.getString(R.string.view_item_match_default_team)
            }
            binding.teamBName.text = event.teamB.name.ifBlank {
                ctx.getString(R.string.view_item_match_default_team)
            }

            // VS label
            binding.vsLabel.text = ctx.getString(R.string.view_item_match_vs)

            // Team logos (hidden in new design but kept for Glide compatibility)
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

            // Accessibility
            binding.teamAFlag.contentDescription = event.teamA.name
            binding.teamBFlag.contentDescription = event.teamB.name
        }

        fun unbind() {
            current = null
            countDownTimer?.cancel()
            countDownTimer = null
            binding.liveBadge.clearAnimation()
            Glide.with(binding.teamALogo).clear(binding.teamALogo)
            Glide.with(binding.teamBLogo).clear(binding.teamBLogo)
        }

        private fun startCountdown(event: Event) {
            countDownTimer?.cancel()
            val targetMs = parseEventTimeMs(event) ?: run {
                binding.timeBadge.text = formatTimeFallback(event)
                return
            }
            val now = System.currentTimeMillis()
            if (targetMs <= now) {
                binding.timeBadge.text = "⏰ Now"
                return
            }
            countDownTimer = object : CountDownTimer(targetMs - now, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    binding.timeBadge.text = "🕒 ${formatCountdown(millisUntilFinished)}"
                }
                override fun onFinish() {
                    binding.timeBadge.text = "⏰ Now"
                }
            }.start()
        }

        private fun buildTournamentInfo(event: Event): String {
            // Try to extract tournament/round from title
            val title = event.title.takeIf { it.isNotBlank() } ?: return ""
            // If title contains " • " or " - ", the first part is tournament info
            val parts = title.split(" • ", " - ", " – ", limit = 2)
            return if (parts.size > 1) "🏆 ${parts[0].trim()}" else "🏆 $title"
        }

        private fun buildLeagueInfo(event: Event): String {
            val sport = event.category.takeIf { it.isNotBlank() }
            val title = event.title.takeIf { it.isNotBlank() }
            return when {
                sport != null && title != null -> {
                    // Show sport first, then league/title
                    val league = title.split(" • ", " - ", " – ", limit = 2)
                    if (league.size > 1) "$sport ll ${league[1].trim()}" else "$sport ll $title"
                }
                title != null -> title
                sport != null -> sport
                else -> ""
            }
        }

        private fun formatTimeFallback(event: Event): String {
            val date = event.date.takeIf { it.isNotBlank() }
            val time = event.time.takeIf { it.isNotBlank() }
            return when {
                date != null && time != null -> "🕒 $time"
                time != null -> "🕒 $time"
                date != null -> "📅 $date"
                else -> "—"
            }
        }

        companion object {
            // Country name → flag emoji mapping for common teams
            private val COUNTRY_FLAGS = mapOf(
                "new zealand" to "\uD83C\uDDF3\uD83C\uDDFF", // 🇳🇿
                "egypt" to "\uD83C\uDDEA\uD83C\uDDEC",       // 🇪🇬
                "uruguay" to "\uD83C\uDDFA\uD83C\uDDFE",     // 🇺🇾
                "cabo verde" to "\uD83C\uDDE8\uD83C\uDDFB",  // 🇨🇻
                "cape verde" to "\uD83C\uDDE8\uD83C\uDDFB",
                "belgium" to "\uD83C\uDDE7\uD83C\uDDEA",     // 🇧🇪
                "iran" to "\uD83C\uDDEE\uD83C\uDDF7",        // 🇮🇷
                "brazil" to "\uD83C\uDDE7\uD83C\uDDF7",      // 🇧🇷
                "germany" to "\uD83C\uDDE9\uD83C\uDDEA",     // 🇩🇪
                "france" to "\uD83C\uDDEB\uD83C\uDDF7",      // 🇫🇷
                "spain" to "\uD83C\uDDEA\uD83C\uDDF8",       // 🇪🇸
                "argentina" to "\uD83C\uDDE6\uD83C\uDDF7",   // 🇦🇷
                "england" to "\uD83C\uDDEC\uD83C\uDDE7",     // 🇬🇧
                "india" to "\uD83C\uDDEE\uD83C\uDDF3",       // 🇮🇳
                "pakistan" to "\uD83C\uDDF5\uD83C\uDDF0",    // 🇵🇰
                "australia" to "\uD83C\uDDE6\uD83C\uDDFA",   // 🇦🇺
                "sri lanka" to "\uD83C\uDDF1\uD83C\uDDF0",  // 🇱🇰
                "bangladesh" to "\uD83C\uDDE7\uD83C\uDDE9",  // 🇧🇩
                "south africa" to "\uD83C\uDDFF\uD83C\uDDE6", // 🇿🇦
                "west indies" to "\uD83C\uDDFC\uD83C\uDDEB",  // 🇼🇮
                "nepal" to "\uD83C\uDDF3\uD83C\uDDF5",        // 🇳🇵
                "mexico" to "\uD83C\uDDF2\uD83C\uDDFD",       // 🇲🇽
                "italy" to "\uD83C\uDDEE\uD83C\uDDF9",        // 🇮🇹
                "netherlands" to "\uD83C\uDDF3\uD83C\uDDF1",  // 🇳🇱
                "portugal" to "\uD83C\uDDF5\uD83C\uDDF9",     // 🇵🇹
                "japan" to "\uD83C\uDDEF\uD83C\uDDF5",        // 🇯🇵
                "south korea" to "\uD83C\uDDF0\uD83C\uDDF7",  // 🇰🇷
                "china" to "\uD83C\uDDE8\uD83C\uDDF3",        // 🇨🇳
                "usa" to "\uD83C\uDDFA\uD83C\uDDF8",          // 🇺🇸
                "united states" to "\uD83C\uDDFA\uD83C\uDDF8",
                "canada" to "\uD83C\uDDE8\uD83C\uDDE6",       // 🇨🇦
                "turkey" to "\uD83C\uDDF9\uD83C\uDDF7",       // 🇹🇷
                "saudi arabia" to "\uD83C\uDDF8\uD83C\uDDE6", // 🇸🇦
                "uae" to "\uD83C\uDDE6\uD83C\uDDEA",          // 🇦🇪
                "qatar" to "\uD83C\uDDF6\uD83C\uDDE6",        // 🇶🇦
            )

            private val TIME_FORMATS = listOf(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US),
                SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.US),
            )

            fun countryFlagFor(teamName: String): String {
                val lower = teamName.lowercase().trim()
                return COUNTRY_FLAGS[lower] ?: ""
            }

            fun parseEventTimeMs(event: Event): Long? {
                val date = event.date.takeIf { it.isNotBlank() } ?: return null
                val time = event.time.takeIf { it.isNotBlank() } ?: return null
                val combined = "$date $time"
                for (fmt in TIME_FORMATS) {
                    try {
                        return fmt.parse(combined)?.time
                    } catch (_: Exception) { }
                }
                return null
            }

            fun formatCountdown(millis: Long): String {
                val totalSec = millis / 1000
                val hours = totalSec / 3600
                val minutes = (totalSec % 3600) / 60
                val seconds = totalSec % 60
                return if (hours > 0) {
                    String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format(Locale.US, "%02d:%02d", minutes, seconds)
                }
            }
        }
    }

    private object EventDiff : DiffUtil.ItemCallback<Event>() {
        override fun areItemsTheSame(a: Event, b: Event): Boolean = a.id == b.id
        override fun areContentsTheSame(a: Event, b: Event): Boolean = a == b
    }
}
