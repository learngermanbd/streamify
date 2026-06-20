package com.streamify.app.security

import android.os.Build
import android.util.Log
import java.io.File

/**
 * Phase 7 · Step 7.6 — Kotlin-side root detection.
 *
 * Complements [NativeSecurityManager.checkEnvironment] with
 * Kotlin-level checks that are harder to bypass with a single
 * native-hook patch:
 *  - Build.TAGS contains "test-keys" (custom/unsigned ROMs)
 *  - System properties indicate debuggable or rooted device
 *  - SELinux is in permissive mode (weakens security boundaries)
 *  - Magisk-specific indicators (Zygisk, MagiskHide traces)
 *  - Dangerous file paths accessible without root
 *
 * All checks are non-destructive and run on whatever thread calls
 * [detect].  Returns a [RootResult] describing what was found.
 */
object RootDetector {

    private const val TAG = "RootDetector"

    /**
     * Run all Kotlin-side root checks.  Returns the first
     * non-[RootResult.Clean] result, or [RootResult.Clean].
     */
    fun detect(): RootResult {
        val buildTag = checkBuildTags()
        if (buildTag != RootResult.Clean) return buildTag

        val properties = checkDangerousProperties()
        if (properties != RootResult.Clean) return properties

        val selinux = checkSelinux()
        if (selinux != RootResult.Clean) return selinux

        val magisk = checkMagiskIndicators()
        if (magisk != RootResult.Clean) return magisk

        val suAccess = checkSuAccess()
        if (suAccess != RootResult.Clean) return suAccess

        return RootResult.Clean
    }

    // ── Build tags ──────────────────────────────────────────────────
    // Custom ROMs and test builds use "test-keys" instead of
    // "release-keys".  A production app on a test-keys ROM is
    // likely running on a rooted or modified device.

    private fun checkBuildTags(): RootResult {
        return if (Build.TAGS?.contains("test-keys") == true) {
            Log.w(TAG, "Build.TAGS contains 'test-keys': ${Build.TAGS}")
            RootResult.TestKeysDetected("Build.TAGS=${Build.TAGS}")
        } else {
            RootResult.Clean
        }
    }

    // ── Dangerous system properties ─────────────────────────────────
    // Properties like ro.debuggable=1 or ro.secure=0 indicate a
    // development/debug device or an insecure configuration.

    private fun checkDangerousProperties(): RootResult {
        return try {
            val debuggable = getSystemProperty("ro.debuggable")
            if (debuggable == "1") {
                Log.w(TAG, "ro.debuggable=1 detected")
                return RootResult.DebuggableDevice("ro.debuggable=1")
            }

            val secure = getSystemProperty("ro.secure")
            if (secure == "0") {
                Log.w(TAG, "ro.secure=0 detected")
                return RootResult.InsecureDevice("ro.secure=0")
            }

            RootResult.Clean
        } catch (e: Exception) {
            Log.d(TAG, "Property check failed (non-fatal): ${e.message}")
            RootResult.Clean
        }
    }

    // ── SELinux status ──────────────────────────────────────────────
    // SELinux in permissive mode means the kernel doesn't enforce
    // mandatory access control, which weakens all security boundaries.

    private fun checkSelinux(): RootResult {
        return try {
            // getenforce returns "Enforcing", "Permissive", or "Disabled"
            val process = Runtime.getRuntime().exec(arrayOf("getenforce"))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            when {
                output.equals("Permissive", ignoreCase = true) -> {
                    Log.w(TAG, "SELinux is permissive")
                    RootResult.SelinuxPermissive("SELinux mode: $output")
                }
                output.equals("Disabled", ignoreCase = true) -> {
                    Log.w(TAG, "SELinux is disabled")
                    RootResult.SelinuxPermissive("SELinux mode: $output")
                }
                else -> RootResult.Clean
            }
        } catch (e: Exception) {
            // getenforce might not be available on all devices
            Log.d(TAG, "SELinux check failed (non-fatal): ${e.message}")
            RootResult.Clean
        }
    }

    // ── Magisk indicators ───────────────────────────────────────────
    // Magisk uses Zygisk (Zygote injection) and MagiskHide to
    // conceal itself.  We check for known traces.

    private fun checkMagiskIndicators(): RootResult {
        // Check for Magisk's /data/adb directory structure
        val magiskPaths = listOf(
            "/data/adb/magisk",
            "/data/adb/modules",
            "/data/adb/post-fs-data.d",
            "/data/adb/service.d",
        )
        for (path in magiskPaths) {
            if (File(path).exists()) {
                Log.w(TAG, "Magisk indicator found: $path")
                return RootResult.MagiskDetected("Path exists: $path")
            }
        }

        // Check for Zygisk (Magisk's Zygote injection)
        val zygiskProp = getSystemProperty("init.svc.zygisk")
        if (zygiskProp?.isNotBlank() == true) {
            Log.w(TAG, "Zygisk property detected: init.svc.zygisk=$zygiskProp")
            return RootResult.MagiskDetected("Zygisk active: init.svc.zygisk=$zygiskProp")
        }

        return RootResult.Clean
    }

    // ── su binary access check ──────────────────────────────────────
    // Try to execute `su` and check if it responds.  This is a
    // direct test that's harder to hide than path-based checks.

    private fun checkSuAccess(): RootResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            if (output.isNotBlank() && !output.contains("not found")) {
                Log.w(TAG, "su binary found at: $output")
                RootResult.SuBinaryFound("which su → $output")
            } else {
                RootResult.Clean
            }
        } catch (e: Exception) {
            Log.d(TAG, "su access check failed (non-fatal): ${e.message}")
            RootResult.Clean
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Read a system property via the hidden API.  Returns null if
     * the property doesn't exist or can't be read.
     */
    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            val value = get.invoke(null, key) as? String
            value?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Sealed class describing root detection outcomes.
 */
sealed class RootResult {
    object Clean : RootResult()
    data class TestKeysDetected(val detail: String) : RootResult()
    data class DebuggableDevice(val detail: String) : RootResult()
    data class InsecureDevice(val detail: String) : RootResult()
    data class SelinuxPermissive(val detail: String) : RootResult()
    data class MagiskDetected(val detail: String) : RootResult()
    data class SuBinaryFound(val detail: String) : RootResult()

    val isRooted: Boolean get() = this !is Clean
}
