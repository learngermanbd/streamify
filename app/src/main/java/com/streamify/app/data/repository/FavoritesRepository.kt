package com.streamify.app.data.repository

import com.streamify.app.data.local.FavoriteEntity
import com.streamify.app.data.local.LocalDataSource
import com.streamify.app.data.models.Channel
import kotlinx.coroutines.flow.Flow

/**
 * Phase 2 · Step 2.4 — Favorites Repository (local-only).
 *
 * Favorites are device-local (no remote endpoint), so this Repository
 * delegates straight to [LocalDataSource]. Step 5.1 UI pairs this with
 * [MainRepository.fetchChannels] for the swipe-to-favorite pattern.
 *
 * No try/catch: [LocalDataSource] already wraps every write in
 * `withContext(Dispatchers.IO)`, so CancellationException propagates
 * cleanly through this Repository facade.
 */
class FavoritesRepository(
    private val localDataSource: LocalDataSource
) {

    /** Cold Flow — re-emits whenever the favorites table changes. */
    fun observeFavorites(): Flow<List<FavoriteEntity>> =
        localDataSource.observeFavorites()

    /** Existence check for the heart-toggle path in Step 5.1. */
    suspend fun isFavorite(channelId: String): Boolean =
        localDataSource.isFavorite(channelId)

    /**
     * Splits inside [LocalDataSource] (the IO-dispatcher hop atomicifies
     * isFavorite → upsert/delete so two rapid taps can't race).
     */
    suspend fun toggle(favorite: FavoriteEntity) =
        localDataSource.toggleFavorite(favorite)

    suspend fun add(favorite: FavoriteEntity) =
        localDataSource.addFavorite(favorite)

    suspend fun remove(channelId: String) =
        localDataSource.removeFavorite(channelId)

    /**
     * Convenience builder used by Step 5.1 UI when the user stars a
     * channel row. `category` defaults to `channel.categoryId` if the
     * caller doesn't have a human label at hand.
     */
    fun fromChannel(channel: Channel, category: String = ""): FavoriteEntity =
        FavoriteEntity(
            channelId = channel.id,
            name = channel.name,
            logoUrl = channel.logoUrl,
            streamUrl = channel.streamUrl,
            category = category.ifEmpty { channel.category }
        )
}
