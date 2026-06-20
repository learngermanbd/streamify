package com.streamify.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.streamify.app.data.models.StreamLink

/**
 * Phase 2 \u00b7 Step 2.3 \u2014 User-curated playlist row.
 *
 * `items` are stored as a JSON string via [Converters.streamLinkListToJson]
 * \u2014 mirrors Step 2.1's [com.streamify.app.data.models.Playlist] model
 * so the user app's data models are the source of truth.
 *
 * `ownerId` is currently a device-local user id (multi-account lands in
 * Phase 8). Indexed on ownerId + createdAt for the per-device listing flow
 * in Step 5.2 (Playlist CRUD UI).
 */
@Entity(
    tableName = "playlists",
    indices = [Index("ownerId"), Index("createdAt")]
)
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Persisted via [Converters]; default empty so the column is non-null. */
    val items: List<StreamLink> = emptyList(),
    val createdAt: Long,
    val ownerId: String
)
