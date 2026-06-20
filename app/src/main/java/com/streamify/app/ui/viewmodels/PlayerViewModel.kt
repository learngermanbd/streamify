package com.streamify.app.ui.viewmodels

import com.streamify.app.data.models.Channel
import com.streamify.app.data.remote.ApiResult
import com.streamify.app.data.repository.FavoritesRepository
import com.streamify.app.data.repository.MainRepository
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState

/**
 * Phase 2 · Step 2.5 — Player screen snapshot.
 *
 * `load(channelId)` resolves the channel from /api/channels (the
 * authoritative source for `streamUrl`) and stamps the favorite
 * status via [FavoritesRepository]. `toggleFavorite()` flips the
 * heart-shaped icon in Step 4.x player overlays.
 *
 * Takes BOTH repositories because the player screen needs the remote
 * channel detail AND the local favorite state in one place.
 */
data class PlayerSnapshot(
    val channel: Channel?,
    val isFavorite: Boolean
)

class PlayerViewModel(
    private val mainRepository: MainRepository,
    private val favoritesRepository: FavoritesRepository
) : StateViewModel<PlayerSnapshot>() {

    /**
     * Load (or reload) the channel that the player screen is bound to.
     * Resets [UiState] to Loading so the existing heart + title flip back
     * to a clean state during a screen re-bind.
     */
    fun load(channelId: String) = launch {
        setState(UiState.Loading)

        val channelsR = mainRepository.fetchChannels()
        if (channelsR !is ApiResult.Success) {
            val ex = channelsR.exceptionOrNull()
            setState(UiState.Error(ex?.message ?: "Couldn’t load channel", ex))
            return@launch
        }

        val channel = channelsR.value.firstOrNull { it.id == channelId }
        if (channel == null) {
            setState(UiState.Error("Channel not found"))
            return@launch
        }

        val fav = favoritesRepository.isFavorite(channel.id)
        setState(UiState.Success(PlayerSnapshot(channel = channel, isFavorite = fav)))
    }

    /**
     * Toggle the favorite state for the currently-loaded channel.
     * No-op if the player is not yet bound to a channel.
     */
    fun toggleFavorite() = launch {
        val snap = (state.value as? UiState.Success)?.value ?: return@launch
        val channel = snap.channel ?: return@launch

        // Reuse the Step 2.4 fromChannel(...) helper so the FavoriteEntity
        // shape lives in exactly one place (the Repository).
        favoritesRepository.toggle(favoritesRepository.fromChannel(channel))
        val nowFavorite = favoritesRepository.isFavorite(channel.id)
        setState(UiState.Success(snap.copy(isFavorite = nowFavorite)))
    }
}
