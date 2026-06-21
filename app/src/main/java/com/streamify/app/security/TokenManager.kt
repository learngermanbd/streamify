package com.streamify.app.security

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * Phase 7 · Step 7.8 — Token lifecycle manager.
 *
 * Manages short-lived access tokens with automatic rotation and
 * secure storage. Tokens are persisted through [TokenStore] which
 * stores the refresh token as an AES-256-GCM ciphertext inside the
 * AndroidKeyStore (v1.1.1 hardening).
 *
 * ## v1.1.1 hardening — refresh-token storage moved to Keystore
 *
 * As of v1.1.1 the refresh token is wrapped by **Option (A)** of
 * `sportzfy_build_plan.html` Step 7.8:
 *
 *  - **Cipher**: AES-256-GCM in the `AndroidKeyStore` (hardware-backed
 *    on devices with StrongBox / TEE).
 *  - **AAD**: device fingerprint + schema version, so a wrapped blob
 *    copied to a different device fails GCM auth at decrypt time.
 *  - **No biometric gating**: `setUserAuthenticationRequired(false)`
 *    so background refresh / cold-launch reads aren't blocked by an
 *    unreachable BiometricPrompt.
 *  - **Migration**: existing plain-text `auth_refresh_token` entries
 *    (writes from BEFORE v1.1.1) are migrated to the wrapped slot
 *    on first read by [TokenStore.load].
 *
 * Access tokens remain in memory only.
 *
 * ## Token model
 *  - **Access token**: short-lived (15 minutes), used for API
 *    requests. Stored in memory only (not persisted).
 *  - **Refresh token**: long-lived, stored encrypted in [TokenStore].
 *    Used to obtain new access tokens.
 *  - **Device pin**: tokens are bound to a device fingerprint
 *    (Android ID + Build.FINGERPRINT). Stolen tokens won't work
 *    on a different device — GCM auth fails on decrypt.
 *
 * ## Usage
 * ```kotlin
 * val manager = TokenManager(context)
 * manager.setTokens(accessToken, refreshToken)
 * val token = manager.getAccessToken() // null if expired
 * ```
 */
class TokenManager(private val context: Context) {

    companion object {
        private const val TAG = "TokenManager"

        @Deprecated("Legacy plain-text slot. v1.1.1+ writes go to TokenStore; reads return null.")
        private const val PREF_ACCESS_TOKEN = "auth_access_token"

        /**
         * Public constant used by [TokenStore] to read legacy
         * plain-text refresh tokens (writes performed before
         * v1.1.1 landed). Kept at the same key name (no migration
         * touching the user) so device pin checks remain valid.
         */
        const val LEGACY_PREF_REFRESH_TOKEN = "auth_refresh_token"

        private const val PREF_TOKEN_EXPIRY = "auth_token_expiry"
        private const val PREF_DEVICE_PIN = "auth_device_pin"

        /** Default access token lifetime: 15 minutes. */
        private const val DEFAULT_ACCESS_TOKEN_LIFETIME_MS = 15 * 60 * 1000L

        /** Buffer before expiry to trigger refresh: 30 seconds. */
        private const val REFRESH_BUFFER_MS = 30 * 1000L

        /**
         * Same Android-ID + Build fingerprint hash that
         * [TokenStore] consumes as AES/GCM Additional
         * Authentication Data. Exposed package-private so
         * [TokenStore.aad] can re-derive the AAD byte-by-byte on
         * every wrap / unwrap (cheap; cached inside TokenStore).
         *
         * Pure function — no Context survival needed, owned by the
         * caller. Callers MUST pass `applicationContext` to avoid
         * leaking an Activity. We keep this signature static so
         * unit tests can verify the AAD contract end-to-end without
         * booting Keystore.
         */
        @JvmStatic
        fun devicePin(context: Context): String {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"

            val deviceInfo = "${Build.FINGERPRINT}|${Build.BOARD}|${Build.HARDWARE}"
            val combined = "$androidId:$deviceInfo"

            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(combined.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(hash, Base64.NO_WRAP)
        }
    }

    /** In-memory access token (not persisted to disk). */
    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var cachedExpiryMs: Long = 0L

    /**
     * Back-end storage. v1.1.1 hardening: refresh tokens now live
     * in [TokenStore] (AES/GCM in AndroidKeyStore). The
     * `streamify_auth` SharedPreferences file is now used ONLY for
     * access-token expiry + device-pin fingerprint metadata.
     *
     * The plain-text refresh-token slot
     * ([LEGACY_PREF_REFRESH_TOKEN]) is read **only** by
     * [TokenStore.load] during the transparent migration path;
     * writes go through [TokenStore.store] exclusively.
     */
    private val prefs by lazy {
        context.getSharedPreferences(TokenStore.PREFS_FILE, Context.MODE_PRIVATE)
    }

    /**
     * Lazy reference to the Keystore-backed refresh-token store.
     * Constructed once per process; [TokenStore.initFromContext] is
     * idempotent and reuses the existing instance on repeat calls.
     */
    private val tokenStore: TokenStore by lazy {
        TokenStore.initFromContext(context.applicationContext)
    }

    /**
     * Store new tokens after login or token refresh.
     * The access token is cached in memory; the refresh token
     * is wrapped by [TokenStore] and persisted in
     * the AndroidKeyStore-backed envelope.
     *
     * v1.1.1 hardening: failures from `tokenStore.store` are no
     * longer silently swallowed (review issue #3). They are
     * logged with cause + re-thrown so StreamifyApp's CrashHandler
     * routes the cause to Sentry. Silent swallowing would leave the
     * user logged out on next launch with no traceback tying the
     * cause to a Keystore exception.
     */
    fun setTokens(accessToken: String, refreshToken: String, expiresInMs: Long = DEFAULT_ACCESS_TOKEN_LIFETIME_MS) {
        cachedAccessToken = accessToken
        cachedExpiryMs = System.currentTimeMillis() + expiresInMs

        try {
            tokenStore.store(refreshToken)
            Log.d(TAG, "TokenStore.store succeeded (Keystore wrap committed)")
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "TokenStore.store failed: refresh token NOT PERSISTED across restart. " +
                    "cause=${t.javaClass.simpleName} ${t.message}",
                t
            )
            throw t
        }

        // Expiry + device-pin fingerprint metadata stays in prefs.
        prefs.edit()
            .putLong(PREF_TOKEN_EXPIRY, cachedExpiryMs)
            .putString(PREF_DEVICE_PIN, devicePin(context))
            .apply()

        Log.d(TAG, "Tokens stored (access expires in ${expiresInMs / 1000}s)")
    }

    /**
     * Get the current access token if it's valid.
     * Returns null if the token is expired or doesn't exist.
     */
    fun getAccessToken(): String? {
        val token = cachedAccessToken ?: return null
        val now = System.currentTimeMillis()

        if (now >= cachedExpiryMs - REFRESH_BUFFER_MS) {
            Log.d(TAG, "Access token expired or expiring soon")
            cachedAccessToken = null
            return null
        }

        return token
    }

    /**
     * Get the stored refresh token.
     * Returns null if no refresh token is stored, the device pin
     * doesn't match (token theft detection), or the Keystore-wrapped
     * blob is corrupt / cross-device-cloned.
     */
    fun getRefreshToken(): String? {
        val storedPin = prefs.getString(PREF_DEVICE_PIN, null)
        val currentPin = devicePin(context)

        if (storedPin != null && storedPin != currentPin) {
            Log.w(TAG, "Device pin mismatch — possible token theft!")
            clearTokens()
            return null
        }

        return runCatching { tokenStore.load() }
            .onFailure { Log.w(TAG, "TokenStore.load failed: ${it.message}") }
            .getOrNull()
    }

    /**
     * Check if the access token needs refreshing.
     */
    fun needsRefresh(): Boolean {
        val token = cachedAccessToken ?: return false
        return System.currentTimeMillis() >= cachedExpiryMs - REFRESH_BUFFER_MS
    }

    /**
     * Clear all stored tokens. Called on logout or when token
     * theft is detected.
     */
    fun clearTokens() {
        cachedAccessToken = null
        cachedExpiryMs = 0L

        runCatching { tokenStore.clear() }
            .onFailure { Log.w(TAG, "TokenStore.clear failed: ${it.message}") }

        prefs.edit()
            .remove(PREF_ACCESS_TOKEN)
            .remove(LEGACY_PREF_REFRESH_TOKEN)
            .remove(PREF_TOKEN_EXPIRY)
            .remove(PREF_DEVICE_PIN)
            .apply()

        Log.d(TAG, "All tokens cleared")
    }

    /**
     * Check if the user is logged in (has a valid refresh token).
     */
    fun isLoggedIn(): Boolean {
        return getRefreshToken() != null
    }

    /**
     * Convenience accessor exposing the wrapped refresh-token
     * store to consumers that need direct access. Prefer
     * [getRefreshToken] for ordinary reads.
     */
    val wrappedStore: TokenStore
        get() = tokenStore
}
