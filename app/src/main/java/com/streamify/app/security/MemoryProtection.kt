package com.streamify.app.security

import android.app.Activity
import android.util.Log
import android.view.WindowManager

/**
 * Phase 7 · Step 7.8 — Memory protection best practices.
 *
 * Provides utilities for handling sensitive data in memory:
 *  - [FLAG_SECURE] to prevent screenshots and screen recording
 *  - Secure [CharArray] and [ByteArray] wiping
 *  - Timing-safe comparison to prevent timing attacks
 *
 * ## Usage
 * ```kotlin
 * // In an Activity that displays sensitive content (e.g., player, login):
 * MemoryProtection.applySecureFlags(window)
 *
 * // When done with sensitive data:
 * MemoryProtection.wipe(sensitiveCharArray)
 * MemoryProtection.wipe(sensitiveByteArray)
 *
 * // Timing-safe comparison for tokens:
 * if (MemoryProtection.constantTimeEquals(expected, actual)) { ... }
 * ```
 */
object MemoryProtection {

    private const val TAG = "MemoryProtection"

    /**
     * Apply [WindowManager.LayoutParams.FLAG_SECURE] to prevent
     * screenshots, screen recording, and task-switcher thumbnails.
     *
     * Call in `Activity.onCreate()` before `setContentView()`:
     * ```kotlin
     * MemoryProtection.applySecureFlags(window)
     * ```
     *
     * Also consider calling in `onResume()` if the flag might be
     * removed during the Activity lifecycle (e.g., PiP mode).
     */
    fun applySecureFlags(activity: Activity) {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        Log.d(TAG, "FLAG_SECURE applied to ${activity.javaClass.simpleName}")
    }

    /**
     * Remove [WindowManager.LayoutParams.FLAG_SECURE].
     * Use when entering PiP mode (where screenshots are allowed).
     */
    fun clearSecureFlags(activity: Activity) {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Log.d(TAG, "FLAG_SECURE cleared from ${activity.javaClass.simpleName}")
    }

    /**
     * Securely wipe a [CharArray] by overwriting all elements with
     * null characters.  Prevents the data from lingering in the JVM
     * heap after it's no longer needed.
     */
    fun wipe(array: CharArray) {
        array.fill('\u0000')
    }

    /**
     * Securely wipe a [ByteArray] by overwriting all elements with
     * zero.
     */
    fun wipe(array: ByteArray) {
        array.fill(0)
    }

    /**
     * Timing-safe comparison of two byte arrays.  Compares every
     * byte regardless of early mismatches to prevent timing attacks.
     *
     * @return `true` if the arrays have the same content.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }

    /**
     * Timing-safe comparison of two strings.  Converts to UTF-8
     * bytes and compares using [constantTimeEquals].
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        return constantTimeEquals(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Create a [CharArray] from a [String] and then wipe the
     * intermediate [String] from the intern pool (best effort).
     *
     * Note: we can't truly wipe a [String] from memory (it's
     * immutable), but converting to [CharArray] and wiping the
     * array when done is the standard Java/Kotlin best practice.
     */
    fun toSecureCharArray(value: String): CharArray {
        return value.toCharArray()
    }
}
