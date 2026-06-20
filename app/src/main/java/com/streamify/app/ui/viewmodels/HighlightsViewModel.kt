package com.streamify.app.ui.viewmodels

import com.streamify.app.data.models.Highlight
import com.streamify.app.data.remote.ApiResult
import com.streamify.app.data.repository.MainRepository
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState

/**
 * Phase 2 · Step 2.5 — Highlights tab ViewModel.
 *
 * Single `List<Highlight>` snapshot; the Step 3.5 adapter renders the
 * 16:9 thumbnail card pattern from this.
 */
class HighlightsViewModel(
    private val mainRepository: MainRepository
) : StateViewModel<List<Highlight>>() {

    fun refresh() = launch {
        setState(UiState.Loading)

        val r = mainRepository.fetchHighlights()
        if (r !is ApiResult.Success) {
            val ex = r.exceptionOrNull()
            setState(UiState.Error(ex?.message ?: "Couldn’t load highlights", ex))
            return@launch
        }
        setState(UiState.Success(r.value))
    }
}
