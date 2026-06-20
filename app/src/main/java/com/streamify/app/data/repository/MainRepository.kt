package com.streamify.app.data.repository

import com.streamify.app.data.models.Banner
import com.streamify.app.data.models.Category
import com.streamify.app.data.models.Channel
import com.streamify.app.data.models.Event
import com.streamify.app.data.models.Highlight
import com.streamify.app.data.models.Playlist
import com.streamify.app.data.remote.ApiResult
import com.streamify.app.data.remote.RemoteDataSource

/**
 * Phase 2 · Step 2.4 — Main (network-side) Repository.
 *
 * Thin facade over [RemoteDataSource]. Holds the network API surface
 * for Step 2.5 ViewModels so the data layer is swappable without
 * touching the UI layer.
 *
 * No try/catch on purpose: CancellationException must propagate
 * cleanly through the suspend boundary. [RemoteDataSource] already
 * maps non-cancel Throwables to [ApiResult.Failure] inside
 * `fetchAndParse`, so structured-concurrency cancellation still
 * propagates while the caller folds on [ApiResult.Success] / Failure.
 */
class MainRepository(
    private val remoteDataSource: RemoteDataSource
) {
    suspend fun fetchEvents(): ApiResult<List<Event>> =
        remoteDataSource.fetchEvents()

    suspend fun fetchLive(): ApiResult<List<Event>> =
        remoteDataSource.fetchLive()

    suspend fun fetchChannels(): ApiResult<List<Channel>> =
        remoteDataSource.fetchChannels()

    suspend fun fetchCategories(): ApiResult<List<Category>> =
        remoteDataSource.fetchCategories()

    suspend fun fetchHighlights(): ApiResult<List<Highlight>> =
        remoteDataSource.fetchHighlights()

    suspend fun fetchPlaylists(ownerId: String): ApiResult<List<Playlist>> =
        remoteDataSource.fetchPlaylists(ownerId)

    /**
     * Phase 3 · Step 3.3 — banner carousel slides. Mirrors the rest of
     * MainRepository's thin-facade pattern (no try/catch; structured
     * concurrency cancels cleanly via [RemoteDataSource.fetchAndParse]).
     */
    suspend fun fetchBanners(): ApiResult<List<Banner>> =
        remoteDataSource.fetchBanners()
}
