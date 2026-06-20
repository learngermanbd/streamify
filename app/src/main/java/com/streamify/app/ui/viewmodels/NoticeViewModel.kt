package com.streamify.app.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.models.Notice
import com.streamify.app.data.remote.AppConfig
import com.streamify.app.data.remote.RemoteConfigHelper
import com.streamify.app.data.repository.NoticeRepository
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Phase 5 · Step 5.5 v2 — ViewModel for the (now multi-section) Notice screen.
 *
 * Sources of truth, in priority order:
 *   1. Room: [NoticeRepository].observeActiveNotices() Flow. Highest priority
 *      because both a fresh push (FCM) and a manual refresh funnel into Room,
 *      so the Flow self-emits in both cases.
 *   2. Network: `/api/notices` is fetched on first display and on FAB
 *      refresh. Server rows are UPSERTed into Room so the Flow above
 *      picks them up on the next tick.
 *   3. Legacy fallback: when the Room list has zero items, look at
 *      `AppConfig.noticeText` (cached from `/api/config`). Non-blank
 *      string → emit [NoticeScreenState.LegacyMode]. Blank → emit
 *      [NoticeScreenState.Empty].
 *
 * Inherits `state: StateFlow<UiState<T>>` from [StateViewModel]. Populated
 * via `setState(...)` whenever either the Room Flow emits OR the
 * legacy fallback cache changes.
 *
 * Cancellation: every long-running suspend call uses [viewModelScope.launch]
 * directly (NOT the base's `launch` helper, since we want to handle errors
 * locally instead of letting the helper blanket-map them to UiState.Error).
 */
class NoticeViewModel(
    private val app: Application,
    private val httpClient: OkHttpClient,
    private val noticeRepo: NoticeRepository
) : StateViewModel<NoticeScreenState>(UiState.Success(NoticeScreenState.Loading)) {

    /** Cached fallback text (loaded once from /api/config). */
    private val fallbackText = MutableStateFlow<String?>(null)

    init {
        // 1. Continuous Room Flow collector — drives setState() on every
        //    Room emission (UPSERT, delete, expiry filter, etc.).
        viewModelScope.launch {
            try {
                noticeRepo.observeActiveNotices().collectLatest { notices ->
                    applyCombinedState(notices)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Room flow collection failed: ${e.message}", e)
                setState(UiState.Error(e.message ?: "Couldn't load notices", e))
            }
        }

        // 2. Lazy-load legacy fallback string once. Force=false honours
        //    the 30-min DataStore cache that RemoteConfigHelper maintains.
        viewModelScope.launch {
            try {
                val cfg: AppConfig = RemoteConfigHelper.fetchConfig(
                    context = app,
                    force = false,
                    httpClient = httpClient
                )
                fallbackText.value = cfg.noticeText.takeIf { it.isNotBlank() }
                // Snapshot call: trigger an immediate re-evaluation so
                // waiting for the next Room tick isn't required.
                applyCombinedState(noticeRepo.observeActiveNotices().first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "fallback RemoteConfig fetch failed: ${e.message}")
                fallbackText.value = null
                applyCombinedState(noticeRepo.observeActiveNotices().first())
            }
        }

        // 3. Background housekeeping — sweep push-sourced TTL AND
        //    expired server-sourced rows. Phase 5.7 widens the sweep
        //    so the table doesn't grow unbounded on slow admin panels.
        viewModelScope.launch {
            runCatching { noticeRepo.pruneOldNotices() }
                .onFailure { Log.w(TAG, "prune failed: ${it.message}") }
        }
    }

    /**
     * Combine the latest Room list + cached legacy text into one
     * UiState to feed the Fragment. Called from the Flow collector
     * AND after a manual legacy reload.
     */
    private fun applyCombinedState(notices: List<Notice>) {
        val legacy = fallbackText.value
        val next: UiState<NoticeScreenState> = when {
            notices.isNotEmpty() -> UiState.Success(buildListMode(notices))
            !legacy.isNullOrBlank() -> UiState.Success(NoticeScreenState.LegacyMode(legacy))
            else -> UiState.Success(NoticeScreenState.Empty)
        }
        setState(next)
    }

    /**
     * Trigger a manual refresh. Pulls `/api/notices` and UPSERTs to Room.
     * The Room observer above picks the change up automatically.
     *
     * @param force when true, server rows NOT in the new payload are
     *              deleted (push-sourced rows are preserved regardless).
     */
    fun refresh(force: Boolean = false) {
        setState(UiState.Success(NoticeScreenState.Loading))
        viewModelScope.launch {
            runCatching { noticeRepo.refreshFromServer(force = force) }
                .onFailure { Log.w(TAG, "refresh failed: ${it.message}", it) }
        }
    }

    /**
     * Reset the v1 fallback by re-fetching `/api/config` with force=true.
     * Called from the Retry button so the user sees fresh text after a
     * network error recovers.
     */
    fun reloadLegacyFallback() {
        viewModelScope.launch {
            try {
                val cfg = RemoteConfigHelper.fetchConfig(
                    context = app,
                    force = true,
                    httpClient = httpClient
                )
                fallbackText.value = cfg.noticeText.takeIf { it.isNotBlank() }
                applyCombinedState(noticeRepo.observeActiveNotices().first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "reloadLegacyFallback failed: ${e.message}")
            }
        }
    }

    private fun buildListMode(notices: List<Notice>): NoticeScreenState.ListMode {
        val grouped = notices.groupBy { it.section }
            .map { (sec, list) -> NoticeSectionBlock(sec, list) }
        return NoticeScreenState.ListMode(grouped)
    }

    companion object {
        private const val TAG = "NoticeVM"
    }
}
