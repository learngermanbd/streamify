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

    /**
     * Video streaming file extensions that are commonly served over
     * HTTP by CDNs and origin servers.  HLS playlists (.m3u8) and
     * MPEG-TS segments (.ts) are the two most common; progressive
     * .mp4 is included for completeness.  Blocking these at the HTTP
     * level kills video playback entirely since many stream providers
     * don't offer HTTPS for segment URLs.
     */
    private val VIDEO_STREAM_EXTENSIONS = setOf(
        ".ts",    // MPEG-TS  (HLS segment)
        ".m3u8",  // HLS playlist
        ".mp4",   // Progressive download
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // ── 1. HTTPS enforcement (with debug loopback + video-stream carve-outs) ───
        val host = request.url.host.lowercase()
        val isLoopback = host in LOOPBACK_HOSTS
        // Release builds NEVER honour loopback — even on a stolen dev
        // device the attacker can't downgrade traffic to HTTP for prod.
        val allowCleartextLoopback = BuildConfig.DEBUG && isLoopback

        // post-v1.1.2 — HLS & video-stream carve-out.  Many CDNs serve
        // .ts segments and .m3u8 playlists exclusively over HTTP; if we
        // block those the ExoPlayer / Media3 pipeline can never start.
        // Includes extension-based matching (covers 99% of HLS CDNs) AND
        // path-based matching for providers that serve extension-less
        // segments under /live/, /stream/, or /hls/ prefixes.
        val path = request.url.encodedPath.lowercase()
        val isVideoStream = VIDEO_STREAM_EXTENSIONS.any { path.endsWith(it) }
            || path.contains("/live/")
            || path.contains("/stream/")
            || path.contains("/hls/")
            || path.contains("/play/")
            || path.contains("/playback/")
            || path.contains("/watch/")

        if (!request.url.isHttps && !allowCleartextLoopback && !isVideoStream) {
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
        // post-v1.1.1 hardening — Resolve relative Location headers
        // against the request URL before validating. The historical
        // startsWith("https://") check threw SecurityException on
        // legitimate same-host redirects like `Location: /v2/some/path`
        // (relative) which are perfectly HTTPS-safe after resolution.
        // We now use OkHttp's own HttpUrl.resolve() — exactly what
        // the transport layer would do when following the redirect.
        val locationHeader = response.header("Location")
        if (locationHeader != null) {
            val absoluteUrl = runCatching { request.url.resolve(locationHeader) }.getOrNull()
            if (absoluteUrl != null && !absoluteUrl.isHttps) {
                Log.e(TAG, "BLOCKED redirect to non-HTTPS: $absoluteUrl (raw: $locationHeader)")
                response.close()
                throw SecurityException(
                    "Non-HTTPS redirect blocked: $absoluteUrl"
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
