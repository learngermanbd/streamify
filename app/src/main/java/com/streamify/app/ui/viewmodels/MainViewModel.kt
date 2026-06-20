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
 * concurrency cancellation still propagates (returning the inner
 * coroutineScope cancels siblings + the outer launch).
 *
 * Phase 3 · Step 3.3 reviewer fix (state-override bug fix):
 * The previous parallel version called `setState(Error)` inside the
 * inner scope then `return@coroutineScope`, but the OUTER `launch`
 * continued past the scope and re-emitted `setState(Success(emptyList))`,
 * silently overriding the error path. Captured nullable `List<T>` vars
 * + a `loadError` var carry the failure out of the scope so the outer
 * `launch` exits faithfully.
 *
 * Banners are a soft dependency: a banner API hiccup does NOT abort
 * the boot snapshot (the carousel hides on emptyList).
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

        // Captured lane values: nullable non-empty list == success,
        // `null` == that fetch failed. Soft dep (banner) defaults to
        // emptyList on failure.
        var liveList:   List<Event>     = emptyList()
        var eventsList: List<Event>     = emptyList()
        var catList:    List<Category>  = emptyList()
        var hlList:     List<Highlight> = emptyList()
        var bannerList: List<Banner>    = emptyList()
        var loadError:  UiState.Error?  = null

        coroutineScope {
            val liveD   = async { mainRepository.fetchLive() }
            val eventsD = async { mainRepository.fetchEvents() }
            val catD    = async { mainRepository.fetchCategories() }
            val hlD     = async { mainRepository.fetchHighlights() }
            val bannerD = async { mainRepository.fetchBanners() }

            // First-failure short-circuit on `live` (canonical sentinel —
            // if it fails, the rest of the snapshot is unusable). Returning
            // the inner scope ends sibling asyncs via cancellation; the
            // outer `launch` sees loadError != null and exits with Error.
            val liveResult = liveD.await()
            if (liveResult !is ApiResult.Success) {
                loadError = UiState.Error(
                    message = liveResult.exceptionOrNull()?.message
                        ?: "Couldn't load live events",
                    cause   = liveResult.exceptionOrNull()
                )
                return@coroutineScope
            }
            liveList   = liveResult.value
            eventsList = eventsD.await().successValueOrNull() ?: emptyList()
            catList    = catD.await().successValueOrNull() ?: emptyList()
            hlList     = hlD.await().successValueOrNull() ?: emptyList()
            // Soft dep — emptyList is the expected failure fallback for banners.
            bannerList = bannerD.await().successValueOrNull() ?: emptyList()
        }

        if (loadError != null) {
            setState(loadError!!)
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
 * Convenience extension: extract the wrapped List from an [ApiResult.Success],
 * or `null` if the result is [ApiResult.Failure]. Lets us assign cleanly to
 * nullable captured vars without an inline `is ApiResult.Success` check.
 */
private fun <T> ApiResult<List<T>>.successValueOrNull(): List<T>? =
    (this as? ApiResult.Success)?.value
