package com.streamify.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 2 \u00b7 Step 2.3 \u2014 Favorite channel row.
 *
 * Mirrors enough of the Step 1.4 [com.streamify.app.data.models.Channel]
 * fields so the user can play their favorited stream offline (or before the
 * next /api/channels fetch). The PK is the upstream channel id.
 *
 * Added-at index supports the "Recent favorites" sort in Step 5.1
 * (swipe-to-delete + undo + heart animation).
 */
@Entity(
    tableName = "favorites",
    indices = [Index("addedAt")]
)
data class FavoriteEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val logoUrl: String?,
    val streamUrl: String,
    val category: String,
    val addedAt: Long = System.currentTimeMillis()
)
