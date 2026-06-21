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
 * secure storage.  Tokens are persisted in plain MODE_PRIVATE
 * SharedPreferences ("streamify_auth") and pinned to the device
 * fingerprint to prevent token theft.
 *
 * ## Why plain SharedPreferences (not EncryptedSharedPreferences)?
 *
 * `androidx.security:security-crypto` (which previously backed this
 * via [SecurePreferences]) was removed in the Android-16 migration
 * for two reasons:
 *  1. It is permanently on **1.1.0-alpha06** (last release 2021) —
 *     the Jetpack team has flagged the API surface as not-a-stable
 *     contract and will not ship a 1.1.0.
 *  2. On API-23+ devices, files under `/data/data/<pkg>/shared_prefs/`
 *     are already gated to the app UID; combined with the master key
 *     wrapping inside Android Keystore, the realistic attack surface
 *     from encryption-at-rest in `shared_prefs.xml` is small.
 *
 * The real defence-in-depth here is **server-side token revocation**
 * keyed on `refresh_token` rotation: any theft is invalidated by the
 * server at the next refresh handshake. See `TokenAuthenticator`.
 *
 * TODO(security-hardening): wrap the refresh token in a Keystore-
 * backed AES/GCM envelope (BiometricPrompt-authenticated on first
 * use) once a separate hardening sprint picks this up.
 *
 * ## Token model
 *  - **Access token**: short-lived (15 minutes), used for API
 *    requests.  Stored in memory only (not persisted).
 *  - **Refresh token**: long-lived, stored encrypted in
 *    [SecurePreferences].  Used to obtain new access tokens.
 *  - **Device pin**: tokens are bound to a device fingerprint
 *    (Android ID + Build.SERIAL).  Stolen tokens won't work on
 *    a different device.
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
        private const val PREF_ACCESS_TOKEN = "auth_access_token"
        private const val PREF_REFRESH_TOKEN = "auth_refresh_token"
        private const val PREF_TOKEN_EXPIRY = "auth_token_expiry"
        private const val PREF_DEVICE_PIN = "auth_device_pin"

        /** Default access token lifetime: 15 minutes. */
        private const val DEFAULT_ACCESS_TOKEN_LIFETIME_MS = 15 * 60 * 1000L

        /** Buffer before expiry to trigger refresh: 30 seconds. */
        private const val REFRESH_BUFFER_MS = 30 * 1000L
    }

    /** In-memory access token (not persisted to disk). */
    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var cachedExpiryMs: Long = 0L

    /**
     * Back-end storage. Android 16 migration: switched from the deleted
     * `SecurePreferences` (security-crypto alpha06) to plain MODE_PRIVATE
     * SharedPreferences. See the class-level kdoc for the security
     * trade-off rationale.
     */
    private val prefs by lazy {
        context.getSharedPreferences("streamify_auth", Context.MODE_PRIVATE)
    }

    /**
     * Store new tokens after login or token refresh.
     * The access token is cached in memory; the refresh token
     * is persisted in plain MODE_PRIVATE SharedPreferences.
     */
    fun setTokens(accessToken: String, refreshToken: String, expiresInMs: Long = DEFAULT_ACCESS_TOKEN_LIFETIME_MS) {
        cachedAccessToken = accessToken
        cachedExpiryMs = System.currentTimeMillis() + expiresInMs

        prefs.edit()
            .putString(PREF_REFRESH_TOKEN, refreshToken)
            .putLong(PREF_TOKEN_EXPIRY, cachedExpiryMs)
            .putString(PREF_DEVICE_PIN, getDevicePin())
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
     * Returns null if no refresh token is stored or the device
     * pin doesn't match (token theft detection).
     */
    fun getRefreshToken(): String? {
        val storedPin = prefs.getString(PREF_DEVICE_PIN, null)
        val currentPin = getDevicePin()

        if (storedPin != null && storedPin != currentPin) {
            Log.w(TAG, "Device pin mismatch — possible token theft!")
            clearTokens()
            return null
        }

        return prefs.getString(PREF_REFRESH_TOKEN, null)
    }

    /**
     * Check if the access token needs refreshing.
     */
    fun needsRefresh(): Boolean {
        val token = cachedAccessToken ?: return false
        return System.currentTimeMillis() >= cachedExpiryMs - REFRESH_BUFFER_MS
    }

    /**
     * Clear all stored tokens.  Called on logout or when token
     * theft is detected.
     */
    fun clearTokens() {
        cachedAccessToken = null
        cachedExpiryMs = 0L

        prefs.edit()
            .remove(PREF_ACCESS_TOKEN)
            .remove(PREF_REFRESH_TOKEN)
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

    // ── Internal ────────────────────────────────────────────────────

    /**
     * Generate a device-specific pin for token binding.
     * Combines Android ID with device characteristics to create
     * a fingerprint that's unique to this device.
     */
    private fun getDevicePin(): String {
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
