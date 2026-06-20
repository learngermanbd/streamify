package com.streamify.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.models.SearchResults
import com.streamify.app.data.prefs.PlayerPrefs
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase 5 · Step 5.6 + Step 5.7 — Search screen ViewModel.
 *
 * Pattern mirrors the existing NetworkStreamViewModel:
 *  - [UiState] sealed wrapping for the result list (matches the codebase's
 *    three-state contract: Loading / Success / Error).
 *  - StateViewModel base for `setState(...)` + structured-concurrency-safe
 *    `launch { … }` helper.
 *  - Reads sources via the activity-scoped [MainViewModel.state] Flow
 *    (events + highlights) AND activity-scoped [CategoriesViewModel.state]
 *    Flow (channels). Does NOT re-fetch from the network on init —
 *    searches the in-memory snapshots the app already loaded at startup.
 *
 * Debouncing semantics: query changes are debounced 300 ms (per
 * sportzfy_build_plan.html#step5.6) before re-filtering. Recent searches
 * are persisted via [PlayerPrefs.recentSearchesFlow] / `addRecentSearch`
 * / `removeRecentSearch` (added in this step as the search-data twin of
 * the existing recent-URL flow).
 *
 * Phase 5.7 widening: channels are now a first-class search surface.
 * The combine expression above pulls `catVm.state` to recover the
 * flat channel list `[CategoriesSnapshot.allChannels]` when the
 * categories snapshot is in `UiState.Success`.
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    app: Application,
    private val mainVm: MainViewModel,
    private val catVm: CategoriesViewModel,
    private val prefs: PlayerPrefs
) : StateViewModel<SearchResults>(UiState.Idle) {

    /** Latest typed query (without debounce). Updates live in the EditText. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** Most-recent searches, in newest-first order, hydrated from DataStore. */
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent

    init {
        viewModelScope.launch {
            prefs.recentSearchesFlow.collect { _recent.value = it }
        }
    }

    /**
     * Streams debounced search results whenever ANY of (query, main
     * snapshot, categories snapshot) change. 3-arg combine with the
     * question `debounced query` as the leading alpha so a user typing
     * doesn't trigger premature re-filter on every keystroke.
     */
    val results: StateFlow<UiState<SearchResults>> = combine(
        _query.debounce(300L).distinctUntilChanged(),
        mainVm.state,
        catVm.state,
    ) { q, upstreamState, catState ->
        // Best-effort extraction: when either upstream is still
        // Loading / Error / Idle, we mirror that state to the search
        // surface so the result row doesn't jump into "empty" before
        // a single fetch has resolved.
        when {
            upstreamState is UiState.Loading || catState is UiState.Loading ->
                UiState.Loading as UiState<SearchResults>
            upstreamState is UiState.Error ->
                UiState.Error(upstreamState.message, upstreamState.cause) as UiState<SearchResults>
            catState is UiState.Error && upstreamState !is UiState.Success ->
                UiState.Error(catState.message, catState.cause) as UiState<SearchResults>
            upstreamState is UiState.Success -> {
                val snap = upstreamState.value
                val allChannels = (catState as? UiState.Success<CategoriesSnapshot>)
                    ?.value
                    ?.allChannels
                    ?: emptyList()
                UiState.Success(
                    SearchResults.filter(
                        events = snap.events,
                        highlights = snap.highlights,
                        channels = allChannels,
                        query = q,
                    )
                ) as UiState<SearchResults>
            }
            else ->
                UiState.Loading as UiState<SearchResults>
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = UiState.Idle
    )

    /** Live update the typed query (called on every EditText change). */
    fun setQuery(q: String) {
        _query.value = q
    }

    /**
     * Persist a query to the recents list. Called when the user hits the
     * IME action / submit OR taps a result row. Blank queries are a no-op
     * so we don't pollute the chip strip with the empty string.
     */
    fun recordSearch(query: String) {
        val cleaned = query.trim()
        if (cleaned.isBlank()) return
        viewModelScope.launch { prefs.addRecentSearch(cleaned) }
    }

    /** Remove a single recent search — chip close-icon tap. */
    fun removeRecent(query: String) {
        viewModelScope.launch { prefs.removeRecentSearch(query) }
    }

    /** Clear all recent searches. Wired to a "Clear all" affordance in the
     *  recent searches chip row. */
    fun clearRecent() {
        viewModelScope.launch {
            val current = _recent.value
            current.forEach { prefs.removeRecentSearch(it) }
        }
    }

    /** Wired from SearchFragment.onDestroy — drop the query so an empty
     *  results state isn't carried into a fresh VM instance. */
    override fun onCleared() {
        super.onCleared()
        _query.value = ""
    }

    companion object {
        /** DEBOUNCE_MS = 300 matches the build-plan spec for Step 5.6. */
        const val DEBOUNCE_MS = 300L
    }
}
