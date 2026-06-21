package com.streamify.app.ui.viewmodels

import com.streamify.app.data.models.Category
import com.streamify.app.data.models.Channel
import com.streamify.app.data.remote.ApiResult
import com.streamify.app.data.repository.MainRepository
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Phase 2 · Step 2.5 — Categories tab snapshot.
 *
 * Holds 2 separate channel lists so `selectCategory` re-filters the
 * already-fetched `allChannels` in O(n) instead of re-hitting the API.
 * `visibleChannels` is what the RecyclerView renders; `allChannels` is
 * the unfiltered source-of-truth used by the chip change.
 */
data class CategoriesSnapshot(
    val categories: List<Category>,
    val selectedCategoryId: String?,
    val allChannels: List<Channel>,
    val visibleChannels: List<Channel>
)

class CategoriesViewModel(
    private val mainRepository: MainRepository
) : StateViewModel<CategoriesSnapshot>() {

    fun refresh() = launch {
        setState(UiState.Loading)

        // post-v1.1.0 — parallel fetch: categories + channels.
        val (catsR, channelsR) = coroutineScope {
            val catsD = async { mainRepository.fetchCategories() }
            val channelsD = async { mainRepository.fetchChannels() }
            catsD.await() to channelsD.await()
        }

        val catsFailure = catsR as? ApiResult.Failure
        val channelsFailure = channelsR as? ApiResult.Failure

        var cats = (catsR as? ApiResult.Success)?.value ?: emptyList()
        var chans = (channelsR as? ApiResult.Success)?.value ?: emptyList()

        // ── Synthetic channels from /api/events ──────────────────
        // post-v1.1.0 fix — when /api/channels 404s AND the channels
        // list is empty, fetch /api/events and synthesise Channel
        // stubs from each Event row. This salvages the entire
        // Categories tab when channels + categories BOTH 404:
        // channels synthesised from events (here), categories
        // synthesised from those channels (next block).
        if (channelsFailure != null && chans.isEmpty()) {
            val eventsR = mainRepository.fetchEvents()
            chans = ((eventsR as? ApiResult.Success)?.value ?: emptyList())
                .map { event ->
                    Channel(
                        id        = event.id,
                        name      = event.title,
                        logoUrl   = event.teamA.logoUrl
                            ?: event.teamB.logoUrl,
                        streamUrl = event.streams.firstOrNull()?.url
                            ?: "",
                        category  = event.category,
                        isActive  = event.isLive,
                        sortOrder = 0
                    )
                }
        }

        // ── Synthetic categories from channels ────────────────────
        // post-v1.1.0 — cascades off `chans` which may have just been
        // synthesised from /api/events above, so a dual-endpoint
        // outage (categories + channels both 404) still yields a
        // usable chip group + channel grid.
        if (catsFailure != null && chans.isNotEmpty()) {
            cats = chans.asSequence()
                .map { it.category }
                .filter { it.isNotBlank() }
                .distinct()
                .map { Category(id = it, name = it, isVisible = true) }
                .toList()
        }

        // ── Dual-failure Error gate ───────────────────────────────
        // Only surface Error when every attempt to synthesise content
        // has been exhausted AND we still have nothing to render.
        if (cats.isEmpty() && chans.isEmpty() &&
            catsFailure != null && channelsFailure != null
        ) {
            setState(
                UiState.Error(
                    channelsFailure.message ?: catsFailure.message
                        ?: "Couldn't load categories",
                    channelsFailure.throwable
                )
            )
            return@launch
        }

        // Default selection: "All" (null id).
        setState(
            UiState.Success(
                CategoriesSnapshot(
                    categories          = cats,
                    selectedCategoryId = null,
                    allChannels        = chans,
                    visibleChannels    = chans
                )
            )
        )
    }

    /**
     * Re-filter `visibleChannels` from `allChannels` based on the
     * chosen chip. Null id = "All" (no filter).
     *
     * post-v1.1.0 fix — Backend convention for `Channel.category`
     * is ambiguous (could hold `Category.id` OR `Category.name`
     * depending on which admin endpoint the channel was created
     * through).  Match EITHER so chip selection works regardless
     * of the backend payload convention — costs at most one extra
     * string comparison per channel per chip tap, no visible UX cost.
     */
    fun selectCategory(categoryId: String?) {
        val current = (state.value as? UiState.Success)?.value ?: return
        val selected = current.categories.firstOrNull { it.id == categoryId }
        val visible = if (selected == null) {
            current.allChannels
        } else {
            current.allChannels.filter { channel ->
                channel.category == selected.id ||
                    channel.category == selected.name
            }
        }
        setState(
            UiState.Success(
                current.copy(
                    selectedCategoryId = categoryId,
                    visibleChannels    = visible
                )
            )
        )
    }
}
