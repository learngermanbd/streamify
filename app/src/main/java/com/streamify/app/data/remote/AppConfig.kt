package com.streamify.app.data.remote

import com.streamify.app.security.RuntimeStringProvider

/**
 * Payload returned by `GET /api/config` on the SportStream admin backend.
 *
 * Phase 1 · Step 1.4. The real backend endpoint is implemented in Phase 8 · Step 8.2.
 * Until then, RemoteConfigHelper hits a placeholder URL and falls back to [defaults].
 *
 * Phase 6 · Step 6.2 — added [latestVersion]. The server emits TWO independent
 * version fields so the client can distinguish:
 *  - [minAppVersion] — the FLOOR; below this triggers a forced update gate on
 *    SplashActivity.
 *  - [latestVersion] — the LATEST version the admin uploaded; below this (but
 *    at-or-above the floor) triggers an OPTIONAL update nag via the drawer's
 *    Update entry.
 *
 * When the server omits `latestVersion`, [RemoteConfigHelper.fetchConfig]
 * defaults it to [minAppVersion] so the older payloads from Phase 1.4 are
 * still handled correctly.
 */
data class AppConfig(
    /** Base URL for the content API. e.g. `https://api.example.com/api` */
    val apiBaseUrl: String,
    /** URL the same-version-check redirects to for app updates. */
    val updateUrl: String,
    /**
     * Newest version the admin uploaded. Below this but at-or-above
     * [minAppVersion] drives an OPTIONAL update; missing from older
     * payloads is treated as "same as minAppVersion".
     */
    val latestVersion: String,
    /** Telegram channel link surfaced in the drawer's "Join Us" entry. */
    val telegramLink: String,
    /** Free-form banner notice text shown on app splash / home top bar. */
    val noticeText: String,
    /** When true, the app shows a blocking maintenance screen. */
    val maintenanceMode: Boolean,
    /** Minimum app version that may connect. Older clients get HTTP 426. */
    val minAppVersion: String,
    /** Experimental feature toggles (e.g. "premium_enabled", "chat_enabled"). */
    val featureFlags: Map<String, Boolean>
) {
    companion object {
        /**
         * Startup fallback used when /api/config cannot be fetched AND no cache exists.
         * All URLs point at the placeholder domain; the app surfaces a network-error
         * card (Step 3.1) instead of silently using offline defaults.
         */
        // Phase 7 · Step 7.2 — URLs decrypted from build-time encrypted
        // constants via RuntimeStringProvider.  The plaintext never
        // appears in the compiled APK; only the AES-GCM ciphertext +
        // obfuscated key are present.
        fun defaults() = AppConfig(
            apiBaseUrl      = RuntimeStringProvider.getString("API_BASE_URL"),
            updateUrl       = RuntimeStringProvider.getString("UPDATE_URL"),
            latestVersion   = "1.0.0",
            telegramLink    = RuntimeStringProvider.getString("TELEGRAM_LINK"),
            noticeText      = "",
            maintenanceMode = false,
            minAppVersion   = "1.0.0",
            featureFlags    = emptyMap()
        )
    }
}
