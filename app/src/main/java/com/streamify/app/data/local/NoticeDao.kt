package com.streamify.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Phase 5 · Step 5.5 v2 — Notice DAO.
 *
 * Expiration filtering happens client-side (`.map { it.filter(active) }`
 * downstream) instead of in the SQL query. Reasoning:
 *   1. `now()` is dynamic so a Flow with a parameterised query would
 *      restart the observer every second; filtering in Kotlin emits
 *      only when the underlying row set changes AND keeps the wire
 *      declaration readable.
 *   2. Re-evaluation when the device clock changes is a non-feature
 *      for this screen (notices with `expiresAt` in the past are
 *      dormant, not deleted).
 *
 * House-keeping (Phase 5.7 widening): two prune sweeps —
 *   - [prunePushSourced] removes push-sourced rows older than 7 days
 *     regardless of `expiresAt` (push-source TTL guard).
 *   - [pruneExpiredServerRows] removes server-sourced rows whose
 *     `expiresAt` is in the past, so the table doesn't accumulate
 *     dormant rows from the admin `/api/notices` fetch.
 *
 * Both calls return row counts so [NoticeRepository.pruneOldNotices]
 * can log housekeeping traces.
 */
@Dao
interface NoticeDao {

    /**
     * All rows ordered by section-then-creator DESC.
     * NoticeFragment's adapter sorts again at the section level using
     * priority DESC + createdAt DESC.
     *
     * Phase 5.7 note: the SQL previously hard-coded
     *     WHEN 'ALERT' THEN 0 WHEN 'INFO' THEN 1 WHEN 'PROMO' THEN 2
     * which silently collapses any future enum value to ordinal 3.
     * Keeping the explicit mapping for the 3 known sections (so the
     * SQL ordering stays deterministic) and using Kotlin-layer sorting
     * for any future enum value via [com.streamify.app.ui.viewmodels.NoticeSection]
     * (see [com.streamify.app.ui.adapters.NoticeAdapter.rowsOf]).
     */
    @Query("""
        SELECT * FROM notices
        ORDER BY
            CASE section
                WHEN 'ALERT' THEN 0
                WHEN 'INFO' THEN 1
                WHEN 'PROMO' THEN 2
                ELSE 3
            END ASC,
            priority DESC,
            createdAt DESC
    """)
    fun observeAll(): Flow<List<NoticeEntity>>

    @Query("SELECT * FROM notices WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): NoticeEntity?

    /** INSERT-OR-REPLACE so push re-deliveries collapse to one row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notice: NoticeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notices: List<NoticeEntity>)

    @Query("DELETE FROM notices WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Phase 5 v2 · Step 5.5 — sweep push-sourced notices where
     * [olderThan] milliseconds have elapsed since [createdAt].
     * Returns the row count for housekeeping logging.
     */
    @Query("DELETE FROM notices WHERE isPushSourced = 1 AND createdAt < :olderThan")
    suspend fun prunePushSourced(olderThan: Long): Int

    /**
     * Phase 5 · Step 5.7 — sweep server-sourced rows whose
     * `expiresAt` is in the past. The Flow above filters them out at
     * emit time but the table would otherwise grow unbounded.
     * Returns the row count for housekeeping logging.
     */
    @Query("DELETE FROM notices WHERE isPushSourced = 0 AND expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun pruneExpiredServerRows(now: Long): Int

    @Query("DELETE FROM notices")
    suspend fun deleteAll()
}
