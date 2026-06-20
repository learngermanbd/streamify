package com.streamify.app.data.remote

import com.streamify.app.data.models.Banner
import com.streamify.app.data.models.Category
import com.streamify.app.data.models.Channel
import com.streamify.app.data.models.Event
import com.streamify.app.data.models.Highlight
import com.streamify.app.data.models.Playlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 2 \u00b7 Step 2.2 \u2014 The single seam between the network and the UI layer.
 *
 * Each `fetchX()` returns an [ApiResult] wrapping a `List<T>` of the requested
 * Step 2.1 model. Failures (network, server error, malformed JSON) all collapse
 * to [ApiResult.Failure] carrying the underlying Throwable.
 *
 * Callers should treat [ApiResult] as a sealed type:
 *
 *   when (val r = remoteDataSource.fetchEvents()) {
 *       is ApiResult.Success -> render(r.value)
 *       is ApiResult.Failure -> showError(r.message)
 *   }
 *
 * Parsers live on each model's companion (`Event.listFromJsonArray`, etc.) so
 * anyone can call them without going through the data source.
 */
class RemoteDataSource(
    private val apiService: ApiService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun fetchEvents(): ApiResult<List<Event>> = fetchAndParse(
        fetch = { apiService.getEvents() },
        parse = { Event.listFromJsonArray(it) }
    )

    /** Subset of events where status == LIVE. */
    suspend fun fetchLive(): ApiResult<List<Event>> = fetchAndParse(
        fetch = { apiService.getLive() },
        parse = { Event.listFromJsonArray(it) }
    )

    suspend fun fetchChannels(): ApiResult<List<Channel>> = fetchAndParse(
        fetch = { apiService.getChannels() },
        parse = { Channel.listFromJsonArray(it) }
    )

    suspend fun fetchCategories(): ApiResult<List<Category>> = fetchAndParse(
        fetch = { apiService.getCategories() },
        parse = { Category.listFromJsonArray(it) }
    )

    suspend fun fetchHighlights(): ApiResult<List<Highlight>> = fetchAndParse(
        fetch = { apiService.getHighlights() },
        parse = { Highlight.listFromJsonArray(it) }
    )

    suspend fun fetchPlaylists(ownerId: String): ApiResult<List<Playlist>> = fetchAndParse(
        fetch = { apiService.getPlaylists(ownerId) },
        parse = { Playlist.listFromJsonArray(it) }
    )

    /**
     * Phase 3 · Step 3.3 — banner carousel slides. Backed by /api/banners;
     * inactive banners are excluded server-side so we filter nothing here.
     */
    suspend fun fetchBanners(): ApiResult<List<Banner>> = fetchAndParse(
        fetch = { apiService.getBanners() },
        parse = { Banner.listFromJsonArray(it) }
    )

    /**
     * Runs [fetch] on [ioDispatcher], then hands the raw body to [parse] on the
     * same dispatcher. Any thrown exception (network, HTTP status, JSON parse)
     * becomes [ApiResult.Failure] carrying the message. Uses named parameters so
     * the trailing-lambda rule does not bind [parse] to the wrong slot.
     */
    private suspend inline fun <T> fetchAndParse(
        crossinline fetch: suspend () -> String,
        crossinline parse: (rawJson: String) -> T
    ): ApiResult<T> = withContext(ioDispatcher) {
        try {
            ApiResult.Success(parse(fetch()))
        } catch (e: ApiHttpException) {
            ApiResult.Failure(e, "Server returned HTTP ${e.code}")
        } catch (e: CancellationException) {
            throw e // structured concurrency — never swallow
        } catch (e: Throwable) {
            ApiResult.Failure(e, e.message ?: e.javaClass.simpleName)
        }
    }
}
