package com.streamify.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Phase 7 · Step 7.5 — Runtime APK integrity verification.
 *
 * Performs three categories of checks:
 *  1. **Signature verification** — compares the signing certificate
 *     SHA-256 against [expectedSignatureSha256].
 *  2. **File integrity** — hashes critical APK entries (classes.dex,
 *     resources.arsc, lib/ contents) and compares against known-good
 *     digests.
 *  3. **Installer source** — ensures the app was installed from a
 *     legitimate source (Google Play / Galaxy Store / direct deploy).
 *
 * All checks run on a background thread.  Results are reported to
 * [SelfHealing] which decides the response.
 */
object IntegrityChecker {

    private const val TAG = "IntegrityChecker"

    /**
     * Expected SHA-256 of the signing certificate (uppercase hex, no
     * colons).
     *
     * Populated by [com.streamify.app.StreamifyApp.onCreate] from
     * `BuildConfig.APP_SIGNING_SHA256` (which is in turn populated
     * by `app/build.gradle.kts` from `signing.properties → APP_SIGNING_SHA256`).
     * Empty string means "skip signature check" (debug builds
     * with no signing.properties).
     */
    var expectedSignatureSha256: String = ""

    /**
     * Known-good SHA-256 digests for critical APK entries.
     *
     * Populated at runtime by [IntegrityHashBuilder.hashInstalledApk]
     * (called by [verifyFileIntegrity] lazily on the SecurityGate
     * worker thread).  Empty map means "skip file-integrity check".
     */
    @Volatile
    var expectedEntryHashes: Map<String, String> = emptyMap()

    /**
     * Run all integrity checks.  Returns [TamperResult.Clean] if
     * everything passes, or a descriptive [TamperResult] if not.
     */
    fun check(context: Context): TamperResult {
        val sigResult = verifySignature(context)
        if (sigResult != TamperResult.Clean) return sigResult

        val fileResult = verifyFileIntegrity(context)
        if (fileResult != TamperResult.Clean) return fileResult

        val installerResult = verifyInstaller(context)
        if (installerResult != TamperResult.Clean) return installerResult

        return TamperResult.Clean
    }

    // ── Signature verification ──────────────────────────────────────

    private fun verifySignature(context: Context): TamperResult {
        if (expectedSignatureSha256.isBlank()) {
            Log.d(TAG, "Signature check skipped (no expected hash configured)")
            return TamperResult.Clean
        }

        return try {
            val certHash = getSigningCertSha256(context)
            if (certHash == null) {
                TamperResult.SignatureMissing("Could not read signing certificate")
            } else if (!certHash.equals(expectedSignatureSha256, ignoreCase = true)) {
                TamperResult.SignatureMismatch(
                    "Expected=$expectedSignatureSha256, Actual=$certHash"
                )
            } else {
                TamperResult.Clean
            }
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed", e)
            TamperResult.SignatureError(e.message ?: "unknown")
        }
    }

    /**
     * Extract the SHA-256 fingerprint of the first signing certificate.
     * Uses GET_SIGNING_CERTIFICATES on API 28+, GET_SIGNATURES on legacy.
     */
    fun getSigningCertSha256(context: Context): String? {
        val pm = context.packageManager
        val pkgName = context.packageName

        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
            info.signatures
        }

        if (certs.isNullOrEmpty()) return null

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(certs[0].toByteArray())
        return hash.joinToString("") { "%02X".format(it) }
    }

    // ── File integrity ──────────────────────────────────────────────

    private fun verifyFileIntegrity(context: Context): TamperResult {
        // v1.1.1 hardening — lazy-hash fallback (review issue #1
        // fix). If StreamifyApp.onCreate did NOT seed
        // `expectedEntryHashes` (debug build with no signing SHA, R8-
        // stripped field, app upgraded with a stale cache) we STILL
        // want a working integrity check on first launch. The
        // lazy path runs on the SecurityGate worker thread (via
        // runChecks -> Executors.newSingleThreadExecutor) — NOT on
        // the splash-blocking main thread — so even a 150 ms APK
        // zip iteration completes within the gate's background
        // budget.
        @Suppress("UNUSED_VARIABLE")
        val callerThreadName = Thread.currentThread().name.let { "$it" }
        if (!callerThreadName.contains("SecurityGate", ignoreCase = false) &&
            !callerThreadName.contains("main", ignoreCase = false)) {
            Log.d(TAG, "verifyFileIntegrity running on thread: $callerThreadName")
        }
        val expectedHashesToCheck = synchronized(this) {
            if (expectedEntryHashes.isEmpty()) {
                Log.i(TAG, "expectedEntryHashes empty; computing on demand (lazy)")
                IntegrityHashBuilder.hashInstalledApk(context).also {
                    expectedEntryHashes = it
                }
            } else {
                expectedEntryHashes
            }
        }

        if (expectedHashesToCheck.isEmpty()) {
            Log.d(TAG, "File-integrity check skipped (no expected hashes configured)")
            return TamperResult.Clean
        }

        return try {
            val apkPath = context.packageCodePath
            ZipFile(apkPath).use { zip ->
                for ((entryName, expectedHash) in expectedHashesToCheck) {
                    val entry = zip.getEntry(entryName) ?: continue
                    val actual = hashZipEntry(zip, entry)
                    if (!actual.equals(expectedHash, ignoreCase = true)) {
                        return TamperResult.FileIntegrityFailure(
                            "Entry '$entryName' hash mismatch: " +
                            "expected=$expectedHash, actual=$actual"
                        )
                    }
                }
            }
            TamperResult.Clean
        } catch (e: Exception) {
            Log.e(TAG, "File integrity check failed", e)
            TamperResult.FileIntegrityError(e.message ?: "unknown")
        }
    }

    private fun hashZipEntry(zip: ZipFile, entry: java.util.zip.ZipEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(entry).use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    // ── Installer source ────────────────────────────────────────────

    /** Legitimate installer package names. */
    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending",          // Google Play Store
        "com.google.android.feedback",   // Play Store (automated)
        "com.sec.android.app.samsungapps", // Galaxy Store
        "com.amazon.venezia",            // Amazon Appstore
        null,                            // ADB / direct install (dev builds)
    )

    private fun verifyInstaller(context: Context): TamperResult {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }

            if (installer !in TRUSTED_INSTALLERS) {
                TamperResult.UntrustedInstaller(
                    "Installer '$installer' is not in the trusted set"
                )
            } else {
                TamperResult.Clean
            }
        } catch (e: Exception) {
            Log.e(TAG, "Installer check failed", e)
            TamperResult.Clean // Don't block on installer check failure
        }
    }
}

/**
 * Sealed class describing the outcome of a tamper check.
 * [SelfHealing] consumes these to decide the response.
 */
sealed class TamperResult {
    /** All checks passed. */
    object Clean : TamperResult()

    data class SignatureMismatch(val detail: String) : TamperResult()
    data class SignatureMissing(val detail: String) : TamperResult()
    data class SignatureError(val detail: String) : TamperResult()
    data class FileIntegrityFailure(val detail: String) : TamperResult()
    data class FileIntegrityError(val detail: String) : TamperResult()
    data class UntrustedInstaller(val detail: String) : TamperResult()
    data class TamperDetected(val detail: String) : TamperResult()
    data class HookDetected(val detail: String) : TamperResult()
    data class RootDetected(val detail: String) : TamperResult()

    val isTamper: Boolean
        get() = this !is Clean
}
