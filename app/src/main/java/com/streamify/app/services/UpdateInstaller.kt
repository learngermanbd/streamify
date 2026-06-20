package com.streamify.app.services

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Phase 6 · Step 6.2 — APK install helper.
 *
 * Builds an `ACTION_VIEW` intent for `application/vnd.android.package-archive`
 * sourced from the `<files-path>` declared in
 * [com.streamify.app.R.xml.file_paths].
 *
 * Permissions:
 *  - `FLAG_GRANT_READ_URI_PERMISSION` so the PackageInstaller can read the
 *    APK bytes via the FileProvider URI.
 *  - `FLAG_ACTIVITY_NEW_TASK` + `FLAG_ACTIVITY_CLEAR_TASK` so the install
 *    intent launches cleanly from a non-Activity context (the receiver's
 *    `applicationContext`).
 *
 * On API 30+, even with `FLAG_GRANT_READ_URI_PERMISSION` we explicitly
 * grant the URI to every matching installer activity so OEMs that
 * sandbox `Intent.FLAG_GRANT_READ_URI_PERMISSION` still see the read bit.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"

    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Log.w(TAG, "installApk skipped: file missing at ${apkFile.absolutePath}")
            return
        }
        val authority = "${context.packageName}.fileprovider"
        val uri = try {
            FileProvider.getUriForFile(context, authority, apkFile)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "FileProvider authority mismatch for $apkFile", e)
            return
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider.getUriForFile failed", e)
            return
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            // Use CLEAR_TOP so the install flow can return to MainActivity on
            // API 26..29 even if some OEM skins treat the package installer
            // as a regular task root.
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }

        // Resolve installers first so PackageManager surfaces them in
        // resolveActivity checks for API 30+ Package Visibility.
        val installers = try {
            context.packageManager.queryIntentActivities(viewIntent, 0)
        } catch (e: Exception) {
            Log.w(TAG, "queryIntentActivities failed", e)
            emptyList()
        }

        if (installers.isEmpty()) {
            Log.w(TAG, "No installer activity found for $uri")
            return
        }

        // Explicit grant helps on Android 11+ where the FLAG alone is
        // occasionally dropped by OEM-skinned PackageManager.
        // Phase 6 · Step 6.2 review-pass MAJOR: wrap each call in
        // runCatching because grantUriPermission can throw
        // SecurityException on API 30+ when the context's authority
        // chain doesn't match the URI's authority (forward-compat
        // hazard if anyone reuses this object with a sub-component
        // context later).
        installers.forEach { ri ->
            ri.activityInfo.packageName?.let { pkg ->
                runCatching {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.onFailure { e ->
                    Log.w(TAG, "grantUriPermission($pkg) failed: ${e.message}")
                }
            }
        }

        try {
            context.startActivity(viewIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "startActivity(ACTION_VIEW) failed; installer missing?", e)
        } catch (e: Exception) {
            Log.e(TAG, "startActivity failed", e)
        }
    }
}
