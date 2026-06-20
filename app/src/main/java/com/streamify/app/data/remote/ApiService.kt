package com.streamify.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 2 · Step 2.2 — Raw endpoint catalog. Returns response bodies as
 * String, parser-agnostic. [RemoteDataSource] sits one layer on top and turns
 * these strings into typed [ApiResult] values for the 8 models.
 *
 * Every method hops to [Dispatchers.IO] because the underlying [ApiClient.get]
 * uses OkHttp's blocking [okhttp3.Call.execute].
 */
class ApiService(
    private val apiClient: ApiClient
) {

    suspend fun getEvents(): String = withContext(Dispatchers.IO) {
        apiClient.get(PATH_EVENTS)
    }

    /** Subset of events where status == LIVE. */
    suspend fun getLive(): String = withContext(Dispatchers.IO) {
        apiClient.get(PATH_LIVE)
    }

    suspend fun getChannels(): String = withContext(Dispatchers.IO) {
        apiClient.get(PATH_CHANNELS)
    }

    suspend fun getCategories(): String = withContext(Dispatchers.IO) {
        apiClient.get(PATH_CATEGORIES)
    }

    suspend fun getHighlights(): String = withContext(Dispatchers.IO) {
        apiClient.get(PATH_HIGHLIGHTS)
    }

    /**
     * Returns the user's playlists. For v1 [ownerId] is the device-local user
     * id; multi-account lands in Phase 8 Step 8.x.
     */
    suspend fun getPlaylists(ownerId: String): String = withContext(Dispatchers.IO) {
        apiClient.get(
            path = PATH_PLAYLISTS,
            queryParams = mapOf("ownerId" to ownerId)
        )
    }

    /**
     * Phase 3 · Step 3.3 — Returns the active banner carousel slide list.
     * Backed by `/api/banners`; inactive banners are filtered server-side.
     */
    suspend fun getBanners(): String = withContext(Dispatchers.IO) {
        apiClient.get(PATH_BANNERS)
    }

    companion object {
        const val PATH_EVENTS = "/api/events"
        const val PATH_LIVE = "/api/live"
        const val PATH_CHANNELS = "/api/channels"
        const val PATH_CATEGORIES = "/api/categories"
        const val PATH_HIGHLIGHTS = "/api/highlights"
        const val PATH_PLAYLISTS = "/api/playlists"
        const val PATH_BANNERS = "/api/banners"
    }
}
