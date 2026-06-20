package com.streamify.app.ui.util

import android.content.Context
import android.content.Intent
import com.streamify.app.ui.activities.PlayerActivity

/**
 * Phase 4 · Step 4.2 — unified Player navigation entry point.
 *
 * All three entry surfaces (Home Event row, Categories channel card,
 * Highlights thumbnail card) reach ExoPlayer through this object so
 * the Fragment click handlers stay a single line each.
 *
 * Three modes map to [PlayerActivity]'s Intent contract:
 *
 *   - [startPlayerForChannel] — PlayerViewModel resolves the Channel
 *     asynchronously from /api/channels; the links bar is then populated
 *     from the resolved `Channel.streamUrl`.
 *
 *   - [startPlayerForVideo] — direct URL playback of an already-resolved
 *     stream (used by Highlights rows where the `videoUrl` is known
 *     up-front). Bypasses the channel lookup.
 *
 *   - [startPlayerForEvent] — Phase 4.x future hook. The Activity already
 *     distinguishes this branch (it shows a "Loading stream…" message at
 *     launch until a future commit wires `Event -> first Channel`).
 *
 * All paths attach [Intent.FLAG_ACTIVITY_NEW_TASK] so callers from a
 * plain `Context` (rather than an `Activity`) compose safely.
 */
object PlayerNavigation {

    fun startPlayerForChannel(
        context: Context,
        channelId: String,
        fallbackTitle: String? = null
    ) {
        if (channelId.isBlank()) return
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channelId)
            .apply {
                if (!fallbackTitle.isNullOrBlank()) {
                    putExtra(PlayerActivity.EXTRA_TITLE, fallbackTitle)
                }
            }
        context.startActivity(intent)
    }

    fun startPlayerForVideo(
        context: Context,
        videoUrl: String,
        title: String
    ) {
        if (videoUrl.isBlank()) return
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            .putExtra(PlayerActivity.EXTRA_TITLE, title)
        context.startActivity(intent)
    }

    fun startPlayerForEvent(
        context: Context,
        eventId: String,
        title: String
    ) {
        if (eventId.isBlank()) return
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_EVENT_ID, eventId)
            .putExtra(PlayerActivity.EXTRA_TITLE, title)
        context.startActivity(intent)
    }
}
