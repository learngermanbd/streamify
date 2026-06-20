package com.streamify.app.data.repository

import android.util.Log
import com.streamify.app.data.local.NoticeDao
import com.streamify.app.data.local.NoticeEntity
import com.streamify.app.data.models.Notice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Phase 5 · Step 5.5 v2 — Notice repository.
 *
 * Treats [NoticeDao] as the Single Source of Truth. The Fragment
 * observes a Flow that is fed by Room; network /push writes are
 * funnel-into-Room so the Flow naturally re-emits.
 *
 * Why SSOT:
 *  - FCM can arrive while the Fragment is open. Direct DAO writes
 *    mean the observer picks up the push without any in-VM
 *    `SharedFlow<PushEvent>` plumbing.
 *  - Dashboard refresh and push happen on different lifecycles but
 *    converge on one canonical list.
 *
 * HTTP layer: reuses the shared [OkHttpClient] from
 * `app.network.httpClient`. Same fetch + cache + AuthInterceptor machinery
 * the rest of the app uses, so /api/notices requests share the
 * 10 MB on-disk HTTP cache.
 */
class NoticeRepository(
    private val httpClient: OkHttpClient,
    private val noticeDao: NoticeDao,
) {

    /**
     * `/api/notices` endpoint placeholder; admin DNS lands in Phase 8.
     * After Phase 8 the URL will come from `AppConfig.apiBaseUrl` via
     * the activity-scoped dependency — for now a hard-ref with the
     * placeholder backend keeps the wiring simple.
     */
    private val noticesUrl: String = "https://learngermanwith.fun/api/notices"

    /**
     * Cold Flow of active notices (i.e. those whose `expiresAt`
     * is null OR in the future at observer-emission time).
     *
     * Emits the domain [Notice] model directly so the ViewModel
     * doesn't have to know about [NoticeEntity].
     */
    fun observeActiveNotices(): Flow<List<Notice>> = noticeDao.observeAll().map { rows ->
        val now = System.currentTimeMillis()
        rows.asSequence()
            .filter { it.expiresAt == null || it.expiresAt > now }
            .map(NoticeEntity::toDomain)
            .toList()
    }

    /**
     * Fetch `/api/notices` and persist any new rows. Returns the
     * list of [Notice]s the server gave us (empty list on failure —
     * caller falls back to the v1 [com.streamify.app.data.remote.AppConfig.noticeText]).
     *
     * @param force when true, bypass the local cache and re-write
     *              ALL non-push rows. Used by the manual-refresh FAB.
     */
    suspend fun refreshFromServer(force: Boolean = false): List<Notice> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(noticesUrl)
                .header("X-App-Version", APP_VERSION)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Server returned ${response.code}; keeping cached notices.")
                    return@use emptyList<Notice>()
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use emptyList<Notice>()

                val parsed = try {
                    val obj = JSONObject(body)
                    val arr = obj.optJSONArray("notices")
                    if (arr != null) Notice.listFromJsonArray(arr)
                    else emptyList()
                } catch (e: JSONException) {
                    Log.w(TAG, "Malformed /api/notices payload; ignoring.", e)
                    return@use emptyList<Notice>()
                }

                if (parsed.isNotEmpty()) {
                    // Upsert so server-side edits and fresh pushes co-exist.
                    noticeDao.upsertAll(parsed.map(NoticeEntity::fromDomain))
                    if (force) {
                        // Wipe stale server rows that aren't in the new payload
                        // EXCEPT push-sourced ones (they don't come from /api/notices).
                        val activeIds = parsed.map { it.id }.toSet()
                        val currentIds = noticeDao.observeAll().first().map { it.id }.toSet()
                        val toDelete = (currentIds - activeIds).filter { !it.startsWith("push:") }
                        toDelete.forEach { noticeDao.deleteById(it) }
                    }
                }
                parsed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Network error contacting /api/notices: ${e.message}")
            emptyList()
        } catch (e: Throwable) {
            Log.w(TAG, "Unhandled refresh failure: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Insert a push-sourced [Notice]. Called from
     * [com.streamify.app.services.StreamifyMessagingService] when
     * an incoming FCM carries `data["type"] == "notice"`. Replaces
     * an existing row with the same id (`push:<message-id>`) so a
     * re-push overwrites instead of stacking.
     */
    suspend fun insertPushSourced(notice: Notice) = withContext(Dispatchers.IO) {
        noticeDao.upsert(NoticeEntity.fromDomain(notice.copy(isPushSourced = true)))
    }

    /**
     * Phase 5 · Step 5.7 — housekeeping sweep covering BOTH
     * push-sourced TTL (>7 days old) and server-sourced expiry
     * (`expiresAt` in the past). Two DAO calls so each is a
     * single-pass DELETE; log a summary line so we can see prune
     * activity in Sentry/logcat.
     */
    suspend fun pruneOldNotices() = withContext(Dispatchers.IO) {
        val sevenDaysAgoMs = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        val nowMs = System.currentTimeMillis()
        val removedPush   = noticeDao.prunePushSourced(sevenDaysAgoMs)
        val removedExpired = noticeDao.pruneExpiredServerRows(nowMs)
        if (removedPush > 0 || removedExpired > 0) {
            Log.i(
                TAG,
                "Pruned $removedPush push-sourced & $removedExpired expired server-sourced notices"
            )
        }
    }

    companion object {
        private const val TAG = "NoticeRepository"
        private const val APP_VERSION = "1.0.0"
    }
}
