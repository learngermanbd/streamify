package com.streamify.app.ui.viewmodels

import com.google.common.truth.Truth.assertThat
import com.streamify.app.data.models.*
import com.streamify.app.data.remote.ApiResult
import com.streamify.app.data.repository.MainRepository
import com.streamify.app.ui.common.UiState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Phase 9 · Step 9.1 — Unit tests for MainViewModel.
 */
class MainViewModelTest {

    private val repo = mockk<MainRepository>()
    private val vm = MainViewModel(repo)

    private fun makeEvent(id: String, title: String, teamA: Team, teamB: Team, isLive: Boolean = false) =
        Event(id = id, title = title, teamA = teamA, teamB = teamB, date = "", time = "", isLive = isLive, category = "sports", status = EventStatus.LIVE)

    @Test
    fun `load - success populates snapshot`() = runTest {
        val events = listOf(makeEvent("e1", "Match", Team("A"), Team("B")))
        val live = listOf(makeEvent("l1", "Live", Team("X"), Team("Y"), isLive = true))
        val categories = listOf(Category(id = "cat1", name = "Sports"))
        val highlights = listOf(Highlight(id = "h1", title = "Goal", thumbnailUrl = "t.jpg", videoUrl = "v.mp4", date = 1L, duration = 30, views = 500))

        coEvery { repo.fetchLive() } returns ApiResult.Success(live)
        coEvery { repo.fetchEvents() } returns ApiResult.Success(events)
        coEvery { repo.fetchCategories() } returns ApiResult.Success(categories)
        coEvery { repo.fetchHighlights() } returns ApiResult.Success(highlights)

        vm.load()
        val state = vm.state.value
        assertThat(state is UiState.Success<*>).isTrue()
        val snapshot = (state as UiState.Success<MainSnapshot>).value
        assertThat(snapshot.live.size).isEqualTo(1)
        assertThat(snapshot.events.size).isEqualTo(1)
        assertThat(snapshot.categories.size).isEqualTo(1)
        assertThat(snapshot.highlights.size).isEqualTo(1)
    }

    @Test
    fun `load - failure on live alone degrades gracefully`() = runTest {
        // Post-v1.1.0 logcat incident: /api/live rolled out last on the
        // backend and 404'd while /api/events, /api/categories,
        // /api/highlights were all 200. The previous hard-sentinel
        // turned one missing endpoint into a fully blanked boot snapshot.
        // Soft-dep: live failure → emptyList, snapshot still renders.
        val events = listOf(makeEvent("e1", "Match", Team("A"), Team("B")))
        val categories = listOf(Category(id = "cat1", name = "Sports"))
        val highlights = listOf(Highlight(id = "h1", title = "Goal", thumbnailUrl = "t.jpg", videoUrl = "v.mp4", date = 1L, duration = 30, views = 500))

        coEvery { repo.fetchLive() } returns ApiResult.Failure(throwable = java.io.IOException("HTTP 404: /api/live"))
        coEvery { repo.fetchEvents() } returns ApiResult.Success(events)
        coEvery { repo.fetchCategories() } returns ApiResult.Success(categories)
        coEvery { repo.fetchHighlights() } returns ApiResult.Success(highlights)

        vm.load()
        val state = vm.state.value
        assertThat(state is UiState.Success<*>).isTrue()
        val snapshot = (state as UiState.Success<MainSnapshot>).value
        assertThat(snapshot.live).isEmpty()
        assertThat(snapshot.events).hasSize(1)
        assertThat(snapshot.categories).hasSize(1)
        assertThat(snapshot.highlights).hasSize(1)
    }

    @Test
    fun `load - failure on both live and events surfaces Error`() = runTest {
        // Genuine "no content available" hard-error gate keeps the
        // snackbar-with-Retry path alive for the offline / backend-down
        // case rather than rendering four empty lists.
        coEvery { repo.fetchLive() } returns ApiResult.Failure(throwable = RuntimeException("live offline"))
        coEvery { repo.fetchEvents() } returns ApiResult.Failure(throwable = RuntimeException("events offline"))

        vm.load()
        val state = vm.state.value
        assertThat(state is UiState.Error).isTrue()
        assertThat((state as UiState.Error).message).contains("offline")
    }

    @Test
    fun `load - failure on live seeds liveList from events with isLive=true`() = runTest {
        // Positive coverage of the Phase 3 Step 3.3 client-side safety
        // net: when /api/live 404'd but /api/events carries isLive=true
        // rows, those rows should flow into MainSnapshot.live so the
        // Home tab can still render in-progress matches.
        val liveEvent = makeEvent("live1", "Live Match", Team("X"), Team("Y"), isLive = true)
        val upcomingEvent = makeEvent("up1", "Upcoming", Team("A"), Team("B"), isLive = false)
        coEvery { repo.fetchLive() } returns ApiResult.Failure(throwable = java.io.IOException("HTTP 404: /api/live"))
        coEvery { repo.fetchEvents() } returns ApiResult.Success(listOf(liveEvent, upcomingEvent))
        coEvery { repo.fetchCategories() } returns ApiResult.Success(emptyList())
        coEvery { repo.fetchHighlights() } returns ApiResult.Success(emptyList())

        vm.load()
        val state = vm.state.value
        assertThat(state is UiState.Success<*>).isTrue()
        val snapshot = (state as UiState.Success<MainSnapshot>).value
        assertThat(snapshot.live).hasSize(1)
        assertThat(snapshot.live.first().id).isEqualTo("live1")
        assertThat(snapshot.events).hasSize(2)
    }

    @Test
    fun `initial state is Idle`() {
        assertThat(vm.state.value).isEqualTo(UiState.Idle)
    }
}
