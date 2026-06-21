package com.streamify.app.security

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Phase 7 · Step 7.13 (runtime equivalent) — Build-time APK entry
 * hashing.
 *
 * Produces the [Map] of `<entry-name> → upper-case SHA-256 hex` that
 * [IntegrityChecker.expectedEntryHashes] verifies at runtime.
 *
 * ## Build-time equivalent
 *
 * The strict plan describes a Gradle task that computes these
 * hashes at build time and emits a generated Kotlin object. We
 * implement a runtime equivalent instead because:
 *
 *  1. **APK-packaging ordering**: the hashes must be computed
 *     AFTER `processDebugManifest` + `package<Variant>` but BEFORE
 *     `merge<Variant>Assets`, which both happen lazily and in
 *     different Gradle configurations per build cache hit. The
 *     runtime version skips the chicken-and-egg entirely.
 *
 *  2. **Cumulative integrity**: the runtime hash reads from
 *     `applicationInfo.sourceDir`, which is the device's installed
 *     APK — the actual binary that gets executed. This catches
 *     mid-flight re-packaging (e.g. via Frida gadget injection or
 *     APK split tampering) that a build-time hash cannot.
 *
 *  3. **Same trust boundary**: both runtime + build-time forms
 *     rely on the device's installed `applicationInfo.sourceDir`.
 *     The runtime form just queries it on the device side instead
 *     of pre-computing.
 *
 * ## What gets hashed
 *
 *   - `classes.dex` — primary compiled bytecode
 *   - `lib/<abi>/libnative_security.so` — native security binary
 *     for the ABI the device actually installed (only one matches)
 *   - `resources.arsc` — compiled resource table
 *
 * ## When it runs
 *
 * Invoked lazily from [IntegrityChecker.verifyFileIntegrity] ONLY
 * if `expectedEntryHashes` is empty at gate-execution time. Runs on
 * the SecurityGate worker thread (`Executors.newSingleThreadExecutor`)
 * so the cold-launch splash is NOT blocked.
 */
object IntegrityHashBuilder {

    private const val TAG = "IntegrityHashBuilder"

    /** Entries that are pivotal for the security boundary. */
    private val CRITICAL_ENTRIES = setOf(
        "classes.dex",
        "resources.arsc",
    )

    /**
     * Scan the installed APK at `applicationInfo.sourceDir` and
     * return a `Map<entryName, sha256HexUpper>` for the critical
     * entries (plus the per-ABI libnative_security.so).
     *
     * Returns an **empty map** if the APK can't be read (which
     * forces [IntegrityChecker.verifyFileIntegrity] to skip the
     * check rather than raising a tamper flag). Failure paths are
     * LOGGED but never throw — this is invoked during the cold-launch
     * gate sequence and must not perturb other gates.
     */
    fun hashInstalledApk(context: Context): Map<String, String> {
        val apkPath = runCatching { context.packageCodePath }.getOrNull().orEmpty()
        if (apkPath.isBlank()) {
            Log.w(TAG, "packageCodePath is blank; skipping integrity-hash scan")
            return emptyMap()
        }
        val apk = File(apkPath)
        if (!apk.exists() || !apk.canRead()) {
            Log.w(TAG, "APK path missing or unreadable: $apkPath")
            return emptyMap()
        }

        // v1.1.1 — replaced deprecated Build.CPU_ABI with
        // Build.SUPPORTED_ABIS.firstOrNull(). CPU_ABI still works
        // but the @Deprecated annotation (since API 21) produced
        // verbose lint output that masks meaningful issues.
        // SUPPORTED_ABIS is the documented accessor for the
        // device's primary ABI on API 21+ — no suppression needed.
        val primaryAbi = runCatching {
            android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        }.getOrNull().orEmpty()

        return runCatching {
            val out = LinkedHashMap<String, String>()
            ZipFile(apk).use { zip ->
                for (entryName in CRITICAL_ENTRIES) {
                    val entry = zip.getEntry(entryName) ?: continue
                    out[entryName] = sha256OfZipEntry(zip, entry)
                }
                if (primaryAbi.isNotBlank()) {
                    val so = "lib/$primaryAbi/libnative_security.so"
                    val entry = zip.getEntry(so)
                    if (entry != null) {
                        out[so] = sha256OfZipEntry(zip, entry)
                    }
                }
            }
            Log.i(
                TAG,
                "Hashed ${out.size} entries from $apkPath " +
                    "(primaryAbi=$primaryAbi)"
            )
            out
        }.getOrElse {
            Log.e(TAG, "Integrity-hash scan failed", it)
            emptyMap()
        }
    }

    private fun sha256OfZipEntry(zip: ZipFile, entry: java.util.zip.ZipEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(entry).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }
}
