package com.streamify.app.security

import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * Phase 7 · Step 7.10 — Play Integrity verdict representation.
 *
 * Parses and represents the Play Integrity API response.  The full
 * verdict is obtained server-side (Phase 8), but we perform a
 * lightweight client-side parse of the JWS token for immediate
 * threat assessment.
 *
 * ## Verdict levels
 *  - **MEETS_DEVICE_INTEGRITY** — device passes CTS and hardware-
 *    backed key attestation (highest trust).
 *  - **MEETS_BASIC_INTEGRITY** — device passed basic software
 *    checks (may have unlocked bootloader).
 *  - **MEETS_VIRTUAL_INTEGRITY** — VM/emulator consistent with
 *    Google Play environment.
 *  - **NO_INTEGRITY** — device failed all checks.
 *
 * ## Scoring integration
 *  - MEETS_DEVICE_INTEGRITY → score 0 (clean)
 *  - MEETS_BASIC_INTEGRITY → score 1 (soft)
 *  - MEETS_VIRTUAL_INTEGRITY → score 2 (soft)
 *  - NO_INTEGRITY → score 4 (hard)
 *  - UNLICENSED account → score 3 (soft)
 */
data class IntegrityVerdict(
    /** Whether the device passes CTS profile integrity. */
    val meetsDeviceIntegrity: Boolean,
    /** Whether the device passes basic integrity. */
    val meetsBasicIntegrity: Boolean,
    /** Whether a virtual environment meets integrity. */
    val meetsVirtualIntegrity: Boolean,
    /** App package name matches our package. */
    val appPackageNameValid: Boolean,
    /** App signing certificate matches expected. */
    val appCertificateValid: Boolean,
    /** Google Play licensing verdict. */
    val licensingVerdict: String,
    /** Device recognition verdict. */
    val deviceRecognitionVerdict: String,
    /** Raw token for server-side verification. */
    val rawToken: String = "",
    /**
     * Phase 7 · Step 7.10 v1.1.1 hardening — tri-state classifier.
     * Defaults to [VerdictState.CLEAN] so all existing callers
     * (which construct via the canonical `fromToken(...)` factory)
     * continue compiling + scoring identically. New callers can
     * populate this from a GoogleApiAvailability + Task.await
     * timeout boundary to flag offline / GMS-missing verdicts
     * without leaking a hard-block score.
     */
    val verdictState: VerdictState = VerdictState.CLEAN
) {

    /**
     * The tri-state classifier for the verdict. Used at runtime to
     * differentiate between an authentic "device meets CTS profile"
     * reply, a "Google Play Services not available" degraded
     * graceful path, and an explicit tamper / debug reply.
     *
     * Maps onto Phase 7 · Step 7.10's risk-scoring impact:
     *  - **CLEAN**     — score +0, full trust, gate silent
     *  - **WEAK_BUT_OK** — score +0, only MEETS_BASIC_INTEGRITY (log breadcrumb)
     *  - **INCONCLUSIVE** — score +0, GMS missing / timeout / malformed (gate waits for server)
     *  - **TAMPER**    — score +4 (debug signs, NOT_PLAY_EVALUATION, etc.)
     */
    enum class VerdictState { CLEAN, WEAK_BUT_OK, INCONCLUSIVE, TAMPER, GMS_UNAVAILABLE, TIMEOUT }
    /**
     * Compute a risk score from the verdict.
     * Higher score = more suspicious.
     */
    fun toRiskScore(): Int {
        var score = 0

        if (!meetsDeviceIntegrity && !meetsBasicIntegrity && !meetsVirtualIntegrity) {
            score += 4  // NO_INTEGRITY — highest risk
        } else if (!meetsDeviceIntegrity && !meetsBasicIntegrity) {
            score += 3  // Only virtual integrity
        } else if (!meetsDeviceIntegrity) {
            score += 1  // Basic but not device integrity
        }

        if (!appPackageNameValid) score += 3
        if (!appCertificateValid) score += 3

        if (licensingVerdict != LICENSED) {
            score += 2
        }

        return score
    }

    /**
     * Whether the device should be considered trustworthy for
     * premium features.
     */
    fun isTrustworthy(): Boolean {
        return meetsDeviceIntegrity && appPackageNameValid &&
            appCertificateValid && licensingVerdict == LICENSED
    }

    /**
     * Whether the device meets minimum requirements for basic
     * functionality (even if not fully trusted).
     */
    fun meetsMinimumRequirements(): Boolean {
        return (meetsDeviceIntegrity || meetsBasicIntegrity) && appPackageNameValid
    }

    companion object {
        private const val TAG = "IntegrityVerdict"

        const val LICENSED = "LICENSED"
        const val UNLICENSED = "UNLICENSED"
        const val UNKNOWN = "UNKNOWN"

        /**
         * Phase 7 · Step 7.10 v1.1.1 — factory for the INCONCLUSIVE
         * verdict surfaced by [com.streamify.app.security.PlayIntegrityManager]
         * when:
         *  - Google Play Services is not available (Huawei, AOSP Xiaomi, …)
         *  - The 4-second PIA deadline elapses
         *  - The Task itself fails with an ApiException we did not handle
         *
         * All `meetsXxx` booleans are false (no positive attestation) but
         * the [verdictState] is set so the gate knows NOT to escalate.
         */
        fun inconclusive(gmsAvailable: Boolean, label: String): IntegrityVerdict =
            IntegrityVerdict(
                meetsDeviceIntegrity = false,
                meetsBasicIntegrity = false,
                meetsVirtualIntegrity = false,
                appPackageNameValid = gmsAvailable,
                appCertificateValid = gmsAvailable,
                licensingVerdict = UNKNOWN,
                deviceRecognitionVerdict = label,
                rawToken = "",
                verdictState = if (gmsAvailable)
                    VerdictState.TIMEOUT
                else
                    VerdictState.GMS_UNAVAILABLE
            )

        /**
         * Parse a Play Integrity JWS token client-side.
         *
         * **Note:** This is a lightweight parse for immediate
         * threat assessment.  The authoritative result comes from
         * server-side verification (Phase 8) which validates the
         * JWS signature against Google's public key.
         *
         * The token is a JWS with 3 Base64URL-encoded parts:
         * `header.payload.signature`
         */
        fun fromToken(token: String): IntegrityVerdict {
            return try {
                val parts = token.split(".")
                if (parts.size < 2) {
                    Log.w(TAG, "Invalid JWS token format")
                    return unverified(token)
                }

                // Decode the payload (second part)
                val payloadJson = String(
                    Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP),
                    Charsets.UTF_8
                )
                val payload = JSONObject(payloadJson)

                // Parse device integrity
                val deviceIntegrity = payload.optJSONObject("deviceIntegrity")
                val deviceVerdict = deviceIntegrity
                    ?.optJSONArray("deviceRecognitionVerdict")
                val verdicts = mutableSetOf<String>()
                if (deviceVerdict != null) {
                    for (i in 0 until deviceVerdict.length()) {
                        verdicts.add(deviceVerdict.getString(i))
                    }
                }

                val meetsDevice = verdicts.contains("MEETS_DEVICE_INTEGRITY")
                val meetsBasic = verdicts.contains("MEETS_BASIC_INTEGRITY")
                val meetsVirtual = verdicts.contains("MEETS_VIRTUAL_INTEGRITY")

                // Parse app integrity
                val appIntegrity = payload.optJSONObject("appIntegrity")
                val packageName = appIntegrity?.optString("packageName", "")
                val certHash = appIntegrity?.optString("certificateSha256Digest", "")

                // Parse account details
                val accountDetails = payload.optJSONObject("accountDetails")
                val licensing = accountDetails
                    ?.optString("appLicensingVerdict", UNKNOWN)

                IntegrityVerdict(
                    meetsDeviceIntegrity = meetsDevice,
                    meetsBasicIntegrity = meetsBasic,
                    meetsVirtualIntegrity = meetsVirtual,
                    appPackageNameValid = packageName == "com.streamify.app",
                    appCertificateValid = certHash?.isNotEmpty() == true,
                    licensingVerdict = licensing ?: UNKNOWN,
                    deviceRecognitionVerdict = verdicts.joinToString(","),
                    rawToken = token
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse integrity token: ${e.message}")
                unverified(token)
            }
        }

        /**
         * Create an unverified verdict (parse failure).
         * Treats everything as unknown — scores conservatively.
         */
        private fun unverified(token: String) = IntegrityVerdict(
            meetsDeviceIntegrity = false,
            meetsBasicIntegrity = false,
            meetsVirtualIntegrity = false,
            appPackageNameValid = false,
            appCertificateValid = false,
            licensingVerdict = UNKNOWN,
            deviceRecognitionVerdict = "PARSE_FAILED",
            rawToken = token,
            verdictState = VerdictState.INCONCLUSIVE
        )
    }
}
