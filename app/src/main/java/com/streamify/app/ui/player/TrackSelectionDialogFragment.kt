package com.streamify.app.ui.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.streamify.app.R
import com.streamify.app.data.prefs.PlayerPrefs
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Step 4.5 — TrackSelectionDialogFragment.
 *
 * Hosts a [BottomSheetDialog] that lists the *subtitle* tracks currently
 * present in [ExoPlayer.getCurrentTracks] and lets the user pick one
 * (or "Off"). On radio change, applies a [TrackSelectionOverride] to
 * [ExoPlayer.trackSelectionParameters] and persists the choice to
 * [PlayerPrefs] for global restore-on-launch.
 *
 * Video + audio track groups are intentionally NOT surfaced here in v1:
 *  - Video quality is handled by [VideoQualityManager] (per-Quality enum).
 *  - Audio track switching is rare on the channels this player serves;
 *    [PlayerActivity] reads the master track count and skips the audio
 *    picker entirely if there's nothing multi-track.
 *
 * Construct via the [show] helper so the activity-side state plumbing
 * stays out of the Fragment's API.
 */
class TrackSelectionDialogFragment : BottomSheetDialogFragment() {

    private var exoPlayerRef: ExoPlayer? = null
    private var prefsRef: PlayerPrefs? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.layout_track_selection_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val exoPlayer = exoPlayerRef
        if (exoPlayer == null) {
            dismissAllowingStateLoss()
            return
        }
        val prefs = prefsRef

        val empty = view.findViewById<android.widget.TextView>(R.id.trackSelectionEmpty)
        val group = view.findViewById<RadioGroup>(R.id.trackSelectionSubtitleGroup)
        val close = view.findViewById<View>(R.id.trackSelectionCloseBtn)
        val done = view.findViewById<View>(R.id.trackSelectionDoneBtn)

        close.setOnClickListener { dismissAllowingStateLoss() }
        done.setOnClickListener { dismissAllowingStateLoss() }

        // Populate the Subtitle radio group. Auto-detect current selection.
        val labels = collectSubtitleTracks(exoPlayer)
        if (labels.isEmpty()) {
            empty.visibility = View.VISIBLE
            group.visibility = View.GONE
        } else {
            empty.visibility = View.GONE
            group.visibility = View.VISIBLE
            val currentId = getCurrentSubtitleTrackId(exoPlayer)
            labels.forEachIndexed { idx, label ->
                val rb = RadioButton(requireContext()).apply {
                    id = View.generateViewId()
                    text = label.displayName
                    isChecked = (currentId == label.stableId) || (idx == 0 && currentId.isEmpty() && label.displayName == requireContext().getString(R.string.player_track_off))
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text))
                }
                rb.tag = label.stableId
                group.addView(rb)
            }
            group.setOnCheckedChangeListener { _, checkedId ->
                val rb = view.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
                val pickedId = rb.tag as? String ?: return@setOnCheckedChangeListener
                applySubtitleChoice(exoPlayer, pickedId, labels)
                prefs?.let {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        it.setSubtitleTrackId(pickedId)
                    }
                }
            }
        }
    }

    private fun applySubtitleChoice(
        exoPlayer: ExoPlayer,
        pickedId: String,
        labels: List<SubtitleLabel>
    ) {
        val params: TrackSelectionParameters = exoPlayer.trackSelectionParameters
        val builder = params.buildUpon()
        val chosenLabel = labels.firstOrNull { it.stableId == pickedId }
        when {
            pickedId == OFF_STABLE_ID || chosenLabel == null -> {
                // "Off" — disable text tracks entirely.
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            }
            else -> {
                // chosenLabel != null && group != null (group is nullable only for OFF sentinel)
                val grp = chosenLabel.group ?: run {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    exoPlayer.trackSelectionParameters = builder.build()
                    return
                }
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(
                        TrackSelectionOverride(grp, chosenLabel.trackIndex)
                    )
            }
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    private fun getCurrentSubtitleTrackId(exoPlayer: ExoPlayer): String {
        val params = exoPlayer.trackSelectionParameters
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return OFF_STABLE_ID
        val textOverride = params.overrides
            .values
            .firstOrNull { it.mediaTrackGroup.type == C.TRACK_TYPE_TEXT && !params.disabledTrackTypes.contains(it.mediaTrackGroup.type) }
            ?: return ""
        val mediaTrackGroup = textOverride.mediaTrackGroup
        val idx = if (textOverride.trackIndices.isNotEmpty()) textOverride.trackIndices.firstOrNull() ?: 0 else 0
        return stableIdFor(mediaTrackGroup, idx)
    }

    private fun collectSubtitleTracks(exoPlayer: ExoPlayer): List<SubtitleLabel> {
        val labels = mutableListOf<SubtitleLabel>()
        // Always include "Off" as the first choice (group=null sentinel;
        // applySubtitleChoice guards against null when reading it).
        labels += SubtitleLabel(
            group = null,
            trackIndex = -1,
            stableId = OFF_STABLE_ID,
            displayName = getString(R.string.player_track_off)
        )
        val groups = exoPlayer.currentTracks.groups
        for (tg in groups) {
            if (tg.type != C.TRACK_TYPE_TEXT) continue
            if (!tg.isSupported) continue
            val mediaTrackGroup = tg.mediaTrackGroup
            for (i in 0 until mediaTrackGroup.length) {
                val format = mediaTrackGroup.getFormat(i)
                val lang = format.language?.takeIf { it.isNotBlank() } ?: "?"
                val codec = when (format.sampleMimeType) {
                    "application/x-subrip", "text/x-subrip" -> "SRT"
                    "text/vtt", "text/x-vtt" -> "VTT"
                    "application/ttml+xml", "application/x-ttml" -> "TTML"
                    "text/x-ssa", "text/x-ass" -> "SSA"
                    else -> (format.sampleMimeType?.substringAfter('/') ?: "TEXT").uppercase()
                }
                val display = "$codec · $lang"
                labels += SubtitleLabel(
                    group = mediaTrackGroup,
                    trackIndex = i,
                    stableId = stableIdFor(mediaTrackGroup, i),
                    displayName = display
                )
            }
        }
        return labels
    }

    private fun stableIdFor(group: TrackGroup, index: Int): String {
        // Stable per-session hash. DataStore is global so a different video's
        // index 0 might collide; the OFF_STABLE_ID sentinel avoids it.
        return "${group.hashCode()}_$index"
    }

    private data class SubtitleLabel(
        val group: TrackGroup?,  // null only for the OFF sentinel
        val trackIndex: Int,
        val stableId: String,
        val displayName: String
    )

    companion object {
        private const val OFF_STABLE_ID = "OFF"
        private const val FRAGMENT_TAG = "TrackSelectionDialogFragment"

        /**
         * Show the dialog. The [exoPlayer] is held by reference; it MUST outlive the dialog
         * (PlayerActivity's lifecycle guarantees that since the dialog is hosted in the
         * activity's FragmentManager).
         */
        fun show(
            fm: FragmentManager,
            exoPlayer: ExoPlayer,
            prefs: PlayerPrefs
        ): TrackSelectionDialogFragment {
            // Bail if the player has nothing the dialog can act on.
            if (!exoPlayerHasTextTracks(exoPlayer)) {
                return TrackSelectionDialogFragment().also { /* hide via caller */ }
            }
            val frag = TrackSelectionDialogFragment().apply {
                exoPlayerRef = exoPlayer
                prefsRef = prefs
            }
            frag.show(fm, FRAGMENT_TAG)
            return frag
        }

        /** Predicate to skip the dialog itself when there are no text tracks. */
        fun exoPlayerHasTextTracks(exoPlayer: ExoPlayer): Boolean {
            return exoPlayer.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        }
    }
}
