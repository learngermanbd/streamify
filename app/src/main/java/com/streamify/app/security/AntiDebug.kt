package com.streamify.app.security

import android.os.Debug
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.abs

/**
 * Phase 7 · Step 7.9 — Kotlin-side anti-debugging protection.
 *
 * Complements the native ptrace-based detection in [NativeSecurityManager]
 * with additional Kotlin-accessible checks that don't require JNI:
 *
 *  1. **isDebuggerConnected** — standard Android debug check.
 *  2. **waitingForDebugger** — detects the VM paused for attach.
 *  3. **TracerPid** — reads `/proc/self/status` to check if another
 *     process is tracing ours (non-zero TracerPid = debugger).
 *  4. **JDWP detection** — checks if the JDWP transport port is open
 *     by inspecting `/proc/net/tcp` for the JDWP port range.
 *  5. **Timing check** — measures execution time of a known code
 *     path; abnormally long times suggest breakpoints or single-stepping.
 *
 * ## Usage
 * ```kotlin
 * val result = AntiDebug.detect()
 * if (result.isDebugged) { /* respond */ }
 * ```
 *
 * Run periodically (every 3 seconds) on a background thread for
 * continuous monitoring.
 */
object AntiDebug {

    private const val TAG = "AntiDebug"

    /**
     * Timing threshold in nanoseconds (thread CPU time).
     * Uses [android.os.SystemClock.currentThreadTimeMillis] to
     * measure only CPU time spent on this thread, avoiding false
     * positives from OS scheduling on low-tier devices.
     * Normal `detect()` takes < 10ms CPU; > 300ms suggests breakpoints.
     */
    private const val TIMING_THRESHOLD_NS = 300_000_000L  // 300ms CPU time

    /** How many consecutive timing violations before flagging. */
    private const val TIMING_VIOLATION_THRESHOLD = 3

    @Volatile
    private var consecutiveTimingViolations = 0

    /**
     * Run all anti-debug checks and return a composite result.
     */
    fun detect(): DebugResult {
        val startCpuMs = android.os.SystemClock.currentThreadTimeMillis()
        val indicators = mutableListOf<String>()

        // ── Check 1: Android's built-in debugger flag ─────────────
        if (Debug.isDebuggerConnected()) {
            indicators.add("isDebuggerConnected")
        }

        // ── Check 2: Waiting for debugger ─────────────────────────
        if (Debug.waitingForDebugger()) {
            indicators.add("waitingForDebugger")
        }

        // ── Check 3: TracerPid in /proc/self/status ──────────────
        val tracerPid = readTracerPid()
        if (tracerPid > 0) {
            indicators.add("tracerPid=$tracerPid")
        }

        // ── Check 4: JDWP port detection ─────────────────────────
        if (isJdwpActive()) {
            indicators.add("jdwpActive")
        }

        // ── Check 5: Timing anomaly (CPU time, not wall clock) ───
        val elapsedCpuNs = (android.os.SystemClock.currentThreadTimeMillis() - startCpuMs) * 1_000_000L
        if (elapsedCpuNs > TIMING_THRESHOLD_NS) {
            consecutiveTimingViolations++
            if (consecutiveTimingViolations >= TIMING_VIOLATION_THRESHOLD) {
                indicators.add("timingAnomaly:${elapsedCpuNs / 1_000_000}ms")
            }
        } else {
            consecutiveTimingViolations = 0
        }

        return DebugResult(
            isDebugged = indicators.isNotEmpty(),
            indicators = indicators
        )
    }

    /**
     * Read the TracerPid from `/proc/self/status`.
     * Returns 0 if not being traced, or the PID of the tracing process.
     */
    private fun readTracerPid(): Int {
        return try {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }
                    ?.substringAfter("TracerPid:")
                    ?.trim()
                    ?.toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read TracerPid: ${e.message}")
            0
        }
    }

    /**
     * Check if the JDWP (Java Debug Wire Protocol) transport is active.
     *
     * JDWP typically opens a port in the range 8000–8999.  We scan
     * `/proc/net/tcp` for established connections on these ports.
     * This is a heuristic — it may produce false positives if another
     * app uses these ports, but it's a useful signal in combination
     * with other checks.
     */
    private fun isJdwpActive(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("cat", "/proc/net/tcp"))
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                reader.lineSequence()
                    .drop(1) // Skip header
                    .any { line ->
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size >= 4) {
                            // Local address is in parts[1], format is HEX_IP:HEX_PORT
                            val localPort = parts[1].substringAfter(":")
                                .toLongOrNull(16)?.toInt() ?: 0
                            // JDWP typically uses ports in 8000-8999 range
                            localPort in 8000..8999
                        } else false
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check JDWP: ${e.message}")
            false
        }
    }
}

/**
 * Result of anti-debugging checks.
 */
data class DebugResult(
    /** Whether any debug indicators were found. */
    val isDebugged: Boolean,
    /** List of specific indicators that triggered. */
    val indicators: List<String>
)
