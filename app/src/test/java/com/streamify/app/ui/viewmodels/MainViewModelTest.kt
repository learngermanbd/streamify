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
    fun `load - failure on first fetch stops early`() = runTest {
        coEvery { repo.fetchLive() } returns ApiResult.Failure(throwable = RuntimeException("offline"))

        vm.load()
        val state = vm.state.value
        assertThat(state is UiState.Error).isTrue()
        assertThat((state as UiState.Error).message).contains("offline")
    }

    @Test
    fun `load - failure on second fetch after live succeeds`() = runTest {
        coEvery { repo.fetchLive() } returns ApiResult.Success(emptyList())
        coEvery { repo.fetchEvents() } returns ApiResult.Failure(throwable = RuntimeException("events error"))

        vm.load()
        val state = vm.state.value
        assertThat(state is UiState.Error).isTrue()
        assertThat((state as UiState.Error).message).contains("events error")
    }

    @Test
    fun `initial state is Idle`() {
        assertThat(vm.state.value).isEqualTo(UiState.Idle)
    }
}
