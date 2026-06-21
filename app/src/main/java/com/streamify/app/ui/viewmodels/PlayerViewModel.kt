package com.streamify.app.ui.viewmodels

import com.streamify.app.data.models.Channel
import com.streamify.app.data.models.Event
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
 *
 * post-v1.1.0 — events fallback: when /api/channels 404s (partial
 * backend rollout), the player falls back to /api/events and synthesizes
 * Channel stubs, mirroring the CategoriesViewModel pattern. This closes
 * the last remaining hard-failure lane — the Categories tab already
 * renders synthetic channels from events, so tapping one must open a
 * working player, not an Error screen.
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
        val channel = if (channelsR is ApiResult.Success) {
            channelsR.value.firstOrNull { it.id == channelId }
        } else {
            null
        }

        // post-v1.1.0 — events fallback: when /api/channels 404s and the
        // Channel wasn't resolved, fetch /api/events and synthesize Channel
        // stubs. This closes the player gap when the Categories tab shows
        // synthetic channels (from events) and the user taps one — the
        // player must work end-to-end regardless of backend rollout state.
        val resolved = channel ?: synthesizeFromEvents(channelId)

        if (resolved == null) {
            val ex = channelsR.exceptionOrNull()
            setState(UiState.Error(ex?.message ?: "Channel not found", ex))
            return@launch
        }

        val fav = favoritesRepository.isFavorite(resolved.id)
        setState(UiState.Success(PlayerSnapshot(channel = resolved, isFavorite = fav)))
    }

    /**
     * Fallback: fetch /api/events and synthesize a Channel stub for
     * [channelId]. Returns null when /api/events also fails or the
     * event isn't found — the caller surfaces Error in that case.
     */
    private suspend fun synthesizeFromEvents(channelId: String): Channel? {
        val eventsR = mainRepository.fetchEvents()
        if (eventsR !is ApiResult.Success) return null

        val event = eventsR.value.firstOrNull { it.id == channelId } ?: return null

        return Channel(
            id = event.id,
            name = event.title,
            logoUrl = event.teamA.logoUrl ?: event.teamB.logoUrl ?: "",
            streamUrl = event.streams.firstOrNull()?.url ?: "",
            category = event.category,
            isActive = event.isLive
        )
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
