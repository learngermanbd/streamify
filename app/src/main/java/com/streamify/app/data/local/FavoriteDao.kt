package com.streamify.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Phase 2 \u00b7 Step 2.3 \u2014 Favorite channel DAO.
 *
 * - `observeAll()` returns a cold Flow so the UI re-renders whenever the
 *   favorites table changes (no manual refresh).
 * - `upsert()` uses [OnConflictStrategy.REPLACE] so the same channel id
 *   can be favorited repeatedly without throwing on the primary-key clash.
 * - `toggle` is NOT a DAO concept \u2014 the LocalDataSource owns the
 *   "is favorite?" branching so DAO stays a pure CRUD interface.
 */
@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE channelId = :channelId LIMIT 1")
    suspend fun findById(channelId: String): FavoriteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :channelId)")
    suspend fun isFavorite(channelId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE channelId = :channelId")
    suspend fun deleteById(channelId: String)

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()
}
