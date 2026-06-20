package com.streamify.app.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.system.exitProcess

/**
 * Phase 7 · Step 7.5 — Gradual degradation response to detected
 * tampering.
 *
 * Instead of immediately crashing (which tips off the attacker that
 * their tamper was detected), this class implements a **gradual
 * degradation** strategy:
 *
 *  1. **Silent logging** — report to Sentry (if available) so the
 *     team can track tampered installs.
 *  2. **Feature degradation** — progressively disable high-value
 *     features (streaming, favorites, push) via feature flags.
 *  3. **Deceptive errors** — show generic network/server errors
 *     instead of "tamper detected" messages.
 *  4. **Final exit** — after a random delay (30-120 seconds), show
 *     a "server maintenance" dialog and exit.
 *
 * The delay makes it harder for the attacker to correlate the exit
 * with their specific tampering action, because the app appeared to
 * work normally for a while before failing.
 *
 * ## Usage
 * ```kotlin
 * // In StreamifyApp.onCreate, after integrity checks:
 * val result = IntegrityChecker.check(context)
 * if (result.isTamper) {
 *     SelfHealing.onTamperDetected(context, result)
 * }
 * ```
 */
object SelfHealing {

    private const val TAG = "SelfHealing"

    /**
     * Degradation levels.  Each level disables more features.
     * The attacker sees progressively worse behavior rather than
     * an immediate crash.
     */
    enum class DegradationLevel {
        /** No degradation — app works normally. */
        NONE,
        /** Level 1: silent logging + subtle quality reduction. */
        LOGGING,
        /** Level 2: disable high-value features. */
        FEATURES_DISABLED,
        /** Level 3: show deceptive errors + exit after delay. */
        FINAL
    }

    @Volatile
    var currentLevel: DegradationLevel = DegradationLevel.NONE
        private set

    @Volatile
    var isTampered: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Called when tampering is detected.  Initiates the gradual
     * degradation sequence.  Safe to call multiple times (idempotent
     * per level).
     */
    fun onTamperDetected(context: Context, result: TamperResult) {
        if (isTampered) return // Already handling
        isTampered = true

        Log.w(TAG, "Tamper detected: $result")

        // Level 1: Silent logging
        escalateTo(DegradationLevel.LOGGING, context, result)

        // Level 2: Feature degradation after a short delay
        handler.postDelayed({
            escalateTo(DegradationLevel.FEATURES_DISABLED, context, result)
        }, 5_000L)

        // Level 3: Final exit after a random delay (30-120 seconds)
        val exitDelay = 30_000L + (Math.random() * 90_000L).toLong()
        handler.postDelayed({
            escalateTo(DegradationLevel.FINAL, context, result)
            // Show a deceptive "server maintenance" message and exit.
            // We use a simple toast instead of a dialog to avoid needing
            // an Activity reference.
            android.widget.Toast.makeText(
                context,
                "Server maintenance in progress. Please try again later.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            handler.postDelayed({ exitProcess(0) }, 3_000L)
        }, exitDelay)
    }

    /**
     * Check if a specific feature should be disabled due to
     * tamper detection.
     *
     * Call this from UI code before enabling high-value features:
     * ```kotlin
     * if (SelfHealing.isFeatureEnabled("premium_streaming")) {
     *     // enable premium streaming
     * }
     * ```
     */
    fun isFeatureEnabled(feature: String): Boolean {
        if (!isTampered) return true

        return when (currentLevel) {
            DegradationLevel.NONE -> true
            DegradationLevel.LOGGING -> true // Subtle — everything still works
            DegradationLevel.FEATURES_DISABLED -> {
                // Disable high-value features
                feature !in DEGRADED_FEATURES
            }
            DegradationLevel.FINAL -> false // Everything is broken
        }
    }

    /**
     * Check if network requests should be degraded (artificially
     * slowed down or returning errors).
     */
    fun shouldDegradeNetwork(): Boolean {
        return currentLevel >= DegradationLevel.FEATURES_DISABLED
    }

    /**
     * Get a deceptive error message for the given context.
     * Never reveals that tampering was detected.
     */
    fun getDeceptiveErrorMessage(): String {
        return DECEPTIVE_ERRORS.random()
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun escalateTo(
        level: DegradationLevel,
        context: Context,
        result: TamperResult
    ) {
        if (level.ordinal <= currentLevel.ordinal) return
        currentLevel = level

        Log.w(TAG, "Escalated to degradation level: $level (cause: $result)")

        // Report to Sentry (if available)
        reportToSentry(context, level, result)
    }

    private fun reportToSentry(
        context: Context,
        level: DegradationLevel,
        result: TamperResult
    ) {
        try {
            val sentryLevel = io.sentry.SentryLevel.WARNING
            val event = io.sentry.SentryEvent().apply {
                message = io.sentry.protocol.Message().apply {
                    message = "APK tamper detected — escalation to ${level.name}"
                }
                this.level = sentryLevel
                setTag("tamper_type", result::class.simpleName ?: "unknown")
                setTag("degradation_level", level.name)
                setExtra("tamper_detail", result.toString())
            }
            io.sentry.Sentry.captureEvent(event)
        } catch (e: Exception) {
            // Sentry might not be initialized in debug builds.
            Log.d(TAG, "Sentry report failed (expected in debug): ${e.message}")
        }
    }

    /** Features disabled at [DegradationLevel.FEATURES_DISABLED]. */
    private val DEGRADED_FEATURES = setOf(
        "premium_streaming",
        "favorites",
        "playlists",
        "push_notifications",
        "picture_in_picture",
        "subtitle_selection",
        "quality_selection",
    )

    /** Deceptive error messages — never reveal the real cause. */
    private val DECEPTIVE_ERRORS = listOf(
        "Unable to connect to server. Please try again later.",
        "Stream temporarily unavailable. Please try again.",
        "Network error. Please check your connection.",
        "Server is under maintenance. Please try again later.",
        "Content is currently unavailable in your region.",
    )
}
