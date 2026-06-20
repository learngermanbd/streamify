package com.streamify.app.data.update

/**
 * Phase 6 · Step 6.2 — Update check decision aggregate.
 *
 * Returned by [UpdateChecker.check] after comparing the local
 * [android.content.pm.PackageInfo.versionName] against
 * [com.streamify.app.data.remote.AppConfig.minAppVersion] and
 * [com.streamify.app.data.remote.AppConfig.latestVersion].
 *
 *  - [UpToDate]   — server version matches local version, do nothing.
 *  - [Optional]   — newer version available but not required; the
 *                   Drawer's "Update" entry surfaces this state via a
 *                   BottomSheetDialogFragment with "Update Now" +
 *                   "Later" choices.
 *  - [Forced]     — local version is below the server's
 *                   [com.streamify.app.data.remote.AppConfig.minAppVersion];
 *                   the splash gate routes to [UpdateActivity] which
 *                   blocks back-button navigation.
 *  - [Failed]     — transient failure (network down OR
 *                   `/api/config` JSON malformed). The app continues
 *                   into MainActivity as normal — a soft nag is NOT
 *                   shown to avoid confusing offline users.
 */
sealed class UpdateDecision {
    /** Local version matches latest; show nothing. */
    object UpToDate : UpdateDecision()

    /**
     * Newer optional version is available.
     *
     * @property latestVersion  the server-reported "latest version" the
     *                           user could move to if they tap "Update Now".
     * @property changelog      server-supplied release notes; empty
     *                           means the activity falls back to a
     *                           default "improvements + bugfixes" line.
     * @property apkUrl         absolute URL to the APK the
     *                           [com.streamify.app.data.update.AppUpdateManager]
     *                           will hand to DownloadManager.
     */
    data class Optional(
        val latestVersion: String,
        val changelog: String,
        val apkUrl: String
    ) : UpdateDecision()

    /**
     * Local version is below the server floor; the app must NOT
     * proceed until the user upgrades.
     *
     * @property minVersion  the minimum version the server will accept.
     * @property apkUrl      APK the user is forced to install.
     */
    data class Forced(
        val minVersion: String,
        val apkUrl: String
    ) : UpdateDecision()

    /** Soft-fail — local network down or /api/config malformed. */
    data class Failed(val reason: String) : UpdateDecision()
}
