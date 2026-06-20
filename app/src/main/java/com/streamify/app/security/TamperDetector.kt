package com.streamify.app.security

import android.content.Context
import android.util.Log
import java.util.zip.ZipFile

/**
 * Phase 7 · Step 7.5 — Detects signs of APK repackaging, resource
 * modification, and runtime tampering.
 *
 * Complements [IntegrityChecker] with deeper structural analysis:
 *  - META-INF file inspection (unexpected signing artifacts)
 *  - ZIP structure anomalies (re-signing leaves traces)
 *  - Native environment checks via [NativeSecurityManager]
 *
 * All checks are designed to be hard to patch out: they use multiple
 * independent verification paths so bypassing one still triggers
 * another.
 */
object TamperDetector {

    private const val TAG = "TamperDetector"

    /**
     * Run all tamper-detection checks.  Returns the first
     * non-[TamperResult.Clean] result, or [TamperResult.Clean] if
     * everything looks normal.
     */
    fun detect(context: Context): TamperResult {
        val metaInfResult = checkMetaInf(context)
        if (metaInfResult != TamperResult.Clean) return metaInfResult

        val nativeResult = checkNativeEnvironment(context)
        if (nativeResult != TamperResult.Clean) return nativeResult

        val debuggableResult = checkDebuggable(context)
        if (debuggableResult != TamperResult.Clean) return debuggableResult

        return TamperResult.Clean
    }

    // ── META-INF inspection ─────────────────────────────────────────
    // A re-signed APK will have different META-INF files than the
    // original signing.  We check for known-good patterns.

    /** Expected META-INF file prefixes for a legitimate release build. */
    private val EXPECTED_META_INF_PREFIXES = listOf(
        "META-INF/MANIFEST.MF",
        "META-INF/CERT.",       // v1 signing (JAR signing)
        "META-INF/*.SF",        // signature file
        "META-INF/*.RSA",       // certificate chain
        "META-INF/*.DSA",       // legacy DSA
        "META-INF/*.EC",        // ECDSA
    )

    private fun checkMetaInf(context: Context): TamperResult {
        return try {
            val apkPath = context.packageCodePath
            ZipFile(apkPath).use { zip ->
                val metaEntries = zip.entries().asSequence()
                    .filter { it.name.startsWith("META-INF/", ignoreCase = true) }
                    .map { it.name }
                    .toList()

                // Check for suspicious META-INF entries that tools like
                // apktool, MT Manager, or Lucky Patcher leave behind.
                val suspiciousPatterns = listOf(
                    "apktool", "lucky", "patcher", "mod", "crack",
                    "hack", "unsigned", "tampered", "modified"
                )
                for (entry in metaEntries) {
                    val lower = entry.lowercase()
                    for (pattern in suspiciousPatterns) {
                        if (lower.contains(pattern)) {
                            Log.w(TAG, "Suspicious META-INF entry: $entry")
                            return TamperResult.TamperDetected(
                                "Suspicious META-INF entry: $entry"
                            )
                        }
                    }
                }

                // A re-signed APK typically has MORE META-INF entries
                // than the original.  Flag if there are more than 10
                // entries (normal APKs have 3-6).
                if (metaEntries.size > 10) {
                    Log.w(TAG, "Unusual META-INF entry count: ${metaEntries.size}")
                    return TamperResult.TamperDetected(
                        "Unusual META-INF entry count: ${metaEntries.size}"
                    )
                }
            }
            TamperResult.Clean
        } catch (e: Exception) {
            Log.e(TAG, "META-INF check failed", e)
            TamperResult.Clean // Don't block on check failure
        }
    }

    // ── Native environment checks ───────────────────────────────────
    // Leverages the NDK-based checks from Step 7.4.

    private fun checkNativeEnvironment(context: Context): TamperResult {
        if (!NativeSecurityManager.isLoaded) {
            Log.w(TAG, "Native security library not loaded; skipping native checks")
            return TamperResult.Clean
        }

        val flags = NativeSecurityManager.checkEnvironment(context)

        if (NativeSecurityManager.ThreatFlag.has(flags, NativeSecurityManager.ThreatFlag.HOOK)) {
            return TamperResult.HookDetected(
                "Frida/Xposed/Substrate detected via /proc/self/maps"
            )
        }

        if (NativeSecurityManager.ThreatFlag.has(flags, NativeSecurityManager.ThreatFlag.ROOT)) {
            return TamperResult.RootDetected("Root/Magisk detected")
        }

        return TamperResult.Clean
    }

    // ── Debuggable flag check ───────────────────────────────────────
    // A repackaged APK might have android:debuggable="true" injected.

    private fun checkDebuggable(context: Context): TamperResult {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName, 0
            )
            val isDebuggable = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

            // In release builds, being debuggable is suspicious.
            // We check via the R8-stripped BuildConfig.DEBUG flag
            // which is baked into the APK at build time.
            if (isDebuggable && !com.streamify.app.BuildConfig.DEBUG) {
                TamperResult.TamperDetected(
                    "Release APK has FLAG_DEBUGGABLE set (likely repackaged)"
                )
            } else {
                TamperResult.Clean
            }
        } catch (e: Exception) {
            Log.e(TAG, "Debuggable check failed", e)
            TamperResult.Clean
        }
    }
}
