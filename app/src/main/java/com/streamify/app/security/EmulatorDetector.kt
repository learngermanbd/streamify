package com.streamify.app.security

import android.os.Build
import android.util.Log
import java.io.File

/**
 * Phase 7 · Step 7.6 — Kotlin-side emulator detection.
 *
 * Complements [NativeSecurityManager.checkEnvironment] with
 * Kotlin-level checks that inspect Build properties, hardware
 * characteristics, and emulator-specific files.
 *
 * Emulators are used for automated attacks, APK reverse
 * engineering, and bypassing device-level security controls.
 * Detecting them allows the app to reduce functionality or
 * increase monitoring for suspicious behavior.
 */
object EmulatorDetector {

    private const val TAG = "EmulatorDetector"

    /** Known emulator identifiers in Build properties. */
    private val EMULATOR_FINGERPRINTS = listOf(
        "generic", "unknown", "emulator", "sdk", "google_sdk",
        "vbox86p", "nox", "ttVM_Hdragon", "droid4x",
        "Andy", "CWSimulator", "sdk_gphone",
    )

    private val EMULATOR_MODELS = listOf(
        "Emulator", "Android SDK built for x86",
        "Android SDK built for x86_64", "google_sdk",
        "droid4x", "nox", "sdk", "vbox86",
    )

    private val EMULATOR_MANUFACTURERS = listOf(
        "Genymotion", "unknown", "Google", "Andy",
    )

    private val EMULATOR_BRANDS = listOf(
        "generic", "android", "generic_x86", "generic_x86_64",
    )

    private val EMULATOR_DEVICES = listOf(
        "generic", "vbox86p", "generic_x86", "generic_x86_64",
        "nox", "droid4x", "ttVM_Hdragon",
    )

    private val EMULATOR_HARDWARE = listOf(
        "goldfish", "ranchu", "vbox86", "nox", "droid4x",
        "ttVM_Hdragon", "generic_x86", "generic_x86_64",
    )

    private val EMULATOR_PRODUCTS = listOf(
        "sdk", "google_sdk", "sdk_x86", "sdk_x86_64",
        "vbox86p", "nox", "droid4x",
    )

    private val EMULATOR_FILES = listOf(
        "/dev/socket/qemud",
        "/dev/qemu_pipe",
        "/dev/goldfish_pipe",
        "/system/lib/libc_malloc_debug_qemu.so",
        "/sys/qemu_trace",
        "/system/lib64/libhoudini.so",   // Intel ARM translation
        "/dev/socket/genyd",              // Genymotion
        "/dev/socket/baseband_genyd",     // Genymotion
    )

    /**
     * Run all emulator detection checks.  Returns a
     * [DetectionResult] with a score (higher = more likely emulator).
     */
    fun detect(): DetectionResult {
        var score = 0
        val indicators = mutableListOf<String>()

        // Check Build properties (each match adds to the score)
        if (matchesAny(Build.FINGERPRINT, EMULATOR_FINGERPRINTS)) {
            score += 2
            indicators.add("FINGERPRINT=${Build.FINGERPRINT}")
        }
        if (matchesAny(Build.MODEL, EMULATOR_MODELS)) {
            score += 2
            indicators.add("MODEL=${Build.MODEL}")
        }
        if (matchesAny(Build.MANUFACTURER, EMULATOR_MANUFACTURERS)) {
            score += 2
            indicators.add("MANUFACTURER=${Build.MANUFACTURER}")
        }
        if (matchesAny(Build.BRAND, EMULATOR_BRANDS)) {
            score += 1
            indicators.add("BRAND=${Build.BRAND}")
        }
        if (matchesAny(Build.DEVICE, EMULATOR_DEVICES)) {
            score += 2
            indicators.add("DEVICE=${Build.DEVICE}")
        }
        if (matchesAny(Build.HARDWARE, EMULATOR_HARDWARE)) {
            score += 2
            indicators.add("HARDWARE=${Build.HARDWARE}")
        }
        if (matchesAny(Build.PRODUCT, EMULATOR_PRODUCTS)) {
            score += 1
            indicators.add("PRODUCT=${Build.PRODUCT}")
        }

        // Check for emulator-specific files
        for (path in EMULATOR_FILES) {
            if (File(path).exists()) {
                score += 3
                indicators.add("FILE=$path")
                break // One file match is strong evidence
            }
        }

        // Check for test-keys (also used by emulators)
        if (Build.TAGS?.contains("test-keys") == true) {
            score += 1
            indicators.add("TAGS=${Build.TAGS}")
        }

        // Threshold: score >= 4 means likely emulator
        val isEmulator = score >= 4

        if (isEmulator) {
            Log.w(TAG, "Emulator detected (score=$score): $indicators")
        }

        return DetectionResult(
            isDetected = isEmulator,
            score = score,
            indicators = indicators
        )
    }

    /**
     * Quick check: is this device likely an emulator?
     * Uses a simpler heuristic for fast boot-time checks.
     */
    fun isLikelyEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.contains("emulator")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || File("/dev/qemu_pipe").exists()
                || File("/dev/socket/qemud").exists()
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun matchesAny(value: String?, patterns: List<String>): Boolean {
        if (value.isNullOrBlank()) return false
        val lower = value.lowercase()
        return patterns.any { lower.contains(it.lowercase()) }
    }
}

/**
 * Result of emulator detection with a score-based approach.
 * Higher [score] = more likely to be an emulator.
 */
data class DetectionResult(
    val isDetected: Boolean,
    val score: Int,
    val indicators: List<String>
)
