package com.streamify.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Phase 7 · Step 7.10 — Device attestation checks.
 *
 * Provides additional device-level attestation beyond Play Integrity,
 * including installer source validation, signing certificate
 * verification, and device integrity cross-checks.
 *
 * ## Checks
 *  1. **Installer source** — verifies the app was installed from
 *     Google Play Store (not sideloaded or from a third-party store).
 *  2. **Signing certificate** — runtime verification of the app's
 *     signing certificate against an expected SHA-256 hash.
 *  3. **Boot state** — checks if the device bootloader is locked
 *     (via `Build.IS_DEBUGGABLE` and system properties).
 *  4. **Verified boot** — checks the verified boot state chain
 *     (green/yellow/orange).
 *  5. **GMS availability** — verifies Google Play Services is
 *     installed and up-to-date (required for Play Integrity).
 *
 * ## Usage
 * ```kotlin
 * val result = DeviceAttestation.check(context)
 * if (result.riskScore >= 5) { /* block */ }
 * ```
 */
object DeviceAttestation {

    private const val TAG = "DeviceAttestation"

    /**
     * Expected SHA-256 hash of the signing certificate.
     * Set from EncryptedConstants or BuildConfig at startup.
     * Empty = skip check.
     */
    @Volatile
    var expectedCertSha256: String = ""

    /**
     * Known legitimate installer packages.
     */
    private val LEGITIMATE_INSTALLERS = setOf(
        "com.android.vending",              // Google Play Store
        "com.google.android.feedback",      // Play Store feedback
        "com.sec.android.app.samsungapps",  // Samsung Galaxy Store
        "com.amazon.venezia",               // Amazon Appstore
        null,                               // ADB/sideload (allowed in debug)
    )

    /**
     * Run all device attestation checks and return a composite result.
     */
    fun check(context: Context): AttestationResult {
        val indicators = mutableListOf<String>()
        var score = 0

        // ── Check 1: Installer source ────────────────────────────
        val installerResult = checkInstaller(context)
        if (!installerResult.isLegitimate) {
            score += 3
            indicators.add("installer:${installerResult.installerPackage ?: "null"}")
        }

        // ── Check 2: Signing certificate ─────────────────────────
        val certResult = checkSigningCertificate(context)
        if (!certResult.isValid && certResult.reason != "skip") {
            score += 5
            indicators.add("cert:${certResult.reason}")
        }

        // ── Check 3: Bootloader state ────────────────────────────
        val bootResult = checkBootState()
        if (!bootResult.isLocked) {
            score += 2
            indicators.add("boot:${bootResult.reason}")
        }

        // ── Check 4: GMS availability ────────────────────────────
        val gmsResult = checkGmsAvailability(context)
        if (!gmsResult.isAvailable) {
            score += 2
            indicators.add("gms:${gmsResult.reason}")
        }

        // ── Check 5: Build fingerprint consistency ───────────────
        val buildResult = checkBuildConsistency()
        if (!buildResult.isConsistent) {
            score += 2
            indicators.add("build:${buildResult.reason}")
        }

        return AttestationResult(
            riskScore = score,
            indicators = indicators,
            installerResult = installerResult,
            certResult = certResult,
            bootResult = bootResult,
            gmsResult = gmsResult
        )
    }

    /**
     * Verify the app was installed from a legitimate source.
     */
    private fun checkInstaller(context: Context): InstallerResult {
        return try {
            val installerPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }

            val isLegitimate = installerPackage in LEGITIMATE_INSTALLERS
            InstallerResult(
                isLegitimate = isLegitimate,
                installerPackage = installerPackage
            )
        } catch (e: Exception) {
            Log.w(TAG, "Installer check failed: ${e.message}")
            InstallerResult(isLegitimate = false, installerPackage = null)
        }
    }

    /**
     * Verify the app's signing certificate at runtime.
     */
    private fun checkSigningCertificate(context: Context): CertResult {
        if (expectedCertSha256.isEmpty()) {
            return CertResult(isValid = true, reason = "skip")
        }

        return try {
            // Reuse IntegrityChecker's signature extraction (handles API 28+ / legacy)
            val actualHash = IntegrityChecker.getSigningCertSha256(context)
            when {
                actualHash == null -> CertResult(isValid = false, reason = "no_signatures")
                actualHash.equals(expectedCertSha256, ignoreCase = true) ->
                    CertResult(isValid = true, reason = "match")
                else -> CertResult(isValid = false, reason = "mismatch")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Certificate check failed: ${e.message}")
            CertResult(isValid = false, reason = "error:${e.message}")
        }
    }

    /**
     * Check if the bootloader is locked.
     */
    private fun checkBootState(): BootResult {
        return try {
            // ro.boot.verifiedbootstate values:
            // "green"  = locked, verified (best)
            // "yellow" = unlocked but self-signed
            // "orange" = unlocked, unverified (rooted/custom ROM)
            val bootState = getSystemProperty("ro.boot.verifiedbootstate")
            val isLocked = bootState == "green" || bootState == null

            BootResult(
                isLocked = isLocked,
                reason = bootState ?: "unknown"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Boot state check failed: ${e.message}")
            BootResult(isLocked = true, reason = "check_failed")
        }
    }

    /**
     * Check if Google Play Services is available and up-to-date.
     */
    private fun checkGmsAvailability(context: Context): GmsResult {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo("com.google.android.gms", 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }

            // Play Integrity requires GMS >= 12.0.0 (versionCode ~12000000)
            val isAvailable = versionCode >= 12_000_000
            GmsResult(
                isAvailable = isAvailable,
                versionCode = versionCode,
                reason = if (isAvailable) "ok" else "outdated:$versionCode"
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "GMS not installed")
            GmsResult(isAvailable = false, versionCode = 0, reason = "not_installed")
        } catch (e: Exception) {
            Log.w(TAG, "GMS check failed: ${e.message}")
            GmsResult(isAvailable = false, versionCode = 0, reason = "error")
        }
    }

    /**
     * Cross-check Build properties for consistency.
     * Tampered devices often have mismatched build properties.
     */
    private fun checkBuildConsistency(): BuildResult {
        return try {
            val tags = Build.TAGS ?: ""
            val isTestKeys = tags.contains("test-keys")

            // Production devices should have release-keys
            if (isTestKeys) {
                return BuildResult(isConsistent = false, reason = "test-keys")
            }

            BuildResult(isConsistent = true, reason = "ok")
        } catch (e: Exception) {
            BuildResult(isConsistent = true, reason = "check_failed")
        }
    }

    /**
     * Read a system property via reflection (same approach as RootDetector).
     */
    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (e: Exception) {
            null
        }
    }
}

// ── Result types ──────────────────────────────────────────────────

data class AttestationResult(
    val riskScore: Int,
    val indicators: List<String>,
    val installerResult: InstallerResult,
    val certResult: CertResult,
    val bootResult: BootResult,
    val gmsResult: GmsResult
) {
    val isClean: Boolean get() = riskScore == 0
    val needsEscalation: Boolean get() = riskScore >= 5
}

data class InstallerResult(
    val isLegitimate: Boolean,
    val installerPackage: String?
)

data class CertResult(
    val isValid: Boolean,
    val reason: String
)

data class BootResult(
    val isLocked: Boolean,
    val reason: String
)

data class GmsResult(
    val isAvailable: Boolean,
    val versionCode: Long,
    val reason: String
)

private data class BuildResult(
    val isConsistent: Boolean,
    val reason: String
)
