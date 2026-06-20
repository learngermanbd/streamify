package com.streamify.app.data.remote

import android.content.Context
import com.streamify.app.BuildConfig
import okhttp3.OkHttpClient

/**
 * Phase 2 \u00b7 Step 2.2 \u2014 The shared dependency-injection seam for the
 * remote-side singletons. Built once in [com.streamify.app.StreamifyApp.onCreate]
 * and accessed through `app.network`.
 *
 *  app.network.httpClient        \u2014 shared OkHttpClient (also used by Media3 in Phase 4)
 *  app.network.apiClient         \u2014 GET wrapper around httpClient + baseUrl
 *  app.network.apiService        \u2014 raw endpoint catalog
 *  app.network.remoteDataSource  \u2014 typed fetch+parse for the 8 models
 *
 * Live binding of the real `apiBaseUrl` from [RemoteConfigHelper.fetchConfig()]
 * lands in Phase 7 · Step 7.6; for now we use [ApiClient.defaultBaseUrl].
 *
 * Phase 7 · Step 7.2 — default param switched from `const val DEFAULT_BASE_URL`
 * (plaintext literal) to `defaultBaseUrl()` (decrypted at runtime from
 * build-time encrypted constants).
 */
class NetworkModule(
    context: Context,
    private val baseUrl: String = ApiClient.defaultBaseUrl()
) {

    val httpClient: OkHttpClient by lazy {
        ApiClient.buildHttpClient(context.applicationContext, debug = BuildConfig.DEBUG)
    }

    val apiClient: ApiClient by lazy {
        ApiClient(baseUrl = baseUrl, httpClient = httpClient)
    }

    val apiService: ApiService by lazy {
        ApiService(apiClient = apiClient)
    }

    val remoteDataSource: RemoteDataSource by lazy {
        RemoteDataSource(apiService = apiService)
    }
}
