package com.streamify.app.data.local

import android.content.Context
import androidx.room.Room

/**
 * Phase 2 · Step 2.3 → Phase 5 · Step 5.5 v2 — Local DI seam.
 *
 * Parallel to NetworkModule. Built once on Application.onCreate and
 * exposed via `app.local.*` so ViewModels / Repositories stay free of
 * Room / Context plumbing.
 *
 *  app.local.database        — the singleton [AppDatabase]
 *  app.local.favoriteDao     — [FavoriteDao]
 *  app.local.playlistDao     — [PlaylistDao]
 *  app.local.noticeDao       — [NoticeDao]      (v2, Step 5.5)
 *  app.local.localDataSource — [LocalDataSource] (favorites + playlists)
 *
 * Migration(1, 2) is the additive `notices` table from Step 5.5 v2.
 * fallbackToDestructiveMigration is retained as a dev-only safety
 * net for unexpected schema mismatches only — the linear v1 → v2
 * path is the explicit migration above, which preserves user data.
 */
class LocalModule(
    context: Context
) {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration() // safety net only — MIGRATION_1_2 must succeed
            .build()
    }

    val favoriteDao: FavoriteDao by lazy { database.favoriteDao() }
    val playlistDao: PlaylistDao by lazy { database.playlistDao() }
    val noticeDao: NoticeDao by lazy { database.noticeDao() }

    val localDataSource: LocalDataSource by lazy {
        LocalDataSource(favoriteDao = favoriteDao, playlistDao = playlistDao)
    }
}
