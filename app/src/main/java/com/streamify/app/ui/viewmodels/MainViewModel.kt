package com.streamify.app.ui.viewmodels

import com.streamify.app.data.models.Banner
import com.streamify.app.data.models.Category
import com.streamify.app.data.models.Event
import com.streamify.app.data.models.Highlight
import com.streamify.app.data.remote.ApiResult
import com.streamify.app.data.repository.MainRepository
import com.streamify.app.ui.common.StateViewModel
import com.streamify.app.ui.common.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Phase 2 · Step 2.5 + Phase 3 · Step 3.3 reviewer fix.
 *
 * Single snapshot the Splash -> Main screen renders. Combines
 * the 4 sub-fetches MainViewModel orchestrates plus the
 * Step 3.3 banner fetch so the UI can observe one StateFlow
 * instead of wiring 5 separate subscriptions.
 *
 * Phase 3 · Step 3.3 reviewer fix (parallelization):
 * Tail latency drops to max(legs) instead of sum(legs). Structured
 * concurrency cancellation still propagates via the
 * [kotlinx.coroutines.coroutineScope] boundary.
 *
 * Banners are a soft dependency: a banner API hiccup does NOT abort
 * the boot snapshot (the carousel hides on emptyList).
 *
 * LIVE endpoint hardening (post-v1.1.0 logcat incident): `/api/live`
 * used to be a hard-failure sentinel — if it 404'd the entire boot
 * snapshot collapsed to Error, even though `/api/events`,
 * `/api/categories`, `/api/highlights`, `/api/banners` were all 200.
 * Root cause: the backend rolled out `/api/live` last in staging and
 * the prior MainViewModel.load() treated any live-failure as terminal,
 * so a single missing endpoint blanked the whole boot snapshot.
 * Fix: demote `/api/live` to a soft dep — failure falls back to an
 * empty list, the rest of the snapshot still renders. As a client-side
 * safety net, when `/api/live` fails we also seed `liveList` from any
 * `Event.isLive == true` rows already present in `/api/events`, so the
 * Home tab still surfaces in-progress matches regardless of backend
 * rollout state. We DO still surface an Error when BOTH `/api/live`
 * AND `/api/events` fail — that's the genuine "no content available"
 * case (offline / backend down) that warrants the snackbar-with-Retry
 * UI rather than four empty lists.
 */
data class MainSnapshot(
    val live: List<Event> = emptyList(),
    val events: List<Event> = emptyList(),
    val categories: List<Category> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    /** Phase 3 Step 3.3: banner carousel slides for the Home ViewPager2. */
    val banners: List<Banner> = emptyList()
)

class MainViewModel(
    private val mainRepository: MainRepository
) : StateViewModel<MainSnapshot>() {

    fun load() = launch {
        setState(UiState.Loading)

        // Captured lane values. Each lane's ApiResult is unwrapped below:
        // Success → append to the matching list; Failure → empty list.
        // We also retain the Failure throwables for `live` + `events` +
        // (post-v1.1.0) `categories` so the synthetic-fallback paths
        // below have the data they need. The dual-failure Error gate
        // (snackbar-with-Retry UI) only fires for live + events.
        var liveList:     List<Event>     = emptyList()
        var eventsList:   List<Event>     = emptyList()
        var catList:      List<Category>  = emptyList()
        var hlList:       List<Highlight> = emptyList()
        var bannerList:   List<Banner>    = emptyList()
        var liveFailure:  Throwable?      = null
        var eventsFailure: Throwable?     = null
        // post-v1.1.0 — track /api/categories failures so the synthetic
        // fallback further down has a signal to fire. The v1.1.0 path
        // dropped this — cats were a pure soft-dep that silently rendered
        // an empty chip group, which is the symptom users were reporting.
        var catFailure:   Throwable?      = null

        coroutineScope {
            val liveD   = async { mainRepository.fetchLive() }
            val eventsD = async { mainRepository.fetchEvents() }
            val catD    = async { mainRepository.fetchCategories() }
            val hlD     = async { mainRepository.fetchHighlights() }
            val bannerD = async { mainRepository.fetchBanners() }

            // Each lane unwrapped exactly once. ApiHttpException (e.g.
            // /api/live 404 during staged backend rollout) collapses to
            // empty list + retained throwable for the two core lanes
            // plus the categories lane (so the synthetic fallback below
            // can fire).
            unwrapList(liveD.await(),   onSuccess = { liveList   = it }, onFailure = { liveFailure   = it })
            unwrapList(eventsD.await(), onSuccess = { eventsList = it }, onFailure = { eventsFailure = it })
            unwrapList(catD.await(),    onSuccess = { catList    = it }, onFailure = { catFailure    = it })
            unwrapList(hlD.await(),     onSuccess = { hlList     = it }, onFailure = { /* soft-dep, empty default */ })
            unwrapList(bannerD.await(), onSuccess = { bannerList = it }, onFailure = { /* soft-dep, empty default */ })
        }

        // Phase 3 Step 3.3 client-side safety net: if /api/live 404'd but
        // /api/events already exposed `isLive=true` rows, surface them
        // through the `live` lane so the Home tab still has in-progress
        // matches. Costs one O(n) filter; runs only when /api/live failed.
        if (liveFailure != null) {
            liveList = eventsList.filter { it.isLive }
        }

        // post-v1.1.0 fix — synthetic Categories from /api/events
        // when /api/categories returns 404. Mirrors the live fallback
        // above so the boot snapshot's categories field stays warm
        // (Home tab chips + Categories tab both read this). Each
        // `Event.category` is a free-form string; we dedupe and
        // synthesize a stub Category with id=name (no iconUrl /
        // sortOrder available client-side without an admin roundtrip).
        // Empty / blank strings are dropped (legacy events may have
        // null category labels) so the synthetic list never advertises
        // a no-op filter.
        if (catFailure != null && eventsList.isNotEmpty()) {
            catList = eventsList.asSequence()
                .map { it.category }
                .filter { it.isNotBlank() }
                .distinct()
                .map { Category(id = it, name = it, isVisible = true) }
                .toList()
        }

        // Genuine "no content available" hard-error gate: BOTH the live
        // lane AND the events lane must fail before we surface Error.
        // Single-endpoint failure (the v1.1.0 /api/live 404 incident) is
        // gracefully absorbed because HomeViewModel still derives a
        // usable home list from `events`.
        if (liveFailure != null && eventsFailure != null) {
            setState(
                UiState.Error(
                    message = liveFailure.message ?: "Couldn't load events",
                    cause   = liveFailure
                )
            )
            return@launch
        }

        setState(
            UiState.Success(
                MainSnapshot(
                    live       = liveList,
                    events     = eventsList,
                    categories = catList,
                    highlights = hlList,
                    banners    = bannerList
                )
            )
        )
    }

    /**
     * Phase 3 · Step 3.2 polish — RETRY entry point.
     *
     * Resets the VM state to [UiState.Idle] so the next [load] call observes
     * a clean gate — caller-side `if (state is Idle) load()` checks pass on
     * a subsequent onCreate (Activity recreation scenario), and the visible
     * state sequence is Error -> Idle -> Loading -> Success/Error, which
     * makes the gate logic testable end-to-end. UI side: MainActivity's
     * Snackbar RETRY action calls this function — bypassing the onCreate
     * Idle gate (boot-only) but presenting a meaningful "back-to-Idle" UX
     * to anyone observing `state`.
     */
    fun retry() {
        setState(UiState.Idle)
        load()
    }
}

/**
 * Standard [ApiResult] routing for the boot-snapshot lanes. On Success we
 * forward the list into [onSuccess]; on Failure the throwable goes to
 * [onFailure] (or is dropped for pure soft-dep lanes like banners). Each
 * lane is awaited exactly once — the [ApiResult] reference passed in is
 * consumed.
 */
private inline fun <T> unwrapList(
    result: ApiResult<List<T>>,
    onSuccess: (List<T>) -> Unit,
    onFailure: (Throwable) -> Unit
) = when (result) {
    is ApiResult.Success -> onSuccess(result.value)
    is ApiResult.Failure -> onFailure(result.throwable)
}
