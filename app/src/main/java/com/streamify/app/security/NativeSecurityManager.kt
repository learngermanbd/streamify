package com.streamify.app.security

import android.content.Context
import android.util.Log

/**
 * Phase 7 · Step 7.4 — Kotlin bridge to the native security library.
 *
 * Loads `libnative_security.so` via [System.loadLibrary] and exposes
 * Kotlin-friendly wrappers around the JNI functions.  The native
 * function names are registered dynamically via `RegisterNatives` in
 * `JNI_OnLoad` so the .so's export table contains only the stripped
 * `JNI_OnLoad` symbol — the actual C++ function names are unrelated
 * to the Kotlin `external fun` names, frustrating static analysis.
 *
 * ## Usage
 * ```kotlin
 * // Full environment check (root + debugger + emulator + hooks)
 * val threats = NativeSecurityManager.checkEnvironment(context)
 * if (threats.hasFlag(ThreatFlag.ROOT)) { ... }
 *
 * // APK signature verification
 * val valid = NativeSecurityManager.verifySignature(context, expectedSha256)
 * ```
 */
object NativeSecurityManager {

    private const val TAG = "NativeSecurityManager"

    /**
     * Bitmask flags returned by [checkEnvironment].
     * Each flag represents a class of threat detected by the native layer.
     */
    object ThreatFlag {
        const val ROOT      = 1 shl 0  // su binary or Magisk detected
        const val DEBUGGER  = 1 shl 1  // ptrace-based debugger attached
        const val EMULATOR  = 1 shl 2  // emulator artifacts detected
        const val HOOK      = 1 shl 3  // Frida / Xposed / Substrate in /proc/self/maps

        /** Convenience: check if [flags] contains a specific [threat]. */
        fun has(flags: Int, threat: Int): Boolean = (flags and threat) != 0
    }

    // ── Library loading ──────────────────────────────────────────────

    /**
     * Whether the native library loaded successfully.  If `false`,
     * all native check functions will return permissive defaults
     * (no threat detected) so the app doesn't crash on devices
     * where the .so is missing or incompatible.
     */
    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("native_security")
            isLoaded = true
            Log.d(TAG, "libnative_security.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libnative_security.so", e)
            isLoaded = false
        }
    }

    // ── Native functions (bound via RegisterNatives in JNI_OnLoad) ───

    /**
     * Run all native environment checks.
     * @return Bitmask of [ThreatFlag] values.  0 = clean environment.
     */
    external fun nativeCheckEnvironment(context: Context): Int

    /**
     * Verify the APK's signing certificate SHA-256 fingerprint.
     * @param expectedHash The expected SHA-256 hex string (uppercase, no colons).
     * @return `true` if the signature matches.
     */
    external fun nativeVerifySignature(
        context: Context,
        expectedHash: String
    ): Boolean

    // ── Kotlin-friendly wrappers ─────────────────────────────────────

    /**
     * Run all native security checks and return the threat bitmask.
     * Returns 0 (no threats) if the native library failed to load.
     */
    fun checkEnvironment(context: Context): Int {
        if (!isLoaded) {
            Log.w(TAG, "Native library not loaded; returning 0 (permissive)")
            return 0
        }
        return try {
            nativeCheckEnvironment(context)
        } catch (e: Exception) {
            Log.e(TAG, "nativeCheckEnvironment failed", e)
            0
        }
    }

    /**
     * Verify the APK signature.  Returns `true` if the native library
     * is not loaded (permissive fallback).
     */
    fun verifySignature(context: Context, expectedSha256: String): Boolean {
        if (!isLoaded) {
            Log.w(TAG, "Native library not loaded; returning true (permissive)")
            return true
        }
        return try {
            nativeVerifySignature(context, expectedSha256)
        } catch (e: Exception) {
            Log.e(TAG, "nativeVerifySignature failed", e)
            true // permissive fallback
        }
    }

    /**
     * Convenience: run a full security audit and return `true` if the
     * environment is clean (no root, no debugger, no emulator, no hooks).
     */
    fun isEnvironmentSafe(context: Context): Boolean {
        return checkEnvironment(context) == 0
    }

    /**
     * Get a human-readable description of detected threats.
     */
    fun describeThreats(flags: Int): List<String> {
        val threats = mutableListOf<String>()
        if (ThreatFlag.has(flags, ThreatFlag.ROOT))     threats.add("Root/Magisk detected")
        if (ThreatFlag.has(flags, ThreatFlag.DEBUGGER))  threats.add("Debugger attached")
        if (ThreatFlag.has(flags, ThreatFlag.EMULATOR))  threats.add("Emulator detected")
        if (ThreatFlag.has(flags, ThreatFlag.HOOK))      threats.add("Hook framework (Frida/Xposed) detected")
        return threats
    }

    /**
     * Called from native code (via JNI) to convert a byte array to a
     * hex string.  Used by the APK signature verification flow.
     */
    @Suppress("unused") // Called from JNI
    @JvmStatic
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }
}
