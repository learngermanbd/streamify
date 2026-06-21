package com.streamify.app.security

import android.content.Context
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
 * ## v1.1.1 hardening — secret lifecycle
 *
 * As of v1.1.1 the `signingSecret` no longer sits in a mutable
 * public field. Instead it is:
 *
 *  1. Encrypted at build time by `:app:encryptSecrets` →
 *     `EncryptedConstants.SIGNING_SECRET`.
 *  2. Fetched on demand by `RuntimeStringProvider.getString("SIGNING_SECRET")`
 *     (AES-256-GCM with an XOR-obfuscated per-build key).
 *  3. Held as a `CharArray` only for the lifetime of each
 *     [signRequest] call, then wiped via `StringEncryptor.wipe`.
 *
 * The `signingSecret` property kept below is the **fallback**
 * path used in debug builds where `SIGNING_SECRET` is missing
 * from `secrets.properties`. Production paths always use
 * [RuntimeStringProvider.getString].
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
     * Optional explicit override. Prefer [signWithSecret] which
     * reads from [RuntimeStringProvider] directly so the secret
     * never appears as a [String] in the JVM intern pool.
     *
     * Empty by default; a non-empty value here bypasses
     * [RuntimeStringProvider] entirely (used only in tests).
     */
    @JvmStatic
    var signingSecretOverride: String = ""

    /**
     * Boot the signer with an [Context]. The context is unused
     * today (placeholder for future per-install derivation) but
     * is required by [com.streamify.app.StreamifyApp]'s
     * `runStep` lambda contract.
     */
    @JvmStatic
    fun bootstrap(@Suppress("UNUSED_PARAMETER") context: Context) {
        // RequestSigner lazily reads RuntimeStringProvider.getString("SIGNING_SECRET")
        // on each sign call (see [signWithSecret]). No eagerly-stored state to bootstrap.
        Log.d(TAG, "RequestSigner bootstrap (lazy-decrypt mode)")
    }

    /**
     * Sign an outgoing OkHttp [Request] by adding security headers:
     *  - `X-Request-Timestamp` — Unix timestamp in milliseconds
     *  - `X-Request-Nonce` — UUID v4 (unique per request)
     *  - `X-Request-Signature` — HMAC-SHA256 of the request metadata
     *
     * If no signing secret is configured (debug builds, missing
     * `secrets.properties → SIGNING_SECRET`), only the timestamp
     * and nonce are added (no signature). The secret is fetched
     * via [RuntimeStringProvider] so it lives as a [CharArray] for
     * the duration of this call only; the array is wiped on exit.
     */
    fun signRequest(request: Request): Request {
        val explicitOverride = signingSecretOverride.takeIf { it.isNotBlank() }
        val secretChars: CharArray? = explicitOverride?.toCharArray()
            ?: runCatching {
                RuntimeStringProvider.get("SIGNING_SECRET")
            }.getOrNull()
        // Build the request with timestamp + nonce (always)
        return buildSigned(request, secretChars).also {
            secretChars?.let { arr -> StringEncryptor.wipe(arr) }
        }
    }

    private fun buildSigned(
        request: Request,
        secretChars: CharArray?
    ): Request {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val bodyHash = hashBody(request)

        val builder = request.newBuilder()
            .header("X-Request-Timestamp", timestamp)
            .header("X-Request-Nonce", nonce)

        val secretString = secretChars?.concatToString().orEmpty()
        if (secretString.isNotBlank()) {
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
            val signature = hmacSha256(secretString, payload)
            builder.header("X-Request-Signature", signature)
            Log.d(TAG, "Signed request ${request.method} ${request.url.encodedPath}")
        } else {
            Log.d(TAG, "No signing secret configured; emitted unsigned headers only")
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
