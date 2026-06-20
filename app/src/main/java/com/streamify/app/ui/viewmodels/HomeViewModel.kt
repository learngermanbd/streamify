package com.streamify.app.ui.viewmodels

import com.streamify.app.data.models.Event
import com.streamify.app.data.remote.ApiResult
import com.streamify.app.data.repository.MainRepository
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState

/**
 * Phase 2 · Step 2.5 — Home (LIVE + upcoming events) ViewModel.
 *
 * Single `List<Event>` snapshot so the Home adapter can render the
 * LIVE badge per-row without joining 2 collections at the adapter.
 * Live events come first, then scheduled.
 */
class HomeViewModel(
    private val mainRepository: MainRepository
) : StateViewModel<List<Event>>() {

    fun refresh() = launch {
        setState(UiState.Loading)

        val liveR = mainRepository.fetchLive()
        if (liveR !is ApiResult.Success) {
            val ex = liveR.exceptionOrNull()
            setState(UiState.Error(ex?.message ?: "Couldn\u2019t load live events", ex))
            return@launch
        }
        val eventsR = mainRepository.fetchEvents()
        if (eventsR !is ApiResult.Success) {
            val ex = eventsR.exceptionOrNull()
            setState(UiState.Error(ex?.message ?: "Couldn\u2019t load events", ex))
            return@launch
        }

        // Live first; upcoming follows.
        setState(UiState.Success(liveR.value + eventsR.value))
    }

    /**
     * Phase 3 · Step 3.3 — accept the activity-scoped [MainViewModel]
     * snapshot and re-emit the home list.
     *
     * Sorts LIVE first (live + scheduled merged), de-duplicates by Event.id
     * so the same match doesn't appear twice if it lands in both `live` and
     * `events`, then sorts by date + time within each tier.
     *
     * Used by HomeFragment when mainVm.state emits UiState.Success —
     * the canonical "mainVm.state.Success -> homeRv.adapter swap" wiring
     * the user requested. Steps 3.x — when Home acquires its own filtering
     * / search / favorite overlays — can layer state on top of this entry
     * point without disturbing the activity-scoped MainViewModel.
     */
    fun bindFromSnapshot(snapshot: MainSnapshot) {
        launch {
            val merged = (snapshot.live + snapshot.events)
                .distinctBy { it.id }
                .sortedWith(compareBy({ !it.isLive }, { it.date + it.time }))
            setState(UiState.Success(merged))
        }
    }
}
