package com.streamify.app.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.streamify.app.R
import com.streamify.app.data.prefs.UpdatePrefs
import com.streamify.app.data.remote.RemoteConfigHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Phase 6 · Step 6.2 — Update coordinator.
 *
 * Responsibilities:
 *  - [checkForUpdate] — full pipeline: read local package version,
 *    fetch [com.streamify.app.data.remote.AppConfig], reduce to
 *    [UpdateDecision], persist any forced-floor the user just saw.
 *  - [startDownload] — hand the APK URL to the platform
 *    [DownloadManager]. The system's DownloadManager continues the
 *    download in its own process; when it completes it fires
 *    `ACTION_DOWNLOAD_COMPLETE` which is picked up by
 *    [com.streamify.app.services.UpdateDownloadReceiver] (registered
 *    in the manifest with `android:exported="true"` per thinker-gemini
 *    point I — not `RECEIVER_NOT_EXPORTED`).
 *
 *  - [shouldShowOptionalNag] — guards the drawer's "Update" entry so an
 *    already-dismissed version doesn't nag twice.
 *
 * Designed to be invoked from a coroutine scope that can survive
 * Activity recreation; StreamifyApp holds a single instance as
 * `app.updateManager`.
 */
class AppUpdateManager(
    private val appContext: Context,
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "AppUpdateManager"
        /** Filename the receiver looks for in [Context.getCacheDir]. */
        const val APK_FILE_NAME = "streamify-update.apk"
    }

    private val updatePrefs by lazy { UpdatePrefs(appContext) }

    /**
     * Full async pipeline — read local version → fetch config →
     * reduce to [UpdateDecision]. CancellationException propagates so
     * `splashTimeout` structured concurrency survives.
     */
    suspend fun checkForUpdate(): UpdateDecision = withContext(Dispatchers.IO) {
        val currentVersion = readLocalVersion()
        val config = try {
            RemoteConfigHelper.fetchConfig(appContext, httpClient = httpClient)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "fetchConfig failed: ${e.message}")
            return@withContext UpdateDecision.Failed(
                reason = e.message ?: e.javaClass.simpleName
            )
        }
        val decision = try {
            UpdateChecker.check(currentVersion, config)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "checker failed: ${e.message}")
            UpdateDecision.Failed(reason = e.message ?: e.javaClass.simpleName)
        }
        if (decision is UpdateDecision.Forced) {
            // Persist asynchronously so the next gate repeats the same floor.
            updatePrefs.rememberForced(decision.minVersion)
        }
        decision
    }

    /**
     * Should the drawer's "Update" entry surface an optional nag this
     * session?  Returns true iff the server reports an [UpdateDecision.Optional]
     * the user has not already dismissed.
     *
     * Suspending because the dismissed-version is behind a DataStore Flow.
     */
    suspend fun shouldShowOptionalNag(): UpdateDecision.Optional? = withContext(Dispatchers.IO) {
        val decision = checkForUpdate()
        if (decision !is UpdateDecision.Optional) return@withContext null
        val dismissed = updatePrefs.readDismissedLatest()
        if (dismissed == decision.latestVersion) return@withContext null
        decision
    }

    /**
     * Enqueue the APK with the platform DownloadManager. The download
     * ID is returned so callers (none currently — the receiver matches
     * any download completing in our cache dir) could pin to a row.
     */
    fun startDownload(apkUrl: String): Long {
        if (apkUrl.isBlank()) {
            Log.w(TAG, "startDownload skipped: empty url")
            return -1L
        }
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(appContext.getString(R.string.update_notification_title))
            .setDescription(appContext.getString(R.string.update_notification_description))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            // No `setDestinationInPublicDownloads` — we want the file
            // in our own cacheDir so the FileProvider authority is ours.
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                APK_FILE_NAME
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        return try {
            dm.enqueue(request)
        } catch (e: Exception) {
            Log.w(TAG, "DownloadManager.enqueue failed: ${e.message}")
            -1L
        }
    }

    /**
     * The on-disk path the receiver will read. We download into
     * `getExternalFilesDir(DOWNLOADS)/APK_FILE_NAME` so FileProvider's
     * `<external-files-path>` declared in `res/xml/file_paths.xml`
     * grants the right permission scope.
     */
    fun downloadedApkFile(): java.io.File {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: appContext.cacheDir
        return java.io.File(dir, APK_FILE_NAME)
    }

    private fun readLocalVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0.0.0"
    } catch (_: NameNotFoundException) {
        "0.0.0"
    } catch (e: Throwable) {
        Log.w(TAG, "readLocalVersion failed: ${e.message}")
        "0.0.0"
    }
}
