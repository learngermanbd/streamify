package com.streamify.app.ui.viewmodels

import com.streamify.app.data.models.Category
import com.streamify.app.data.models.Channel
import com.streamify.app.data.remote.ApiResult
import com.streamify.app.data.repository.MainRepository
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState

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

        val catsR = mainRepository.fetchCategories()
        if (catsR !is ApiResult.Success) {
            val ex = catsR.exceptionOrNull()
            setState(UiState.Error(ex?.message ?: "Couldn’t load categories", ex))
            return@launch
        }
        val channelsR = mainRepository.fetchChannels()
        if (channelsR !is ApiResult.Success) {
            val ex = channelsR.exceptionOrNull()
            setState(UiState.Error(ex?.message ?: "Couldn’t load channels", ex))
            return@launch
        }

        // Default selection: "All" (null id).
        setState(
            UiState.Success(
                CategoriesSnapshot(
                    categories       = catsR.value,
                    selectedCategoryId = null,
                    allChannels      = channelsR.value,
                    visibleChannels  = channelsR.value
                )
            )
        )
    }

    /**
     * Re-filter `visibleChannels` from `allChannels` based on the
     * chosen chip. Null id = "All" (no filter).
     */
    fun selectCategory(categoryId: String?) {
        val current = (state.value as? UiState.Success)?.value ?: return
        val selected = current.categories.firstOrNull { it.id == categoryId }
        val visible = if (selected == null) current.allChannels
            else current.allChannels.filter { it.category == selected.name }
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
