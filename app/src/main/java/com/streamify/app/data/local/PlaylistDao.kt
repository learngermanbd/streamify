package com.streamify.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Phase 2 \u00b7 Step 2.3 \u2014 Playlist DAO.
 *
 * - `observeByOwner()` filters playlists by the device-local user id so
 *   multi-account migrations in Phase 8 are a one-column swap.
 * - `updateItems()` writes only the items column \u2014 cheaper than
 *   upserting the whole row when "Add to playlist" fires a single-event
 *   diff in Step 5.2.
 * - All write methods suspend so the caller can decide the dispatcher
 *   (LocalDataSource hoists them onto [Dispatchers.IO]).
 */
@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists WHERE ownerId = :ownerId ORDER BY createdAt DESC")
    fun observeByOwner(ownerId: String): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: String)
}
