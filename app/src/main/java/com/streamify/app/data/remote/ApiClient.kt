package com.streamify.app.data.remote

import android.content.Context
import android.util.Log
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.streamify.app.security.NetworkInterceptor
import com.streamify.app.security.RequestSigner
import com.streamify.app.security.RuntimeStringProvider
import com.streamify.app.security.SSLPinner
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

        /** Sent on every authed request. Real version from BuildConfig.VERSION_NAME in 6.5. */
        const val APP_VERSION = "1.0.0"

        /** 10 MB HTTP cache \u2014 well within OkHttp's recommended 5-25 MB range. */
        private const val CACHE_SIZE_BYTES = 10L * 1024L * 1024L

        /**
         * Builds the shared [OkHttpClient]. Wired with:
         *  \u2022 30 s connect / read / write timeouts
         *  \u2022 10 MB on-disk HTTP cache
         *  \u2022 [AuthInterceptor] (X-App-Version + Accept headers; auth token lands in Phase 5)
         *  \u2022 [DebugLoggerInterceptor] only when [debug] = true
         */
        fun buildHttpClient(context: Context, debug: Boolean): OkHttpClient =
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
                .addInterceptor(AuthInterceptor)
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
internal object AuthInterceptor : Interceptor {
    private const val HEADER_APP_VERSION = "X-App-Version"
    private const val HEADER_ACCEPT = "Accept"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header(HEADER_APP_VERSION, ApiClient.APP_VERSION)
            .header(HEADER_ACCEPT, "application/json")
            .build()
        return chain.proceed(request)
    }
}

/**
 * Minimal line-per-request logger. Enabled only in debug builds. Logs via
 * android.util.Log so it appears in logcat alongside OkHttp's own warnings.
 */
internal class DebugLoggerInterceptor : Interceptor {

    private val tag = "SportStreamHttp"

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
