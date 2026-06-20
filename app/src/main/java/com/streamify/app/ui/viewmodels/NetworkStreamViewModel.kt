package com.streamify.app.ui.viewmodels

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.models.RecentStreamUrl
import com.streamify.app.data.prefs.PlayerPrefs
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Phase 5 · Step 5.3 — ViewModel for the Network Stream screen.
 *
 * Wraps the recent-URL DataStore flow in a UI-friendly [UiState]. The list
 * is small (≤ [PlayerPrefs.MAX_RECENT_URLS] entries) so emitting it as one
 * `Success` payload on every change is fine — DiffUtil collapses the
 * actual rebinds.
 *
 * Add/remove operations are non-suspend (`fun ... = launch { ... }`) so the
 * Fragment button-clicks stay synchronous and don't need a coroutine
 * wrapper. All suspending Repository calls use explicit
 * `try / catch CancellationException → rethrow / catch Throwable → log`
 * so a Fragment destroy mid-write doesn't silently drop the DataStore
 * update (Phase 2 audit SHOULD-FIX carried forward).
 */
class NetworkStreamViewModel(
    private val playerPrefs: PlayerPrefs
) : StateViewModel<List<RecentStreamUrl>>() {

    /**
     * Tie [PlayerPrefs.recentNetworkUrlsFlow] into [state]. Idempotent —
     * safe to call again from `repeatOnLifecycle(STARTED)` because
     * [StateViewModel.launch] cancels previous in-flight work before
     * re-subscribing.
     */
    fun refresh() = launch {
        try {
            playerPrefs.recentNetworkUrlsFlow.collect { list ->
                setState(UiState.Success(list))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            setState(UiState.Error(e.message ?: "Couldn't load recent URLs", e))
        }
    }

    /**
     * Add a new recent URL (called from the PLAY button). PlayerActivity's
     * URL-routing read fires BEFORE this so the user actually sees the
     * stream play even if DataStore write is slow — the WRITE is eventual
     * consistency, not gate-to-play.
     */
    fun add(url: String, formatLabel: String) = viewModelScope.launch {
        try {
            playerPrefs.addRecentNetworkUrl(url, formatLabel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("NetworkStreamVM", "add() failed for $url", e)
        }
    }

    /** Remove a recent URL. Same cancellation semantics as [add]. */
    fun remove(url: String) = viewModelScope.launch {
        try {
            playerPrefs.removeRecentNetworkUrl(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("NetworkStreamVM", "remove() failed for $url", e)
        }
    }
}
