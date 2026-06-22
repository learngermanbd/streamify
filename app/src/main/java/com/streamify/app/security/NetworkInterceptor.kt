package com.streamify.app.security

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Network request/response integrity interceptor.
 *
 * Validates outgoing requests and incoming responses:
 *  1. **HTTPS enforcement for API calls only** — blocks non-HTTPS
 *     requests to the API backend (learngermanwith.fun). All other
 *     requests (streaming CDNs, media servers) are allowed over HTTP
 *     because most sports streaming servers use plain HTTP.
 *  2. **Redirect protection** — blocks redirects to non-HTTPS URLs
 *     for API backend requests.
 *  3. **Response content-type** — warns if API responses don't
 *     return JSON (potential proxy injection of HTML/JS).
 *  4. **Custom security headers** — adds `X-Request-Timestamp`
 *     and `X-Request-Nonce` for replay-attack protection (used
 *     by [RequestSigner] server-side verification).
 */
internal object NetworkInterceptor : Interceptor {

    private const val TAG = "NetworkInterceptor"

    /**
     * API backend domains that MUST use HTTPS. All other domains
     * (streaming CDNs, media servers) are allowed over HTTP because
     * most sports streaming providers use plain HTTP.
     */
    private val API_BACKEND_HOSTS = setOf(
        "learngermanwith.fun",
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // ── 1. HTTPS enforcement for API backend only ────────────────
        // Most sports streaming CDNs use plain HTTP. Only enforce HTTPS
        // for API calls to the backend. All other requests (streaming,
        // media, CDN) are allowed over HTTP.
        val host = request.url.host.lowercase()
        val isApiBackend = host in API_BACKEND_HOSTS || host.endsWith(".learngermanwith.fun")

        if (isApiBackend && !request.url.isHttps) {
            Log.e(TAG, "BLOCKED non-HTTPS API request: ${request.url}")
            throw SecurityException(
                "Non-HTTPS request blocked for API backend: ${request.url}"
            )
        }

        // ── 2. Add security headers via RequestSigner ───────────────
        val signedRequest = RequestSigner.signRequest(request)

        // ── 3. Execute request ──────────────────────────────────────
        val response = chain.proceed(signedRequest)

        // ── 4. Redirect protection (API backend only) ────────────────
        // Only block non-HTTPS redirects for API backend requests.
        // Streaming CDNs may redirect to HTTP URLs legitimately.
        val locationHeader = response.header("Location")
        if (locationHeader != null && isApiBackend) {
            val absoluteUrl = runCatching { request.url.resolve(locationHeader) }.getOrNull()
            if (absoluteUrl != null && !absoluteUrl.isHttps) {
                Log.e(TAG, "BLOCKED redirect to non-HTTPS: $absoluteUrl (raw: $locationHeader)")
                response.close()
                throw SecurityException(
                    "Non-HTTPS redirect blocked for API backend: $absoluteUrl"
                )
            }
        }

        // ── 5. Response content-type warning ────────────────────────
        // post-v1.1.1 hardening — Apply this check to every path.
        // The historical filter (only warn for /api/) let an HTML
        // proxy injection pass silently if the upstream backend
        // happened to point at a non-/api/ JSON endpoint (some
        // routes are mounted under /v1/, /graphql/, /internal/, etc.).
        // Bypass list: octet-stream (HLS segments, downloadable
        // assets), text/plain (manifests, server-side text logs),
        // image/* (thumbnails), video/* (HLS video segments).
        val contentType = response.header("Content-Type")
        if (contentType != null &&
            !contentType.contains("json", ignoreCase = true) &&
            !contentType.contains("octet-stream", ignoreCase = true) &&
            !contentType.contains("text/plain", ignoreCase = true) &&
            !contentType.contains("image/", ignoreCase = false) &&
            !contentType.contains("video/", ignoreCase = false)
        ) {
            Log.w(
                TAG,
                "Unexpected Content-Type for response: " +
                    "$contentType (${request.url})"
            )
            // Don't block — some endpoints might legitimately return
            // non-JSON.  Just warn so a downstream proxy injection
            // (e.g. captive portal returning HTML) surfaces in logcat.
        }

        return response
    }
}
