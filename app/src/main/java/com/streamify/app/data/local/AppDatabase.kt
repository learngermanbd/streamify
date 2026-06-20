package com.streamify.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Phase 2 · Step 2.3 → Phase 5 · Step 5.5 v2 — AppDatabase (v2).
 *
 * Aggregates the tables:
 *  • favorites  — [FavoriteEntity] / [FavoriteDao]         (v1)
 *  • playlists  — [PlaylistEntity] / [PlaylistDao]         (v1)
 *  • notices    — [NoticeEntity]   / [NoticeDao]           (v2, Step 5.5)
 *
 * Migrations:
 *  • MIGRATION_1_2 — additive CREATE TABLE for `notices`. Existing
 *    `favorites` and `playlists` rows are preserved untouched. We
 *    deliberately do NOT want fallbackToDestructiveMigration to
 *    silently nuke the user's favorites / playlists when this lands.
 *  • fallbackToDestructiveMigration retained as a last-resort safety
 *    net for future unexpected schema mismatches (dev builds only).
 */
@Database(
    entities = [
        FavoriteEntity::class,
        PlaylistEntity::class,
        NoticeEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun noticeDao(): NoticeDao

    companion object {
        const val DATABASE_NAME = "streamify.db"

        /**
         * Phase 5 · Step 5.5 v2 migration: add `notices` table only.
         *
         * The SQL mirrors the @Entity declaration on NoticeEntity so
         * Room's KSP-generated DAO agrees byte-for-byte. Index
         * declarations translate to CREATE INDEX statements below.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notices` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `section` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER,
                        `attachmentsJson` TEXT NOT NULL,
                        `deepLink` TEXT,
                        `isPushSourced` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notices_section_createdAt` " +
                    "ON `notices` (`section`, `createdAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notices_isPushSourced_createdAt` " +
                    "ON `notices` (`isPushSourced`, `createdAt`)"
                )
            }
        }
    }
}
