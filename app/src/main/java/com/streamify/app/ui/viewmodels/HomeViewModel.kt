package com.streamify.app.ui.viewmodels

import com.streamify.app.data.models.Event
import com.streamify.app.data.remote.ApiResult
import com.streamify.app.data.repository.MainRepository
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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

        // post-v1.1.0 fix — same soft-dep shape as MainViewModel.load().
        // /api/live + /api/events fan out in parallel so a single-lane
        // failure no longer collapses the home tab to Error when the
        // other lane is healthy. This function is currently latent
        // (HomeFragment uses bindFromSnapshot from the activity-scoped
        // MainViewModel snapshot), but the public API stays safe under
        // any future caller.
        val (liveR, eventsR) = coroutineScope {
            val liveD = async { mainRepository.fetchLive() }
            val eventsD = async { mainRepository.fetchEvents() }
            liveD.await() to eventsD.await()
        }

        val liveFailure = liveR as? ApiResult.Failure
        val eventsFailure = eventsR as? ApiResult.Failure
        if (liveFailure != null && eventsFailure != null) {
            setState(
                UiState.Error(
                    eventsFailure.message ?: liveFailure.message
                        ?: "Couldn't load events",
                    eventsFailure.throwable
                )
            )
            return@launch
        }

        val live = (liveR as? ApiResult.Success)?.value ?: emptyList()
        val events = (eventsR as? ApiResult.Success)?.value ?: emptyList()

        // Live first; upcoming follows. distinctBy { it.id } matches
        // bindFromSnapshot's de-dup semantics so a row appearing in
        // both /api/live and /api/events doesn't double-render.
        setState(UiState.Success((live + events).distinctBy { it.id }))
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
