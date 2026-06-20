package com.streamify.app.data.local

import com.streamify.app.data.models.Playlist
import com.streamify.app.data.models.StreamLink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Phase 2 \u00b7 Step 2.3 \u2014 Local data source.
 *
 * Wraps both DAOs and pushes every suspending write to [ioDispatcher]
 * (default [Dispatchers.IO]). Read-side Flows are NOT wrapped because the
 * query itself is already off the main thread \u2014 the `Flow` emits when the
 * underlying query completes.
 *
 * Domain-facing helpers (e.g. [toggleFavorite]) keep the call sites in
 * Step 5.1 + 5.2 short while staying close enough to Room that a future
 * swap to a different ORM is mechanical.
 */
class LocalDataSource(
    private val favoriteDao: FavoriteDao,
    private val playlistDao: PlaylistDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    // \u2500\u2500\u2500 Favorites \u2500\u2500\u2500

    fun observeFavorites(): Flow<List<FavoriteEntity>> = favoriteDao.observeAll()

    suspend fun isFavorite(channelId: String): Boolean = withContext(ioDispatcher) {
        favoriteDao.isFavorite(channelId)
    }

    suspend fun toggleFavorite(favorite: FavoriteEntity) = withContext(ioDispatcher) {
        if (favoriteDao.isFavorite(favorite.channelId)) {
            favoriteDao.deleteById(favorite.channelId)
        } else {
            favoriteDao.upsert(favorite)
        }
    }

    suspend fun addFavorite(favorite: FavoriteEntity) = withContext(ioDispatcher) {
        favoriteDao.upsert(favorite)
    }

    suspend fun removeFavorite(channelId: String) = withContext(ioDispatcher) {
        favoriteDao.deleteById(channelId)
    }

    // \u2500\u2500\u2500 Playlists \u2500\u2500\u2500

    fun observePlaylists(ownerId: String): Flow<List<PlaylistEntity>> =
        playlistDao.observeByOwner(ownerId)

    suspend fun upsertPlaylist(playlist: PlaylistEntity) = withContext(ioDispatcher) {
        playlistDao.upsert(playlist)
    }

    suspend fun deletePlaylist(id: String) = withContext(ioDispatcher) {
        playlistDao.deleteById(id)
    }

    /**
     * Same shape as [addStream] but takes a full [Playlist] data model and
     * writes its items. Useful for the initial /api/playlists hydration
     * in Step 8.10.
     */
    suspend fun upsertDomainPlaylist(playlist: Playlist) = withContext(ioDispatcher) {
        playlistDao.upsert(
            PlaylistEntity(
                id = playlist.id,
                name = playlist.name,
                items = playlist.items,
                createdAt = playlist.createdAt,
                ownerId = playlist.ownerId
            )
        )
    }

    suspend fun addStream(playlistId: String, stream: StreamLink) = withContext(ioDispatcher) {
        val current = playlistDao.findById(playlistId) ?: return@withContext
        val updated = current.items + stream
        playlistDao.upsert(current.copy(items = updated))
    }

    suspend fun removeStream(playlistId: String, streamUrl: String) = withContext(ioDispatcher) {
        val current = playlistDao.findById(playlistId) ?: return@withContext
        val updated = current.items.filterNot { it.url == streamUrl }
        if (updated.size == current.items.size) return@withContext
        playlistDao.upsert(current.copy(items = updated))
    }
}
