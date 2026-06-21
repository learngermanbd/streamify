package com.streamify.app.data.remote

import android.content.Context
import android.util.Log
import com.streamify.app.BuildConfig
import com.streamify.app.security.NetworkInterceptor
import com.streamify.app.security.RequestSigner
import com.streamify.app.security.RuntimeStringProvider
import com.streamify.app.security.SSLPinner
import com.streamify.app.security.TokenManager
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Phase 2 \u00b7 Step 2.2 \u2014 Thin wrapper over a shared [OkHttpClient] that performs
 * GET requests against a fixed [baseUrl]. Returns the response body as a String
 * so callers (in this layer: [ApiService]) stay parser-agnostic.
 *
 * @param baseUrl   The configured API host, e.g. "https://learngermanwith.fun/".
 *                  Will be [defaultBaseUrl] until `RemoteConfigHelper.fetchConfig()`
 *                  delivers a real one (Phase 7 · Step 7.6).
 * @param httpClient The OkHttp instance. Use [buildHttpClient] to get the shared one
 *                  with the right timeouts, cache, auth + debug logging wiring.
 */
class ApiClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient
) {

    /**
     * Issue a GET against [baseUrl] + [path] (joining with `/` if needed).
     * Appends [queryParams] as URL query parameters.
     *
     * @return Response body as a String.
     * @throws IOException on a network failure.
     * @throws ApiHttpException on a non-2xx response code.
     */
    @Throws(IOException::class, ApiHttpException::class)
    fun get(
        path: String,
        queryParams: Map<String, String> = emptyMap()
    ): String {
        val urlBuilder = joinUrl(baseUrl, path).newBuilder()
        queryParams.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiHttpException(response.code, body)
            }
            return body
        }
    }

    private fun joinUrl(base: String, path: String): HttpUrl {
        val normalizedBase = if (base.endsWith('/')) base.dropLast(1) else base
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        return (normalizedBase + normalizedPath).toHttpUrl()
    }

    companion object {
        /**
         * Phase 7 · Step 7.2 — Default API base URL, decrypted at
         * runtime from build-time encrypted constants.  Replaces the
         * old `const val DEFAULT_BASE_URL` which was a plaintext
         * literal visible in the APK.
         */
        fun defaultBaseUrl(): String = RuntimeStringProvider.getString("API_BASE_URL")

        /**
         * Sent on every authed request as `X-App-Version`. Wired
         * straight from [BuildConfig.VERSION_NAME] (compile-time
         * `const val` in the AGP-generated BuildConfig) so the header
         * tracks the gradle `defaultConfig.versionName` without
         * further edits; the historical hard-coded \"1.0.0\" was a
         * 6.5 placeholder that's now obsolete.
         */
        const val APP_VERSION = BuildConfig.VERSION_NAME

        /** 10 MB HTTP cache \u2014 well within OkHttp's recommended 5-25 MB range. */
        private const val CACHE_SIZE_BYTES = 10L * 1024L * 1024L

        /**
         * Builds the shared [OkHttpClient]. Wired with:
         *  \u2022 30 s connect / read / write timeouts
         *  \u2022 10 MB on-disk HTTP cache
         *  \u2022 [AuthInterceptor] (X-App-Version + Accept headers; auth token lands in Phase 5)
         *  \u2022 [DebugLoggerInterceptor] only when [debug] = true
         */
        fun buildHttpClient(
            context: Context,
            debug: Boolean,
            tokenManager: TokenManager,
        ): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .cache(Cache(context.cacheDir.resolve("streamify_http"), CACHE_SIZE_BYTES))
                // Phase 7 · Step 7.7 — SSL pinning (relaxed in debug for proxy tools)
                // Note: Proxy.NO_PROXY is NOT set here because this client is
                // shared with Media3 ExoPlayer (HLS/DASH streaming) which may
                // need system proxy on corporate networks.  HTTPS enforcement
                // + certificate pinning provide equivalent MITM protection.
                .certificatePinner(SSLPinner.buildCertificatePinner(debug))
                // post-v1.1.1 hardening — Ordered retry-then-auth then-sign.
                // RetryOn5xx first so each retry re-walks AuthInterceptor
                // (fresh Bearer/Version headers) and NetworkInterceptor
                // (fresh timestamp/nonce signature via RequestSigner, plus
                // fresh Content-Type validation). Auth next so every signed
                // request gets a freshly cached access token stamped on.
                .addInterceptor(RetryOn5xxInterceptor())
                .addInterceptor(AuthInterceptor(tokenManager))
                // Phase 7 · Step 7.7 — HTTPS enforcement + request signing + redirect protection
                .addInterceptor(NetworkInterceptor)
                .apply { if (debug) addInterceptor(DebugLoggerInterceptor()) }
                .build()
    }
}

/**
 * Adds X-App-Version + Accept headers to every outgoing request. Will also host
 * the Authorization: Bearer <token> header once the user-auth flow ships
 * (Phase 5 \u00b7 Step 5.1).
 */
internal class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
            .header(HEADER_APP_VERSION, ApiClient.APP_VERSION)
            .header(HEADER_ACCEPT, "application/json")

        // post-v1.1.1 hardening — opportunistic Bearer header.
        // The historical KDoc admitted the auth-header was a
        // "Phase 5 placeholder"; now that Phase 5 has shipped,
        // TokenManager + TokenStore + RefreshToken flow are built
        // out (see security/TokenManager.kt + security/TokenStore.kt)
        // and this is the seam where the access token finally lands
        // on the wire. The read is wrapped in runCatching because
        // a TokStore/Keystore transient failure (TEE wedge on first
        // boot, KeyStoreException under load) should NOT crash every
        // outbound request — the cache miss is benign and the next
        // request retries the read; the higher-level Authenticator
        // handles persistent 401s.
        runCatching {
            tokenManager.getAccessToken()
                ?.takeIf { it.isNotBlank() }
                ?.let { token -> builder.header(HEADER_AUTHORIZATION, "Bearer $token") }
        }.onFailure { t ->
            Log.d(TAG, "Token unavailable: ${t.javaClass.simpleName} ${t.message}")
        }

        return chain.proceed(builder.build())
    }

    // post-v1.1.1 hardening — Interceptor header constants. These
    // must live in the companion object because Kotlin forbids
    // `const val` inside a non-companion class body; placing them
    // in a private companion keeps them out of the public API
    // without losing the `private const` inlining benefit.
    private companion object {
        private const val TAG = "AuthInterceptor"
        private const val HEADER_APP_VERSION = "X-App-Version"
        private const val HEADER_ACCEPT = "Accept"
        private const val HEADER_AUTHORIZATION = "Authorization"
    }
}

/**
 * Minimal line-per-request logger. Enabled only in debug builds. Logs via
 * android.util.Log so it appears in logcat alongside OkHttp's own warnings.
 */
internal class DebugLoggerInterceptor : Interceptor {

    private val tag = "StreamifyHttp"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedNanos = System.nanoTime()
        Log.d(tag, "--> ${request.method} ${request.url}")

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            Log.d(tag, "<-- FAIL ${request.url}: ${e.javaClass.simpleName} ${e.message}")
            throw e
        }

        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
        Log.d(tag, "<-- ${response.code} ${request.url} (${elapsedMs}ms)")
        return response
    }
}

/**
 * post-v1.1.1 hardening — Retry-on-5xx response interceptor.
 *
 * Catches transient server-side failures (HTTP 500, 502, 503, 504)
 * and re-issues the *same* request up to [maxRetries] additional
 * times. Re-issuing via `chain.proceed(request)` walks EVERY
 * interceptor after this one again, so each retry gets:
 *   - a fresh Bearer / version header (AuthInterceptor re-reads
 *     the cached access token — harmless if unchanged),
 *   - a fresh timestamp + nonce signature (NetworkInterceptor →
 *     RequestSigner so server-side replay protection doesn't
 *     reject the retry).
 *
 * Does NOT retry on 4xx (client-side, non-transient).  We keep
 * this short (max 2 retries) so a sustained 500 storm doesn't
 * hammer the backend — the higher-level ViewModel surfaces the
 * remaining error to a user-facing retry button.  Also bails
 * out on a chained follow-up redirect response (`priorResponse
 * != null`) so we don't loop an auth-redirect chain.
 */
internal class RetryOn5xxInterceptor(
    private val maxRetries: Int = 2,
) : Interceptor {

    private val tag = "RetryOn5xx"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempt = 0

        while (response.code in 500..599 &&
            attempt < maxRetries &&
            response.priorResponse == null
        ) {
            Log.w(
                tag,
                "5xx response (${response.code}) on ${request.url}; retry " +
                    "${attempt + 1}/$maxRetries"
            )
            response.close()
            response = chain.proceed(request)
            attempt++
        }
        return response
    }
}
