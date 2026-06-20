package com.streamify.app.security

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Phase 7 · Step 7.7 — Network request/response integrity interceptor.
 *
 * Validates outgoing requests and incoming responses:
 *  1. **HTTPS enforcement** — blocks any request to non-HTTPS URLs.
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // ── 1. HTTPS enforcement ────────────────────────────────────
        if (!request.url.isHttps) {
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
