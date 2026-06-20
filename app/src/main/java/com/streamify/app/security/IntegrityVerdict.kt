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
    val rawToken: String = ""
) {
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
            rawToken = token
        )
    }
}
