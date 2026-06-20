package com.streamify.app.security

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 7 · Step 7.2 — Decrypts build-time encrypted constants on
 * demand and caches the result as [CharArray] (never [String], to
 * avoid the JVM intern pool).
 *
 * ## Usage
 * ```kotlin
 * // Prefer CharArray for short-lived secrets:
 * val url = RuntimeStringProvider.get("API_BASE_URL")
 * httpClient.useEncryptedUrl(url)
 * // url can be wiped when no longer needed.
 *
 * // Convenience for APIs that require String:
 * val url = RuntimeStringProvider.getString("API_BASE_URL")
 * ```
 *
 * ## Lifecycle
 * Call [clearCache] from [android.content.ComponentCallbacks2.onTrimMemory]
 * when the app enters the background.  This minimises the window where
 * decrypted values sit in heap memory.  The cache is re-populated lazily
 * on next access.
 *
 * ## Thread safety
 * Backed by [ConcurrentHashMap].  Decrypt-once semantics: concurrent
 * readers for the same key will block only until the first decryption
 * completes (standard [ConcurrentHashMap.getOrPut] behaviour).
 */
object RuntimeStringProvider {

    private const val TAG = "RuntimeStringProvider"

    private val cache = ConcurrentHashMap<String, CharArray>()

    /**
     * Get a decrypted secret by key name.  Returns a cached [CharArray]
     * if available; otherwise decrypts from [EncryptedConstants] and
     * stores the result.
     *
     * @param key  One of the keys defined in [EncryptedConstants.ENTRIES]
     *             (e.g. "API_BASE_URL", "SENTRY_DSN").
     * @throws IllegalArgumentException if [key] is not in [EncryptedConstants.ENTRIES].
     */
    fun get(key: String): CharArray {
        // Use computeIfAbsent (atomic) instead of getOrPut (non-atomic)
        // to avoid leaking unwiped CharArrays under thread contention.
        return cache.computeIfAbsent(key) {
            val entry = EncryptedConstants.ENTRIES[key]
                ?: throw IllegalArgumentException("Unknown encrypted constant: $key")
            StringEncryptor.decryptBuildTime(entry.iv, entry.ct)
        }
    }

    /**
     * Convenience: returns the decrypted value as a [String].
     *
     * **Prefer [get] with [CharArray]** for better secret hygiene —
     * Strings are immutable and linger in the intern pool until GC.
     * Use this only when calling APIs that require [String].
     */
    fun getString(key: String): String {
        return get(key).concatToString()
    }

    /**
     * Wipe all cached decrypted values and release memory.  Safe to
     * call from any thread.  The next call to [get] will re-decrypt
     * lazily.
     */
    fun clearCache() {
        cache.values.forEach { StringEncryptor.wipe(it) }
        cache.clear()
        Log.d(TAG, "Decrypted-value cache cleared (${cache.size} entries)")
    }

    /** Number of currently cached entries.  Exposed for testing. */
    fun cacheSize(): Int = cache.size
}
