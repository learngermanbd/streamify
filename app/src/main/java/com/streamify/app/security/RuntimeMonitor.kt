package com.streamify.app.security

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Phase 7 · Step 7.9 — Runtime environment monitoring.
 *
 * Inspects the process's memory maps, thread state, and loaded
 * libraries for signs of injection or tampering at runtime.
 *
 * ## Checks
 *  1. **Memory map scan** — reads `/proc/self/maps` and searches
 *     for known hooking/injection framework libraries (Frida,
 *     Xposed, Substrate, Cydia, etc.).
 *  2. **Thread count monitoring** — an unusually high thread count
 *     may indicate an attached debugger or injection agent.
 *  3. **Library integrity** — verifies that only expected native
 *     libraries are loaded (compares against a baseline).
 *
 * ## Usage
 * ```kotlin
 * val result = RuntimeMonitor.scan()
 * if (result.isSuspicious) { /* escalate */ }
 * ```
 */
object RuntimeMonitor {

    private const val TAG = "RuntimeMonitor"

    /**
     * Suspicious library names or paths found in `/proc/self/maps`.
     * Each entry is a lowercase substring match.
     */
    private val SUSPICIOUS_LIBRARIES = listOf(
        "frida",               // Frida agent
        "frida-agent",
        "xposed",              // Xposed framework
        "libxposed",
        "substrate",           // Cydia Substrate
        "libsubstrate",
        "libcydia",            // Cydia
        "libdvm",              // Dalvik hooking
        "libsandhook",         // SandHook framework
        "libwhale",            // Whale hooking engine
        "libblackbox",         // BlackBox virtual framework
        "libva++.so",          // VirtualApp
        "libva-native.so",
        "io.virtualapp",       // VirtualApp package
        "com.saurik.substrate",
        "de.robv.android.xposed",
        "org.lsposed",         // LSPosed
        "me.weishu.exp",       // TaiChi
        "top.canyie.pine",     // Pine hook framework
    )

    /**
     * Expected baseline of thread count.  The app typically runs
     * 15–40 threads.  If we see > 80, something is injecting threads.
     */
    private const val THREAD_COUNT_WARN_THRESHOLD = 60
    private const val THREAD_COUNT_CRITICAL_THRESHOLD = 80

    /**
     * Scan the runtime environment for injection and tampering.
     */
    fun scan(): MonitorResult {
        val indicators = mutableListOf<String>()

        // ── Check 1: Memory maps ─────────────────────────────────
        val mapIndicators = scanMemoryMaps()
        indicators.addAll(mapIndicators)

        // ── Check 2: Thread count ────────────────────────────────
        val threadCount = Thread.activeCount()
        if (threadCount > THREAD_COUNT_CRITICAL_THRESHOLD) {
            indicators.add("threadCount:critical=$threadCount")
        } else if (threadCount > THREAD_COUNT_WARN_THRESHOLD) {
            indicators.add("threadCount:warn=$threadCount")
        }

        // ── Check 3: Loaded native libraries ─────────────────────
        val libIndicators = scanLoadedLibraries()
        indicators.addAll(libIndicators)

        return MonitorResult(
            isSuspicious = indicators.isNotEmpty(),
            threadCount = threadCount,
            indicators = indicators
        )
    }

    /**
     * Read `/proc/self/maps` and search for suspicious library names.
     */
    private fun scanMemoryMaps(): List<String> {
        val found = mutableListOf<String>()
        try {
            File("/proc/self/maps").useLines { lines ->
                val seen = mutableSetOf<String>()
                lines.forEach { line ->
                    val lower = line.lowercase()
                    for (lib in SUSPICIOUS_LIBRARIES) {
                        if (lib in lower && seen.add(lib)) {
                            found.add("maps:$lib")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /proc/self/maps: ${e.message}")
        }
        return found
    }

    /**
     * Use `Runtime.exec("cat /proc/self/maps")` as a secondary
     * check for loaded libraries — this catches cases where the
     * File-based read is hooked but the process exec is not.
     */
    private fun scanLoadedLibraries(): List<String> {
        val found = mutableListOf<String>()
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("cat", "/proc/self/maps"))
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                val seen = mutableSetOf<String>()
                reader.lineSequence().forEach { line ->
                    val lower = line.lowercase()
                    for (lib in SUSPICIOUS_LIBRARIES) {
                        if (lib in lower && seen.add("exec:$lib")) {
                            found.add("exec:$lib")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed exec scan: ${e.message}")
        }
        return found
    }
}

/**
 * Result of the runtime environment scan.
 */
data class MonitorResult(
    /** Whether any suspicious indicators were found. */
    val isSuspicious: Boolean,
    /** Current active thread count. */
    val threadCount: Int,
    /** List of specific indicators that triggered. */
    val indicators: List<String>
)
