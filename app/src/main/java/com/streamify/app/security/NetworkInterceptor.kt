package com.streamify.app.security

import android.util.Log
import com.streamify.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Phase 7 · Step 7.7 — Network request/response integrity interceptor.
 *
 * Validates outgoing requests and incoming responses:
 *  1. **HTTPS enforcement** — blocks any request to non-HTTPS URLs.
 *     Debug builds carve out Android-emulator loopback hosts
 *     (`10.0.2.2`, `localhost`, `127.0.0.1`) so the dev APK can
 *     talk to a backend running on the developer's PC without a
 *     self-signed cert.  Release builds enforce strict HTTPS
 *     everywhere.
 *  2. **Redirect protection** — blocks redirects to non-HTTPS URLs
 *     (a common MITM vector).
 *  3. **Response content-type** — warns if API responses don't
 *     return JSON (potential proxy injection of HTML/JS).
 *  4. **Custom security headers** — adds `X-Request-Timestamp`
 *     and `X-Request-Nonce` for replay-attack protection (used
 *     by [RequestSigner] server-side verification).
 */
internal object NetworkInterceptor : Interceptor {

    private const val TAG = "NetworkInterceptor"

    /**
     * Hosts whose loopback nature means HTTPS is not realistically
     * available in dev (no self-signed root, no DNS).  Mirrors the
     * domain-config carve-outs in `network_security_config.xml`.
     * DEBUG-only; release refuses loopback regardless of host.
     */
    private val LOOPBACK_HOSTS = setOf(
        "10.0.2.2",   // Android emulator's `host loopback` alias
        "localhost",
        "127.0.0.1",
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // ── 1. HTTPS enforcement (with debug loopback carve-out) ───
        val host = request.url.host.lowercase()
        val isLoopback = host in LOOPBACK_HOSTS
        // Release builds NEVER honour loopback — even on a stolen dev
        // device the attacker can't downgrade traffic to HTTP for prod.
        val allowCleartextLoopback = BuildConfig.DEBUG && isLoopback
        if (!request.url.isHttps && !allowCleartextLoopback) {
            Log.e(TAG, "BLOCKED non-HTTPS request: ${request.url}")
            throw SecurityException(
                "Non-HTTPS request blocked: ${request.url}"
            )
        }

        // ── 2. Add security headers via RequestSigner ───────────────
        val signedRequest = RequestSigner.signRequest(request)

        // ── 3. Execute request ──────────────────────────────────────
        val response = chain.proceed(signedRequest)

        // ── 4. Redirect protection ──────────────────────────────────
        val redirectUrl = response.header("Location")
        if (redirectUrl != null && !redirectUrl.startsWith("https://", ignoreCase = true)) {
            Log.e(TAG, "BLOCKED redirect to non-HTTPS: $redirectUrl")
            response.close()
            throw SecurityException(
                "Non-HTTPS redirect blocked: $redirectUrl"
            )
        }

        // ── 5. Response content-type warning ────────────────────────
        // API responses should be JSON.  If we get HTML/JS, it might
        // be a proxy injecting content.
        val contentType = response.header("Content-Type")
        if (contentType != null &&
            !contentType.contains("json", ignoreCase = true) &&
            !contentType.contains("octet-stream", ignoreCase = true) &&
            !contentType.contains("text/plain", ignoreCase = true) &&
            request.url.encodedPath.startsWith("/api/")
        ) {
            Log.w(TAG, "Unexpected Content-Type for API response: " +
                "$contentType (${request.url})")
            // Don't block — some endpoints might legitimately return
            // non-JSON (e.g., file downloads).  Just warn.
        }

        return response
    }
}
