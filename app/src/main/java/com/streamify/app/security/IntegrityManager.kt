package com.streamify.app.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 7 · Step 7.10 — Google Play Integrity API wrapper.
 *
 * Manages Play Integrity token requests with session caching and
 * re-verification for sensitive operations.
 *
 * ## Flow
 *  1. Generate a cryptographically random nonce.
 *  2. Request an integrity token from Google Play.
 *  3. Send the token to our backend for verification (Phase 8).
 *  4. Backend decrypts the token using Google's server key and
 *     returns the verdict (device/app/account integrity).
 *  5. Cache the verdict result for the current session.
 *
 * ## Usage
 * ```kotlin
 * val manager = PlayIntegrityManager(context)
 * manager.requestToken { result ->
 *     when (result) {
 *         is IntegrityTokenResult.Success -> // send token.token to backend
 *         is IntegrityTokenResult.Error -> // handle error
 *     }
 * }
 * ```
 *
 * ## Quota considerations
 * Play Integrity has daily quotas.  We request tokens only:
 *  - On cold start (once per process)
 *  - Before sensitive operations (streaming, premium features)
 *  - When the cached result is stale (> 30 minutes)
 */
object PlayIntegrityManager {

    private const val TAG = "PlayIntegrityMgr"

    /** Nonce size in bytes (16 bytes = 128 bits of entropy). */
    private const val NONCE_BYTES = 16

    /**
     * Cached integrity result.  Re-request if stale.
     * Null means no check has been performed yet this session.
     */
    private val cachedResult = AtomicReference<IntegrityVerdict?>(null)

    /** Timestamp of the last successful integrity check. */
    @Volatile
    private var lastCheckTimestamp = 0L

    /** How long a cached result is valid (30 minutes). */
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    /**
     * Whether a token request is currently in flight.
     * Prevents multiple concurrent requests.
     */
    @Volatile
    private var isRequesting = false

    /**
     * Request a Play Integrity token from Google.
     *
     * @param context Application context.
     * @param cloudProjectNumber The Google Cloud project number
     *   (from google-services.json or Play Console). Pass 0 to use
     *   the default associated with the app.
     * @param callback Invoked with the result on the calling thread.
     */
    fun requestToken(
        context: Context,
        cloudProjectNumber: Long = 0L,
        callback: (IntegrityTokenResult) -> Unit
    ) {
        // Return cached result if still valid
        val cached = cachedResult.get()
        val now = System.currentTimeMillis()
        if (cached != null && now - lastCheckTimestamp < CACHE_TTL_MS) {
            Log.d(TAG, "Returning cached integrity result (age: ${(now - lastCheckTimestamp) / 1000}s)")
            callback(IntegrityTokenResult.Success(cached))
            return
        }

        if (isRequesting) {
            Log.d(TAG, "Request already in flight — skipping")
            callback(IntegrityTokenResult.Error("Request in progress"))
            return
        }

        isRequesting = true

        try {
            val integrityManager = IntegrityManagerFactory.create(context.applicationContext)

            // Generate a fresh nonce
            val nonceBytes = ByteArray(NONCE_BYTES)
            SecureRandom().nextBytes(nonceBytes)
            val nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP or Base64.URL_SAFE)

            val requestBuilder = IntegrityTokenRequest.builder()
                .setNonce(nonce)

            // Set cloud project number if provided
            if (cloudProjectNumber > 0) {
                requestBuilder.setCloudProjectNumber(cloudProjectNumber)
            }

            integrityManager.requestIntegrityToken(requestBuilder.build())
                .addOnSuccessListener { response ->
                    isRequesting = false
                    val token = response.token()
                    Log.i(TAG, "Integrity token received (${token.length} chars)")

                    // Parse the token locally for immediate threat assessment.
                    // Server-side verification (Phase 8) provides authoritative result.
                    val verdict = IntegrityVerdict.fromToken(token)
                    cachedResult.set(verdict)
                    lastCheckTimestamp = System.currentTimeMillis()

                    callback(IntegrityTokenResult.Success(verdict))
                }
                .addOnFailureListener { exception ->
                    isRequesting = false
                    Log.e(TAG, "Integrity token request failed: ${exception.message}")
                    callback(IntegrityTokenResult.Error(
                        exception.message ?: "Unknown error"
                    ))
                }
        } catch (e: Exception) {
            isRequesting = false
            Log.e(TAG, "Failed to create integrity manager: ${e.message}")
            callback(IntegrityTokenResult.Error(e.message ?: "Init failed"))
        }
    }

    /**
     * Force-invalidate the cached result, causing the next
     * [requestToken] call to fetch a fresh token.
     */
    fun invalidateCache() {
        cachedResult.set(null)
        lastCheckTimestamp = 0L
        Log.d(TAG, "Integrity cache invalidated")
    }

    /**
     * Get the cached verdict, if available.
     */
    fun getCachedVerdict(): IntegrityVerdict? {
        val cached = cachedResult.get()
        val now = System.currentTimeMillis()
        return if (cached != null && now - lastCheckTimestamp < CACHE_TTL_MS) cached else null
    }

    /**
     * Check if the cached result indicates the device is trustworthy.
     * Returns `null` if no cached result is available.
     */
    fun isDeviceTrusted(): Boolean? {
        val verdict = getCachedVerdict() ?: return null
        return verdict.meetsDeviceIntegrity
    }
}

/**
 * Result of an integrity token request.
 */
sealed class IntegrityTokenResult {
    /** Token received successfully.  Contains the parsed verdict. */
    data class Success(val verdict: IntegrityVerdict) : IntegrityTokenResult()
    /** Token request failed. */
    data class Error(val message: String) : IntegrityTokenResult()
}
