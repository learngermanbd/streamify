package com.streamify.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Phase 7 · Step 7.8 — EncryptedSharedPreferences wrapper.
 *
 * Provides AES-256-GCM + HMAC encrypted key-value storage backed by
 * the Android Keystore (via [MasterKey]).  Used for storing auth
 * tokens, refresh tokens, and other sensitive user preferences that
 * must survive app restarts.
 *
 * ## Security model
 *  - The master key is hardware-backed (StrongBox / TEE).
 *  - Each value is encrypted with a unique IV.
 *  - HMAC prevents tampering with the encrypted blob on disk.
 *  - The master key never leaves the Keystore; encryption/decryption
 *    happens inside the TEE.
 *
 * ## Usage
 * ```kotlin
 * val prefs = SecurePreferences.getInstance(context)
 * prefs.putString("auth_token", token)
 * val token = prefs.getString("auth_token", null)
 * ```
 */
object SecurePreferences {

    private const val TAG = "SecurePreferences"
    private const val PREFS_FILE = "streamify_secure_prefs"

    @Volatile
    private var instance: SharedPreferences? = null

    /**
     * Get or create the [EncryptedSharedPreferences] instance.
     * Thread-safe via double-checked locking.
     */
    fun getInstance(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: createEncryptedPrefs(context.applicationContext).also {
                instance = it
            }
        }
    }

    /**
     * Clear all encrypted preferences.  Used during logout or
     * when the user's auth session is invalidated.
     */
    fun clear(context: Context) {
        getInstance(context).edit().clear().apply()
        Log.d(TAG, "Encrypted preferences cleared")
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also {
            Log.d(TAG, "EncryptedSharedPreferences initialized ($PREFS_FILE)")
        }
    }
}
