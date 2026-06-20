package com.streamify.app.data.repository

import com.streamify.app.data.local.LocalDataSource
import com.streamify.app.data.local.PlaylistEntity
import com.streamify.app.data.models.Playlist
import com.streamify.app.data.models.StreamLink
import kotlinx.coroutines.flow.Flow

/**
 * Phase 2 · Step 2.4 — Playlist Repository.
 *
 * Step 5.2 (Playlist CRUD UI) consumes observe / upsert / delete /
 * addStream / removeStream. Step 8.10 (/api/playlists hydration)
 * consumes upsertDomain.
 *
 * No try/catch: writes hop through [LocalDataSource]'s
 * `withContext(Dispatchers.IO)` and structured-concurrency cancellation
 * propagates cleanly through this Repository facade.
 */
class PlaylistRepository(
    private val localDataSource: LocalDataSource
) {

    fun observe(ownerId: String): Flow<List<PlaylistEntity>> =
        localDataSource.observePlaylists(ownerId)

    suspend fun upsert(playlist: PlaylistEntity) =
        localDataSource.upsertPlaylist(playlist)

    /** Used by the initial /api/playlists hydration in Step 8.10. */
    suspend fun upsertDomain(playlist: Playlist) =
        localDataSource.upsertDomainPlaylist(playlist)

    suspend fun delete(id: String) =
        localDataSource.deletePlaylist(id)

    suspend fun addStream(playlistId: String, stream: StreamLink) =
        localDataSource.addStream(playlistId, stream)

    suspend fun removeStream(playlistId: String, streamUrl: String) =
        localDataSource.removeStream(playlistId, streamUrl)
}
