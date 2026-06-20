package com.streamify.app.ui.gestures

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Phase 4 · Step 4.3 — Single-touch dispatcher for the player's gesture
 * overlay (horizontal seek + left brightness + right volume + single-tap
 * toggle + double-tap skip).
 *
 * Design (settled after Thinker round):
 *  - Attached as `binding.playerView.setOnTouchListener(detector::onTouch)`.
 *    Returning `true` on ACTION_DOWN claims the gesture stream for the
 *    touch lifetime so the SeekBar / MaterialButtons elsewhere in the
 *    chrome don't fight us for MOVE / UP.
 *  - Gesture class is **locked after first significant movement** (past
 *    `ViewConfiguration.scaledTouchSlop`). Subsequent moves stay in the
 *    same class even if the dx/dy ratio wobbles.
 *  - Brightness is **per-window** via
 *    `WindowManager.LayoutParams.screenBrightness` — no WRITE_SETTINGS
 *    permission, no system-wide side effect on other apps.
 *  - Volume uses `AudioManager.setStreamVolume(STREAM_MUSIC)` to mirror
 *    the Step 4.2 volume slider's source of truth.
 *  - Seek commits on UP only — Approach A from the design (avoids
 *    MediaCodec underrun on sparse-frame streams).
 *  - Cross-phase couplings:
 *      - [isLocked] — Step 4.4 disables gestures when controls are locked.
 *      - [isInPip]  — Step 4.2 in PiP; chrome already hidden but we
 *        short-circuit defensively so a stray tap can't fire a toggle.
 */
class SwipeGestureDetector(
    private val context: Context,
    private val activity: Activity,
    private val exoPlayerProvider: () -> ExoPlayer?
) {

    enum class Type { NONE, HORIZONTAL_SEEK, VERTICAL_BRIGHTNESS, VERTICAL_VOLUME }

    // ---------- Caller-supplied callbacks ----------
    var onIndicatorVisibilityChanged: ((visible: Boolean) -> Unit)? = null
    var onIndicatorUpdate: ((type: Type, progress: Float, label: String) -> Unit)? = null
    var onSeekPreview: ((positionMs: Long) -> Unit)? = null
    var onSeekFinalize: ((positionMs: Long) -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: ((isLeft: Boolean) -> Unit)? = null
    var isLocked: () -> Boolean = { false }
    var isInPip: () -> Boolean = { false }

    // ---------- Internal state ----------
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var gestureType: Type = Type.NONE
    private var initialX: Float = 0f
    private var initialY: Float = 0f
    private var initialBrightness: Float = 0.5f
    private var lastAppliedBrightness: Float = -1f  // -1 = first apply; see applyBrightness
    private var initialVolumeSteps: Int = 0
    private var maxVolumeSteps: Int = 0
    private var initialPositionMs: Long = 0L
    private var durationMs: Long = 0L
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    private var lastVolumeUpdateSteps: Int = -1

    // MAJOR #1 / volume — cached reference to AudioManager so we can restore STREAM_MUSIC
    // volume on ACTION_UP / ACTION_CANCEL. Without this the swipe end-value leaks into the
    // next media app on the device. (Brightness doesn't need explicit restoration; per-window
    // LayoutParams.screenBrightness dies naturally with the activity.)
    private var audioManager: AudioManager? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true  // claim event stream
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked() || isInPip()) return false
                onSingleTap?.invoke()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked() || isInPip()) return false
                val w = viewWidth.toFloat().takeIf { it > 0f } ?: return false
                onDoubleTap?.invoke(e.x < w / 2f)
                return true
            }
        }
    )

    /** The View listener. Returns true on ACTION_DOWN to claim the stream. */
    fun onTouch(view: View, event: MotionEvent): Boolean {
        if (isLocked() || isInPip()) return false

        // Feed the system GestureDetector first (single + double tap).
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                viewWidth = view.width
                viewHeight = view.height
                initialX = event.x
                initialY = event.y
                gestureType = Type.NONE  // re-decide on first move
                // Reset IPC-gate sentinel so the first MOVE of a new brightness
                // gesture is never falsely gated against a stale prior value.
                lastAppliedBrightness = -1f

                // MAJOR #1 (revised pass-5/6) — capture pre-gesture brightness as the math
                // baseline. We do NOT restore on UP/CANCEL because per-window
                // screenBrightness has no cross-activity leak — it dies with the window —
                // and restoring would snap the user-set value back to original (bad UX).
                val lp = activity.window.attributes
                initialBrightness = if (lp.screenBrightness in 0.05f..1.0f) lp.screenBrightness else 0.5f
                // Sentinel -1f stays — guarantees the first apply commits (pass-3 fix).

                // MAJOR #1 — capture STREAM_MUSIC volume at gesture DOWN so we can restore
                // it on ACTION_UP / ACTION_CANCEL. Without this the swipe end-value leaks
                // into the next media app on the device.
                audioManager = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?)
                maxVolumeSteps = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
                initialVolumeSteps = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                lastVolumeUpdateSteps = initialVolumeSteps

                val p = exoPlayerProvider()
                initialPositionMs = p?.currentPosition ?: 0L
                durationMs = p?.duration?.takeIf { it > 0L } ?: 0L
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - initialX
                val dy = event.y - initialY

                if (gestureType == Type.NONE &&
                    (abs(dx) > touchSlop || abs(dy) > touchSlop)
                ) {
                    gestureType = classifyFirstMove(viewWidth, dx, dy)
                    if (gestureType != Type.NONE) {
                        onIndicatorVisibilityChanged?.invoke(true)
                    }
                }
                when (gestureType) {
                    Type.HORIZONTAL_SEEK -> applySeek(viewWidth, dx)
                    Type.VERTICAL_BRIGHTNESS -> applyBrightness(viewHeight, dy)
                    Type.VERTICAL_VOLUME -> applyVolume(dy)
                    Type.NONE -> Unit
                }
            }

            MotionEvent.ACTION_UP -> {
                when (gestureType) {
                    Type.HORIZONTAL_SEEK -> {
                        if (durationMs > 0L && viewWidth > 0) {
                            val dx = event.x - initialX
                            val delta = dx / viewWidth.toFloat()
                            val newPos = (initialPositionMs + delta * durationMs)
                                .toLong().coerceIn(0L, durationMs)
                            onSeekFinalize?.invoke(newPos)
                        }
                    }
                    Type.VERTICAL_BRIGHTNESS -> {
                        // Explicitly hide the indicator so brightness gestures
                        // don't rely solely on the MOVE-timer chain.
                        onIndicatorVisibilityChanged?.invoke(false)
                    }
                    Type.VERTICAL_VOLUME -> {
                        // Explicitly hide the indicator (consistency with brightness).
                        onIndicatorVisibilityChanged?.invoke(false)
                        // MAJOR #1 — restore the pre-gesture STREAM_MUSIC volume so it
                        // doesn't leak into the next media app. Brightness is NOT restored
                        // here: per-window LayoutParams.screenBrightness dies naturally
                        // with the activity, and reverting on UP would snap the user's
                        // chosen level back to original (YouTube / Netflix / VLC all
                        // persist gesture state). GUARDED to avoid a no-op IPC write on
                        // seek-only / tap-only UP.
                        audioManager?.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            initialVolumeSteps,
                            0
                        )
                    }
                    else -> Unit
                }
                gestureType = Type.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                // Hide the indicator card so a system-interrupted touch (incoming call,
                // rotation mid-gesture) doesn't leave the overlay stranded on the surface.
                onIndicatorVisibilityChanged?.invoke(false)
                // MINOR #3 — only restore volume when we actually moved it; tap-only or
                // undetermined touches invoked no setStreamVolume so this would be a no-op
                // IPC write.
                if (gestureType == Type.VERTICAL_VOLUME) {
                    audioManager?.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        initialVolumeSteps,
                        0
                    )
                }
                gestureType = Type.NONE
            }
        }
        return true
    }

    /**
     * Pick the gesture class on the first significant movement. Locks the
     * class for the rest of the touch stream so a small wobble in dx vs
     * dy can't re-classify mid-gesture.
     */
    private fun classifyFirstMove(width: Int, dx: Float, dy: Float): Type {
        if (width <= 0 || durationMs <= 0L) {
            // No usable stream metadata — fall back to brightness/volume by x-zone.
            return if (initialX < width / 3f) Type.VERTICAL_BRIGHTNESS else Type.VERTICAL_VOLUME
        }
        return when {
            abs(dx) > abs(dy) * 1.5f -> Type.HORIZONTAL_SEEK
            initialX < width / 3f -> Type.VERTICAL_BRIGHTNESS
            initialX > (width * 2f) / 3f -> Type.VERTICAL_VOLUME
            // Middle column + vertical motion still dominant: split by sign.
            dy < 0f -> Type.VERTICAL_BRIGHTNESS  // swipe up = brighter
            else -> Type.VERTICAL_VOLUME        // swipe down = quieter
        }
    }

    private fun applySeek(width: Int, dx: Float) {
        if (width <= 0 || durationMs <= 0L) return
        val delta = dx / width.toFloat()
        val newPos = (initialPositionMs + delta * durationMs).toLong().coerceIn(0L, durationMs)
        onSeekPreview?.invoke(newPos)
        onIndicatorUpdate?.invoke(
            Type.HORIZONTAL_SEEK,
            (newPos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
            formatSeekLabel(initialPositionMs, newPos)
        )
    }

    private fun applyBrightness(height: Int, dy: Float) {
        if (height <= 0) return
        val fraction = -dy / height.toFloat()
        val newBright = (initialBrightness + fraction).coerceIn(0.05f, 1.0f)
        // IPC-gate: a fast vertical swipe can fire 60+ MOVE events/sec; only
        // re-write the WindowManager.LayoutParams when the value crosses a
        // 1% threshold (matching the visible progress-bar precision).
        if (kotlin.math.abs(newBright - lastAppliedBrightness) < 0.01f) return
        lastAppliedBrightness = newBright
        val lp = activity.window.attributes
        lp.screenBrightness = newBright
        activity.window.attributes = lp
        onIndicatorUpdate?.invoke(
            Type.VERTICAL_BRIGHTNESS,
            newBright,  // 0.05..1.0 maps directly to progress
            "Brightness \u00b7 ${(newBright * 100).toInt()}%"
        )
    }

    private fun applyVolume(dy: Float) {
        if (maxVolumeSteps <= 0) return
        val height = viewHeight.toFloat().takeIf { it > 0f } ?: return
        val fraction = -dy / height
        val newSteps = (initialVolumeSteps + fraction * maxVolumeSteps)
            .toInt().coerceIn(0, maxVolumeSteps)
        if (newSteps == lastVolumeUpdateSteps) return
        lastVolumeUpdateSteps = newSteps
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, newSteps, 0)
        onIndicatorUpdate?.invoke(
            Type.VERTICAL_VOLUME,
            newSteps.toFloat() / maxVolumeSteps.toFloat(),
            "Volume \u00b7 ${(newSteps * 100 / maxVolumeSteps)}%"
        )
    }

    /** "+00:42" / "-01:35" style delta label + final timestamp. */
    private fun formatSeekLabel(initialMs: Long, targetMs: Long): String {
        val deltaMs = targetMs - initialMs
        val sign = if (deltaMs >= 0) "+" else "-"
        val absDelta = if (deltaMs >= 0) deltaMs else -deltaMs
        val totalSec = absDelta / 1000L
        val mm = totalSec / 60L
        val ss = totalSec % 60L
        val deltaStr = "%02d:%02d".format(mm, ss)
        val targetStr = formatTimestamp(targetMs)
        return "$targetStr  $sign$deltaStr"
    }

    private fun formatTimestamp(ms: Long): String {
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
