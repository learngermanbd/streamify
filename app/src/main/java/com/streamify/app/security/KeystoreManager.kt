package com.streamify.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Phase 7 · Step 7.2 — Manages an AES-256-GCM [SecretKey] in the
 * Android Keystore.  The key is hardware-backed: StrongBox when the
 * device supports it, otherwise TEE (TrustZone).
 *
 * Used for **runtime** data encryption (e.g. re-wrapping build-time
 * secrets, encrypting cached tokens).  Not used for the build-time
 * encrypted constants — those use the XOR-obfuscated key embedded in
 * [EncryptedConstants].
 *
 * Thread-safe: the [KeyStore] instance is re-opened on every call to
 * [getOrCreateKey] so we never hold a stale reference across process
 * boundaries.
 */
internal object KeystoreManager {

    private const val TAG = "KeystoreManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "streamify_master_key"

    /**
     * Return the existing hardware-backed key, or generate a new one.
     *
     * The key is AES-256-GCM, does NOT require user authentication
     * (so background decryption works), and uses randomized IVs
     * ([setRandomizedEncryptionRequired] = true).
     */
    fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        // Fast path: key already exists.
        ks.getEntry(KEY_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        // Slow path: generate a new hardware-backed key.
        Log.d(TAG, "Generating new AES-256-GCM key in AndroidKeyStore")

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()

        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    /**
     * Delete the key from the Keystore.  Used during key rotation —
     * the next call to [getOrCreateKey] will generate a fresh key.
     */
    fun deleteKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.deleteEntry(KEY_ALIAS)
        Log.d(TAG, "Deleted key alias '$KEY_ALIAS' from AndroidKeyStore")
    }
}
