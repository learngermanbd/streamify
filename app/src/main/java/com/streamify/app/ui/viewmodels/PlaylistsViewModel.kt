package com.streamify.app.ui.viewmodels

import com.streamify.app.data.local.PlaylistEntity
import com.streamify.app.data.models.StreamLink
import com.streamify.app.data.repository.PlaylistRepository
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState

/**
 * Phase 5 · Step 5.2 — Playlists screen ViewModel.
 *
 * Wraps [PlaylistRepository] with [UiState] semantics for the
 * PlaylistsFragment list, and exposes CRUD operations for
 * `create / rename / delete / addStream / removeStream`. The repos
 * itself is local-only (Room + JSON column); no remote sync until
 * Step 8.10's `/api/playlists` hydration lands.
 *
 * `ownerId` is the device-local user id until Phase 8 introduces
 * multi-account; constant string at `DEVICE_OWNER_ID`.
 */
class PlaylistsViewModel(
    private val playlistRepository: PlaylistRepository,
    private val ownerId: String = DEVICE_OWNER_ID
) : StateViewModel<List<PlaylistEntity>>() {

    /**
     * Tie [observe] into [state]. Idempotent — repeat invocations are
     * safe (StateViewModel cancels in-flight launches before starting a
     * new one).
     */
    fun refresh() = launch {
        setState(UiState.Loading)
        runCatching {
            playlistRepository.observe(ownerId).collect { list ->
                setState(UiState.Success(list))
            }
        }.onFailure { e ->
            setState(UiState.Error(e.message ?: "Couldn't load playlists", e))
        }
    }

    /**
     * Create a new playlist with [name] (de-duplicates on existing name
     * by appending ` (n)` until unique).
     */
    fun create(name: String) = launch {
        val trimmed = name.trim().ifBlank { "Untitled playlist" }
        val current = (state.value as? UiState.Success)?.value.orEmpty()
        val unique = uniqueName(trimmed, current.map { it.name })
        playlistRepository.upsert(
            PlaylistEntity(
                id = java.util.UUID.randomUUID().toString(),
                name = unique,
                items = emptyList(),
                createdAt = System.currentTimeMillis(),
                ownerId = ownerId
            )
        )
    }

    fun rename(id: String, newName: String) = launch {
        val trimmed = newName.trim().ifBlank { return@launch }
        val current = playlistRepository.let { repo -> runCatching { /* no-op */ } }
        // findById is suspend on the DAO; the Repository facade doesn't
        // expose it, so we re-emit through observe() — slightly hacky
        // but avoids widening the API surface for one rename path.
        val playlists = (state.value as? UiState.Success)?.value.orEmpty()
        val target = playlists.firstOrNull { it.id == id } ?: return@launch
        val others = playlists.filter { it.id != id }.map { it.name }
        val unique = uniqueName(trimmed, others)
        playlistRepository.upsert(target.copy(name = unique))
    }

    fun delete(id: String) = launch {
        runCatching { playlistRepository.delete(id) }
    }

    fun addStream(playlistId: String, stream: StreamLink) = launch {
        runCatching { playlistRepository.addStream(playlistId, stream) }
    }

    fun removeStream(playlistId: String, streamUrl: String) = launch {
        runCatching { playlistRepository.removeStream(playlistId, streamUrl) }
    }

    /**
     * Make [candidate] unique across [existing] by appending
     * `" (n)"` increments until no collision remains. Cheap (linear in
     * list size, runs on a Room-write coroutine so no UI jank).
     */
    private fun uniqueName(candidate: String, existing: List<String>): String {
        if (candidate !in existing) return candidate
        var n = 2
        while ("$candidate ($n)" in existing) n++
        return "$candidate ($n)"
    }

    companion object {
        /**
         * Single-owner phase. Multi-account arrives in Step 8.10 when
         * the admin backend hydrates `/api/playlists`.
         */
        const val DEVICE_OWNER_ID = "device-local"
    }
}
