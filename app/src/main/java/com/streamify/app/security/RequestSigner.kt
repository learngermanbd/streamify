package com.streamify.app.security

import android.util.Log
import okhttp3.Request
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 7 · Step 7.7 — HMAC-SHA256 request signing.
 *
 * Adds cryptographic signatures to outgoing API requests to prevent:
 *  - **Request tampering** — the server verifies the signature
 *    matches the request body + headers.
 *  - **Replay attacks** — each request includes a timestamp and
 *    nonce; the server rejects requests with stale timestamps or
 *    reused nonces.
 *
 * ## Signature format
 * The signature is computed over:
 * ```
 * HMAC-SHA256(
 *   signing_secret,
 *   method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + body_hash
 * )
 * ```
 *
 * The resulting signature is sent in the `X-Request-Signature` header.
 *
 * ## Server-side verification
 * The server must:
 *  1. Check that the timestamp is within ±5 minutes of server time.
 *  2. Check that the nonce hasn't been seen before (store for 10 min).
 *  3. Recompute the HMAC and compare with the signature.
 */
object RequestSigner {

    private const val TAG = "RequestSigner"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000L // 5 minutes

    /**
     * Signing secret.  In production, this should come from
     * [RuntimeStringProvider] or [EncryptedConstants].
     * Empty string disables signing (debug builds).
     *
     * TODO(Phase 7): wire from EncryptedConstants.
     */
    internal var signingSecret: String = ""

    /**
     * Sign an outgoing OkHttp [Request] by adding security headers:
     *  - `X-Request-Timestamp` — Unix timestamp in milliseconds
     *  - `X-Request-Nonce` — UUID v4 (unique per request)
     *  - `X-Request-Signature` — HMAC-SHA256 of the request metadata
     *
     * If [signingSecret] is empty (debug builds), only the timestamp
     * and nonce are added (no signature).
     */
    fun signRequest(request: Request): Request {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val bodyHash = hashBody(request)

        val builder = request.newBuilder()
            .header("X-Request-Timestamp", timestamp)
            .header("X-Request-Nonce", nonce)

        if (signingSecret.isNotBlank()) {
            val payload = buildString {
                append(request.method)
                append('\n')
                append(request.url.encodedPath)
                append('\n')
                append(timestamp)
                append('\n')
                append(nonce)
                append('\n')
                append(bodyHash)
            }
            val signature = hmacSha256(signingSecret, payload)
            builder.header("X-Request-Signature", signature)
        }

        return builder.build()
    }

    /**
     * Verify that a response's request was signed within the
     * acceptable time window.  Used server-side (documented here
     * for reference).
     */
    fun isTimestampValid(timestampMs: Long): Boolean {
        val now = System.currentTimeMillis()
        return Math.abs(now - timestampMs) < TIMESTAMP_TOLERANCE_MS
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun hashBody(request: Request): String {
        val body = request.body ?: return "empty"
        return try {
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            val bytes = buffer.readByteArray()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.digest(bytes).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to hash request body: ${e.message}")
            "error"
        }
    }

    private fun hmacSha256(secret: String, data: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
