package com.streamify.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamify.app.data.models.*
import com.streamify.app.data.remote.ApiResult
import com.streamify.app.data.remote.RemoteDataSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MainRepositoryTest {

    private val remote = mockk<RemoteDataSource>()
    private val repo = MainRepository(remote)

    private fun makeEvent(id: String, title: String) = Event(
        id = id, title = title,
        teamA = Team("A"), teamB = Team("B"),
        date = "", time = "", category = "sports", status = EventStatus.LIVE
    )

    @Test
    fun `fetchEvents - success returns event list`() = runTest {
        val events = listOf(makeEvent("e1", "Match 1"))
        coEvery { remote.fetchEvents() } returns ApiResult.Success(events)
        val result = repo.fetchEvents()
        assertThat(result is ApiResult.Success).isTrue()
    }

    @Test
    fun `fetchEvents - failure propagates`() = runTest {
        coEvery { remote.fetchEvents() } returns ApiResult.Failure(throwable = RuntimeException("boom"))
        assertThat(repo.fetchEvents() is ApiResult.Failure).isTrue()
    }

    @Test
    fun `fetchLive - success returns event list`() = runTest {
        val live = listOf(makeEvent("live1", "Live Match").copy(isLive = true))
        coEvery { remote.fetchLive() } returns ApiResult.Success(live)
        val result = repo.fetchLive()
        assertThat(result is ApiResult.Success).isTrue()
    }

    @Test
    fun `fetchChannels - success returns channel list`() = runTest {
        val channels = listOf(Channel(id = "ch1", name = "Sports HD", streamUrl = "http://s", category = "sports"))
        coEvery { remote.fetchChannels() } returns ApiResult.Success(channels)
        val result = repo.fetchChannels()
        assertThat(result is ApiResult.Success).isTrue()
    }

    @Test
    fun `fetchCategories - success returns category list`() = runTest {
        val categories = listOf(Category(id = "cat1", name = "Football"))
        coEvery { remote.fetchCategories() } returns ApiResult.Success(categories)
        val result = repo.fetchCategories()
        assertThat(result is ApiResult.Success).isTrue()
    }

    @Test
    fun `fetchHighlights - success returns highlight`() = runTest {
        val highlights = listOf(Highlight(id = "h1", title = "Goal!", thumbnailUrl = "t.jpg", videoUrl = "v.mp4", date = 1L, duration = 30, views = 5000))
        coEvery { remote.fetchHighlights() } returns ApiResult.Success(highlights)
        val result = repo.fetchHighlights()
        assertThat(result is ApiResult.Success).isTrue()
    }

    @Test
    fun `fetchPlaylists - success`() = runTest {
        val playlists = listOf(Playlist(id = "p1", ownerId = "user1", name = "My List", items = emptyList(), createdAt = 0L))
        coEvery { remote.fetchPlaylists("user1") } returns ApiResult.Success(playlists)
        val result = repo.fetchPlaylists("user1")
        assertThat(result is ApiResult.Success).isTrue()
    }

    @Test
    fun `fetchPlaylists - failure propagates`() = runTest {
        coEvery { remote.fetchPlaylists(any()) } returns ApiResult.Failure(throwable = RuntimeException("gone"))
        assertThat(repo.fetchPlaylists("x") is ApiResult.Failure).isTrue()
    }
}
