package com.streamify.app.ui.viewmodels

import com.streamify.app.data.local.FavoriteEntity
import com.streamify.app.data.repository.FavoritesRepository
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState

/**
 * Phase 5 · Step 5.1 — Favorites screen ViewModel.
 *
 * Wraps [FavoritesRepository.observeFavorites] as a [UiState] so the Fragment
 * gets idle/loading/success/error semantics for free. Phase 5.1's swipe-to-
 * delete uses [remove] directly instead of pull-to-refresh, so this VM
 * stays the read-only truth source.
 */
class FavoritesViewModel(
    private val favoritesRepository: FavoritesRepository
) : StateViewModel<List<FavoriteEntity>>() {

    /**
     * Tie [observeFavorites] into [state]. Idempotent — calling it again
     * during configuration change cancels the prior collector via
     * [launch] (StateViewModel cancels in-flight launches before starting
     * a new one).
     */
    fun refresh() = launch {
        setState(UiState.Loading)
        runCatching {
            favoritesRepository.observeFavorites().collect { list ->
                setState(UiState.Success(list))
            }
        }.onFailure { e ->
            setState(UiState.Error(e.message ?: "Couldn't load favorites", e))
        }
    }

    /**
     * Remove a favorite by channel id. The Room Flow above re-fires the
     * list automatically — Fragment doesn't need to manually refresh.
     */
    fun remove(channelId: String) = launch {
        runCatching { favoritesRepository.remove(channelId) }
    }

    /**
     * Re-insert a previously-removed favorite (undo Snackbar path).
     * Caller hands the original entity back in.
     */
    fun restore(favorite: FavoriteEntity) = launch {
        runCatching { favoritesRepository.add(favorite) }
    }
}
