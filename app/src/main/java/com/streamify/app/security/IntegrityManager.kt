package com.streamify.app.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Phase 7 · Step 7.10 — Google Play Integrity API wrapper.
 *
 * Manages Play Integrity token requests with session caching and
 * re-verification for sensitive operations.
 *
 * ## v1.1.1 hardening
 *  - **4-second hard timeout** wrapping the integrity-task graph
 *    (the Play Integrity API exposes no deadline and historically
 *    can stall ~30 s on flaky networks — a cold-launch block on
 *    a splash screen is unacceptable).
 *  - **GMS-missing graceful degrade** — if
 *    `GoogleApiAvailability.isGooglePlayServicesAvailable` returns
 *    anything other than SUCCESS (Huawei devices, AOSP Xiaomi
 *    builds, adb-only test images, …), we return an INCONCLUSIVE
 *    verdict (gmsAvailable=false) instead of throwing `Error`.
 *    The gate does NOT score INCONCLUSIVE; the user keeps
 *    full-functionality.
 *  - **Tri-state verdictState propagation** — the cached
 *    [IntegrityVerdict] now carries an explicit
 *    `verdictState` so [com.streamify.app.security.SecurityGate]
 *    can distinguish CELSEPARATE-CLEAN from INCONCLUSIVE in Sentry
 *    breadcrumbs (without affecting the local risk score).
 *
 * ## Flow
 *  1. Probe `GoogleApiAvailability.isGooglePlayServicesAvailable`.
 *     Not-SUCCESS → return INCONCLUSIVE + cache immediately.
 *  2. Generate a fresh 128-bit nonce.
 *  3. Request an integrity token from Google Play under a 4-second
 *     `withTimeoutOrNull` ceiling.
 *     Timeout → INCONCLUSIVE + cache.
 *  4. Parse the JWS payload locally for immediate threat assessment;
 *     authoritative server-side verification is Phase 8 (Step 7.10 v2).
 *  5. Cache the verdict result for the current session (30 minutes TTL).
 *
 * ## Quota considerations
 * Play Integrity has daily quotas. We request tokens only:
 *  - On cold start (once per process)
 *  - Before sensitive operations (streaming, premium features)
 *  - When the cached result is stale (> 30 minutes)
 *
 * ## Usage
 * ```kotlin
 * PlayIntegrityManager.requestToken(context) { result ->
 *     when (result) {
 *         is IntegrityTokenResult.Success -> // result.verdict.meetsMinimumRequirements()
 *         is IntegrityTokenResult.Error -> // handle error
 *     }
 * }
 * ```
 */
object PlayIntegrityManager {

    private const val TAG = "PlayIntegrityMgr"

    /** Nonce size in bytes (16 bytes = 128 bits of entropy). */
    private const val NONCE_BYTES = 16

    /**
     * 4-second hard ceiling. The Play Integrity API exposes no
     * deadline. We cannot let any cold-launch gate poll block
     * the splash for 30+ seconds on a flaky network.
     */
    private const val REQUEST_TIMEOUT_MS = 4_000L

    /**
     * Cached integrity result. Re-request if stale. Null means
     * no check has been performed yet this session.
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
     * Private coroutine scope for async work. Single-threaded
     * main dispatcher so callback dispatching matches the prior
     * AddOnSuccessListener main-thread contract.
     */
    private val auxScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Request a Play Integrity token from Google.
     *
     * @param context Application context.
     * @param cloudProjectNumber The Google Cloud project number
     *   (from google-services.json or Play Console). Pass 0 to use
     *   the default associated with the app.
     * @param callback Invoked with the result on the main thread.
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
        auxScope.launch {
            try {
                val verdict = queryWithTimeoutAndGms(context, cloudProjectNumber)
                cachedResult.set(verdict)
                lastCheckTimestamp = System.currentTimeMillis()
                callback(IntegrityTokenResult.Success(verdict))
            } catch (e: Throwable) {
                Log.w(TAG, "Integrity lookup failed unexpectedly: ${e.message}")
                val fallback = inconclusiveFromException(e)
                cachedResult.set(fallback)
                lastCheckTimestamp = System.currentTimeMillis()
                callback(IntegrityTokenResult.Success(fallback))
            } finally {
                isRequesting = false
            }
        }
    }

    /**
     * GMS availability probe → INCONCLUSIVE_GRACEFUL fallback,
     * followed by 4-second-timeout-bound PIA call,
     * followed by IntegrityVerdict.fromToken().
     *
     * Each non-success path returns an INCONCLUSIVE verdict so the
     * gate does NOT score against the user.
     */
    private suspend fun queryWithTimeoutAndGms(
        context: Context,
        cloudProjectNumber: Long
    ): IntegrityVerdict {
        // ── Step 1: GMS probe ─────────────────────────────────────────
        val gmsStatus = try {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context)
        } catch (t: Throwable) {
            Log.w(TAG, "GoogleApiAvailability probe threw: ${t.message}")
            ConnectionResult.SERVICE_INVALID
        }
        if (gmsStatus != ConnectionResult.SUCCESS) {
            Log.i(TAG, "Play Services unavailable (status=$gmsStatus); INCONCLUSIVE_GMS_MISSING")
            return IntegrityVerdict.inconclusive(
                gmsAvailable = false,
                label = "GMS_MISSING_$gmsStatus"
            )
        }

        // ── Step 2: hard 4-second timeout around the PIA Task ──────────
        val token = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            awaitIntegrityToken(context, cloudProjectNumber)
        }
        if (token == null) {
            Log.w(TAG, "Play Integrity request timed out (or Task failed); INCONCLUSIVE_TIMEOUT")
            return IntegrityVerdict.inconclusive(
                gmsAvailable = true,
                label = "TIMEOUT_${REQUEST_TIMEOUT_MS}ms"
            )
        }

        // ── Step 3: parse token locally for immediate threat assessment ─
        // Server-side JWS verification is Phase 8 (Step 7.10 v2).
        return IntegrityVerdict.fromToken(token)
    }

    /**
     * Bridge the Play Integrity Task graph into a suspending
     * function. Returns null on Task failure so the caller can
     * classify it together with the timeout case.
     *
     * Uses `suspendCancellableCoroutine` rather than
     * `kotlinx-coroutines-play-services`'s `Task.await()` so we
     * avoid a new coroutines extension dependency for one call site.
     */
    private suspend fun awaitIntegrityToken(
        context: Context,
        cloudProjectNumber: Long
    ): String? = suspendCancellableCoroutine { cont ->
        try {
            val integrityManager = IntegrityManagerFactory.create(context.applicationContext)

            val nonceBytes = ByteArray(NONCE_BYTES)
            SecureRandom().nextBytes(nonceBytes)
            val nonce = Base64.encodeToString(
                nonceBytes,
                Base64.NO_WRAP or Base64.URL_SAFE
            )

            val requestBuilder = IntegrityTokenRequest.builder()
                .setNonce(nonce)
            if (cloudProjectNumber > 0) {
                requestBuilder.setCloudProjectNumber(cloudProjectNumber)
            }

            integrityManager.requestIntegrityToken(requestBuilder.build())
                .addOnSuccessListener { response: IntegrityTokenResponse ->
                    if (cont.isActive) {
                        Log.i(TAG, "Integrity token received (${response.token().length} chars)")
                        cont.resume(response.token())
                    }
                }
                .addOnFailureListener { exception ->
                    Log.w(TAG, "Integrity token Task failed: ${exception.message}")
                    if (cont.isActive) cont.resume(null)
                }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bootstrap integrity manager: ${e.message}")
            if (cont.isActive) cont.resume(null)
        }

        cont.invokeOnCancellation {
            // Best-effort: AddOnSuccessListener can't be cancelled,
            // but the timeout-or-failure path will short-circuit
            // before any further work runs.
            Log.d(TAG, "awaitIntegrityToken coroutine cancelled")
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

    /**
     * Convenience used by the catch-all path in [requestToken] —
     * maps an exception into an INCONCLUSIVE verdict with the
     * exception's message preserved in the device-recognition
     * verdict slot for Sentry traces.
     */
    private fun inconclusiveFromException(t: Throwable): IntegrityVerdict =
        IntegrityVerdict.inconclusive(
            gmsAvailable = true,
            label = "EXCEPTION_${t.javaClass.simpleName}"
        )
}

/**
 * Result of an integrity token request.
 */
sealed class IntegrityTokenResult {
    /** Token received successfully. Contains the parsed verdict. */
    data class Success(val verdict: IntegrityVerdict) : IntegrityTokenResult()
    /** Token request failed. */
    data class Error(val message: String) : IntegrityTokenResult()
}
