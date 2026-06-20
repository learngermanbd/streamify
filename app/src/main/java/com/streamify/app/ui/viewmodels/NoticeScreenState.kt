package com.streamify.app.ui.viewmodels

import com.streamify.app.data.models.Notice
import com.streamify.app.data.models.NoticeSection

/**
 * Phase 5 · Step 5.5 v2 — what the NoticeFragment renders on each
 * UiState tick.
 *
 * Four render branches + Loading:
 *
 *  - [ListMode]   — list of [NoticeSectionBlock] (ALERT > INFO > PROMO
 *                   ordering applied at construction time). Drives
 *                   the [com.streamify.app.ui.adapters.NoticeAdapter]
 *                   RecyclerView.
 *  - [LegacyMode] — single free-form string. Emitted when the v2
 *                   room/network pipeline returns 0 items AND
 *                   [com.streamify.app.data.remote.AppConfig.noticeText]
 *                   is non-blank. Mirrors the v1 minimal screen.
 *  - [Empty]      — both pipelines empty. Drives the empty-state
 *                   card / ImageView we already shipped for v1.
 *  - [Loading]    — bridges UiState.Loading to a screen-state so the
 *                   existing render() in NoticeFragment can dispatch
 *                   via a single sealed switch.
 *
 * The Fragment's loading-only branch reads UiState.Loading directly
 * (kept at the UiState layer so the Loading spinner doesn't have to
 * round-trip through NoticeScreenState). NoticeScreenState is the
 * INSIDE of UiState.Success.
 */
sealed interface NoticeScreenState {

    data object Loading : NoticeScreenState

    /**
     * Multi-section render. sections MUST be in stable order
     * (ALERT → INFO → PROMO) — divider bands depend on it.
     */
    data class ListMode(
        val sections: List<NoticeSectionBlock>
    ) : NoticeScreenState

    /**
     * v1 fallback: single TextView body in a scrollable card. Used
     * when the v2 notice pipeline yields zero items but
     * AppConfig.noticeText is non-blank.
     */
    data class LegacyMode(val text: String) : NoticeScreenState

    /** No notices anywhere — drives the v1 empty card. */
    data object Empty : NoticeScreenState
}

/**
 * One section's worth of [Notice] rows (header + N items). Passed in
 * to [com.streamify.app.ui.adapters.NoticeAdapter] which mixes in
 * its own Header item type to render via a single RecyclerView.
 */
data class NoticeSectionBlock(
    val section: NoticeSection,
    val notices: List<Notice>
)
