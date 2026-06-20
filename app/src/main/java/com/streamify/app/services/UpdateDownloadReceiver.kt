package com.streamify.app.services

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.streamify.app.data.update.AppUpdateManager

/**
 * Phase 6 · Step 6.2 — Download completion listener.
 *
 * Registered in the manifest with `android:exported="true"` (thinker-gemini
 * point I: using `RECEIVER_NOT_EXPORTED` on Android 14+ blocks system
 * broadcasts from external apps like DownloadManager).
 *
 * We match any `DOWNLOAD_COMPLETE` broadcast — the platform DownloadManager
 * is single-tenant (one download at a time), and our
 * [com.streamify.app.data.update.AppUpdateManager] downloads the APK
 * into our own scoped `getExternalFilesDir(DOWNLOADS)` so the file path
 * we read here is unambiguous.
 *
 * Per thinker-gemini point J (Play Protect UX): if the user had to flip
 * the "Allow from this source" toggle, the original install intent is
 * killed and won't auto-resume. We re-issue the intent here; if the user
 * still hasn't granted unknown-source permission they see a system dialog
 * one more time pointing to the file instead of the silenced intent.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return
        Log.i("UpdateDownloadReceiver", "download id=$id completed; sizing file")

        // Phase 6 · Step 6.2 review-pass MAJOR: previously we used a
        // runCatching { ... as? StreamifyApp } pattern that silently
        // turned a ClassCastException into `app = null` on multi-process
        // cold start (where the receiver fires before our Application
        // class is initialised). Re-anchor to a strict cast + explicit
        // ClassCastException guard so we still log instead of dropping.
        val app = try {
            context.applicationContext as com.streamify.app.StreamifyApp
        } catch (e: ClassCastException) {
            Log.w(
                "UpdateDownloadReceiver",
                "Application not yet a StreamifyApp; skipping update trigger"
            )
            return
        }
        val apkFile = app.updateManager.downloadedApkFile()
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.w(
                "UpdateDownloadReceiver",
                "APK file missing or empty at ${apkFile.absolutePath}"
            )
            return
        }
        UpdateInstaller.installApk(context, apkFile)
    }
}
