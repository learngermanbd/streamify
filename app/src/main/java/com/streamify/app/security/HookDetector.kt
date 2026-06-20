package com.streamify.app.security

import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Phase 7 · Step 7.6 — Kotlin-side hook framework detection.
 *
 * Complements [NativeSecurityManager.checkEnvironment]'s
 * `/proc/self/maps` scanning with Kotlin-level checks that are
 * harder to bypass with a single native-hook patch:
 *  - Xposed/LSPosed class loader inspection
 *  - Frida default port (27042) probe
 *  - Stack trace analysis for hooking frames
 *  - Known hooking framework files on disk
 *
 * All checks are non-destructive and run on whatever thread calls
 * [detect].
 */
object HookDetector {

    private const val TAG = "HookDetector"

    /** Default Frida server port. */
    private const val FRIDA_DEFAULT_PORT = 27042

    /** Known hooking framework class names. */
    private val HOOK_CLASS_NAMES = listOf(
        "de.robv.android.xposed.XposedBridge",
        "de.robv.android.xposed.XC_MethodHook",
        "com.android.internal.os.ZygoteInit",  // Xposed modifies this
        "org.lsposed.managerhooker.Entry",
        "com.swift.sandhook.SandHook",
        "com.swift.sandhook.NativeHook",
    )

    /** Known hooking framework file paths. */
    private val HOOK_FILES = listOf(
        "/data/data/de.robv.android.xposed.installer",
        "/data/data/org.lsposed.manager",
        "/data/data/me.weishu.kernelsu",       // KernelSU
        "/data/data/com.topjohnwu.magisk",     // Magisk app
        "/data/local/tmp/frida-server",
        "/data/local/tmp/re.frida.server",
        "/sdcard/Download/frida-server",
    )

    /** Suspicious strings in stack traces. */
    private val HOOK_STACK_PATTERNS = listOf(
        "XposedBridge",
        "de.robv.android.xposed",
        "com.saurik.substrate",
        "frida",
        "LSPosed",
        "EdXposed",
        "SandHook",
        "ShadowHook",
    )

    /**
     * Run all Kotlin-side hook detection checks.  Returns a
     * [DetectionResult] with a score (higher = more likely hooked).
     */
    fun detect(): DetectionResult {
        var score = 0
        val indicators = mutableListOf<String>()

        // Check for hooking framework classes in the class loader
        val classResult = checkHookClasses()
        if (classResult.first) {
            score += 5 // Strong evidence
            indicators.addAll(classResult.second)
        }

        // Check for Frida server on default port
        val fridaResult = checkFridaPort()
        if (fridaResult.first) {
            score += 5 // Strong evidence
            indicators.addAll(fridaResult.second)
        }

        // Check for hooking framework files
        val fileResult = checkHookFiles()
        if (fileResult.first) {
            score += 3
            indicators.addAll(fileResult.second)
        }

        // Check stack traces for hooking frames
        val stackResult = checkStackTraces()
        if (stackResult.first) {
            score += 3
            indicators.addAll(stackResult.second)
        }

        // Threshold: score >= 5 means likely hooked
        val isHooked = score >= 5

        if (isHooked) {
            Log.w(TAG, "Hook framework detected (score=$score): $indicators")
        }

        return DetectionResult(
            isDetected = isHooked,
            score = score,
            indicators = indicators
        )
    }

    // ── Class loader inspection ─────────────────────────────────────
    // Xposed/LSPosed inject classes into the app's class loader.
    // Trying to load them reveals their presence.

    private fun checkHookClasses(): Pair<Boolean, List<String>> {
        val found = mutableListOf<String>()
        for (className in HOOK_CLASS_NAMES) {
            try {
                Class.forName(className, false, javaClass.classLoader)
                found.add("CLASS=$className")
            } catch (_: ClassNotFoundException) {
                // Expected — class doesn't exist on clean devices
            }
        }
        return Pair(found.isNotEmpty(), found)
    }

    // ── Frida port probe ────────────────────────────────────────────
    // Frida server listens on port 27042 by default.  A successful
    // connection means Frida is running on the device.

    private fun checkFridaPort(): Pair<Boolean, List<String>> {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", FRIDA_DEFAULT_PORT),
                    1000 // 1-second timeout to avoid blocking if port is filtered
                )
                Pair(true, listOf("FRIDA_PORT=127.0.0.1:$FRIDA_DEFAULT_PORT"))
            }
        } catch (_: Exception) {
            // Connection refused/timeout — no Frida server (expected)
            Pair(false, emptyList())
        }
    }

    // ── File system checks ──────────────────────────────────────────

    private fun checkHookFiles(): Pair<Boolean, List<String>> {
        val found = mutableListOf<String>()
        for (path in HOOK_FILES) {
            if (File(path).exists()) {
                found.add("FILE=$path")
            }
        }
        return Pair(found.isNotEmpty(), found)
    }

    // ── Stack trace analysis ────────────────────────────────────────
    // If a method is hooked, the stack trace will contain frames
    // from the hooking framework (e.g., XposedBridge.handleHookedMethod).

    private fun checkStackTraces(): Pair<Boolean, List<String>> {
        val found = mutableListOf<String>()
        val stackTrace = Thread.currentThread().stackTrace

        for (frame in stackTrace) {
            val className = frame.className ?: continue
            for (pattern in HOOK_STACK_PATTERNS) {
                if (className.contains(pattern, ignoreCase = true)) {
                    found.add("STACK=$className.${frame.methodName}")
                    break
                }
            }
        }

        return Pair(found.isNotEmpty(), found)
    }
}
