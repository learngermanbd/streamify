package com.streamify.app.security

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 7 · Step 7.9 — Honeypot detection system.
 *
 * Embeds fake sensitive data and code paths that appear valuable to
 * reverse engineers.  If an attacker uses any of these decoys, it's
 * a strong signal that the app is being actively analyzed, and the
 * security gate should escalate.
 *
 * ## Honeypot types
 *  1. **Fake API endpoints** — plausible-looking URLs that, if
 *     called, flag the caller as an attacker.
 *  2. **Decoy encryption keys** — Base64 strings that look like
 *     real keys but are actually traps.
 *  3. **Dead code paths** — methods that appear to unlock premium
 *     features but instead trigger a security response.
 *  4. **Canary values** — values embedded in code that, if found
 *     in memory dumps or modified APKs, prove tampering.
 *
 * ## Usage
 * ```kotlin
 * // Initialize at app start
 * HoneyPotManager.init()
 *
 * // Check if a canary has been accessed
 * if (HoneyPotManager.isCanaryTriggered()) { /* escalate */ }
 * ```
 */
object HoneyPotManager {

    private const val TAG = "HoneyPotManager"

    /**
     * Fake API endpoints that look like admin/debug endpoints.
     * If any of these are called (detected server-side or via
     * network monitoring), the caller is an attacker.
     */
    val FAKE_ENDPOINTS = listOf(
        "https://api.learngermanwith.fun/v2/admin/debug/config",
        "https://api.learngermanwith.fun/v2/internal/metrics/raw",
        "https://api.learngermanwith.fun/v2/admin/users/export",
        "https://api.learngermanwith.fun/v2/debug/feature-flags",
    )

    /**
     * Decoy encryption keys that look like real AES/RSA keys.
     * These are deliberately weak or known values — if an attacker
     * extracts and uses them, their requests can be identified.
     */
    val DECOY_KEYS = listOf(
        // Looks like a Base64 AES key but is actually a hash of "honeypot"
        "aG9uZXlwb3Q=",   // base64("honeypot")
        // Looks like a Firebase key
        "AAAAFAKEKEY-REPLACE-WITH-REAL-KEY=",
        // Looks like an API key
        "sk_live_FAKE_51H0n3yp0tK3yD3c0y",
    )

    /**
     * Canary values embedded in critical code paths.  Each canary
     * has a unique ID and a trigger state.  If a canary is accessed
     * (e.g., by an attacker calling a method they shouldn't), the
     * trigger flag is set.
     */
    private val canaryStates = ConcurrentHashMap<String, Boolean>()

    /**
     * Initialize honeypot canaries.  Call once at app start.
     */
    fun init() {
        canaryStates["premium_unlock"] = false
        canaryStates["admin_access"] = false
        canaryStates["debug_config"] = false
        canaryStates["key_export"] = false
        Log.d(TAG, "Honeypot canaries initialized: ${canaryStates.size}")
    }

    /**
     * Trigger a canary.  Called from dead code paths that an
     * attacker might try to activate.
     *
     * @param canaryId The canary identifier.
     */
    fun triggerCanary(canaryId: String) {
        canaryStates[canaryId] = true
        Log.e(TAG, "CANARY TRIGGERED: $canaryId — possible attack!")

        // Report to Sentry
        try {
            io.sentry.Sentry.captureMessage(
                "Honeypot canary triggered: $canaryId"
            ) { scope ->
                scope.setExtra("canaryId", canaryId)
                scope.setExtra("timestamp", System.currentTimeMillis().toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to report canary: ${e.message}")
        }
    }

    /**
     * Check if any canary has been triggered.
     */
    fun isCanaryTriggered(): Boolean {
        return canaryStates.any { it.value }
    }

    /**
     * Get the list of triggered canaries.
     */
    fun getTriggeredCanaries(): List<String> {
        return canaryStates.filter { it.value }.keys.toList()
    }

    /**
     * Reset all canary states.  Called after security escalation
     * has been handled.
     */
    fun reset() {
        canaryStates.keys.forEach { canaryStates[it] = false }
        Log.d(TAG, "All canaries reset")
    }

    // ── Dead code paths (honeypots) ──────────────────────────────

    /**
     * Fake "premium unlock" that appears to bypass the subscription.
     * If an attacker patches the code to call this, the canary fires.
     *
     * **DO NOT CALL THIS METHOD** — it's a honeypot trap.
     */
    @Suppress("unused")
    private fun unlockPremiumFeatures(): Boolean {
        triggerCanary("premium_unlock")
        return false // Always returns false even if called
    }

    /**
     * Fake "admin access" that appears to grant elevated privileges.
     *
     * **DO NOT CALL THIS METHOD** — it's a honeypot trap.
     */
    @Suppress("unused")
    private fun grantAdminAccess(token: String): Boolean {
        triggerCanary("admin_access")
        return false
    }

    /**
     * Fake "debug config dump" that appears to expose secrets.
     *
     * **DO NOT CALL THIS METHOD** — it's a honeypot trap.
     */
    @Suppress("unused")
    private fun dumpDebugConfig(): String {
        triggerCanary("debug_config")
        return "{}" // Return empty JSON
    }
}
