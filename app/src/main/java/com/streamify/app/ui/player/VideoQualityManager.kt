package com.streamify.app.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import com.streamify.app.data.prefs.VideoQualityMode
import androidx.media3.common.Tracks

/**
 * Step 4.5 — apply a [VideoQualityMode] to ExoPlayer using Media3 1.5.x.
 *
 * Strategy:
 *  - **AUTO**: clear any override we previously set and let Media3's adaptive
 *    selection pick the best fit (the default).
 *  - **FHD/HD/SD/LD**: pick the highest-resolution video track whose
 *    `height <= mode.maxHeightInclusive` and install a
 *    [TrackSelectionOverride] so ExoPlayer keeps that track.
 *
 * Why override-and-stamp instead of `setMaxVideoSize`:
 *  - `setMaxVideoSize` clamps adaptively each time Media3 picks a track, but
 *    the strict plan locks the exact height threshold per quality rung, so
 *    we use the explicit Override path so a user who picks "HD" stays on
 *    the 720p track even when a higher resolution becomes available.
 *
 * If no matching track exists in the current [Player.getCurrentTracks], the
 * call is a no-op; Media3's adaptive selection keeps choosing freely.
 */
object VideoQualityManager {

    /** Apply [mode] to [exoPlayer]. Idempotent: can be called from any lifecycle state. */
    fun apply(exoPlayer: ExoPlayer, mode: VideoQualityMode) {
        val currentTracks: Tracks = exoPlayer.currentTracks
        val params: TrackSelectionParameters = exoPlayer.trackSelectionParameters
        val newParams = if (mode == VideoQualityMode.AUTO) {
            // Strip any prior override we stamped so adaptive selection returns.
            params.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .build()
        } else {
            val videoGroup = pickBestVideoTrack(currentTracks, mode.maxHeightInclusive)
            if (videoGroup == null) {
                // No matching track — leave adaptive selection alone.
                params
            } else {
                params.buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(videoGroup.groupMediaTrackGroup, videoGroup.trackIndex)
                    )
                    .build()
            }
        }
        exoPlayer.trackSelectionParameters = newParams
    }

    /**
     * Find the *highest-resolution* video track whose format height is ≤ [maxHeightInclusive].
     * Returns null if the current tracks contain no video track or no track qualifies, in which
     * case the caller should fall back to adaptive selection.
     */
    private fun pickBestVideoTrack(tracks: Tracks, maxHeightInclusive: Int): VideoTrackPick? {
        var bestPick: VideoTrackPick? = null
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            if (!group.isSupported) continue
            val mediaTrackGroup = group.mediaTrackGroup
            for (i in 0 until mediaTrackGroup.length) {
                val format = mediaTrackGroup.getFormat(i)
                val h = format.height
                if (h <= 0 || h > maxHeightInclusive) continue
                if (bestPick == null || h > bestPick.height) {
                    bestPick = VideoTrackPick(mediaTrackGroup, i, h)
                }
            }
        }
        return bestPick
    }

    private data class VideoTrackPick(
        val groupMediaTrackGroup: androidx.media3.common.TrackGroup,
        val trackIndex: Int,
        val height: Int
    )
}
