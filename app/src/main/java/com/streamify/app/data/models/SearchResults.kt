package com.streamify.app.data.models


/**
 * Phase 5 · Step 5.6 + Step 5.7 — Search hit aggregate.
 *
 * SearchViewModel reduces a [com.streamify.app.ui.viewmodels.MainViewModel]
 * MainSnapshot AND the activity-scoped CategoriesViewModel
 * [com.streamify.app.ui.viewmodels.CategoriesViewModel] state into
 * this aggregate by case-insensitive substring matching the typed
 * query against each shape's primary + secondary text fields:
 *  - Event     → title + teamA.name + teamB.name + category
 *  - Highlight → title
 *  - Channel   → name + category
 *
 * Phase 5.7: channels are now part of the search surface.
 * The SearchViewModel reaches both MainViewModel.state (events +
 * highlights) AND CategoriesViewModel.state (channels). Both VMs
 * are activity-scoped so SearchFragment can `.combine()` their flows.
 *
 * Passed to the adapter as three discrete lists so each rendering
 * branch can own its own section header + row layout (mirrors the
 * per-row layout patterns established in Phase 3 · Step 3.3/3.5).
 */
data class SearchResults(
    val events: List<Event>,
    val highlights: List<Highlight>,
    val channels: List<Channel> = emptyList(),
) {
    val isEmpty: Boolean
        get() = events.isEmpty() && highlights.isEmpty() && channels.isEmpty()

    val hasAny: Boolean
        get() = !isEmpty

    companion object {
        /** Empty aggregate for the Idle state. */
        val EMPTY = SearchResults(emptyList(), emptyList(), emptyList())

        /**
         * Reduce a snapshot's full lists down to the matching subset for
         * [query]. Prefix-aware ordering (matches-at-front) is intentionally
         * NOT here — adapters sort within section so a fresh user query
         * doesn't surprise the position of items already in view.
         *
         * Channel matching: case-insensitive substring on `name` OR
         * `category`. We deliberately do not match on `streamUrl` so a
         * user search for a sports bar URL like "espn.com/watch" doesn't
         * surface every channel that happens to point at that URL.
         */
        fun filter(
            events: List<Event>,
            highlights: List<Highlight>,
            channels: List<Channel>,
            query: String
        ): SearchResults {
            val q = query.trim()
            if (q.isEmpty()) return EMPTY
            val qLower = q.lowercase()
            return SearchResults(
                events = events.filter { event ->
                    event.title.lowercase().contains(qLower) ||
                        event.teamA.name.lowercase().contains(qLower) ||
                        event.teamB.name.lowercase().contains(qLower) ||
                        event.category.lowercase().contains(qLower)
                },
                highlights = highlights.filter { it.title.lowercase().contains(qLower) },
                channels = channels.filter { channel ->
                    channel.name.lowercase().contains(qLower) ||
                        channel.category.lowercase().contains(qLower)
                },
            )
        }
    }
}
