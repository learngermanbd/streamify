package com.streamify.app.ui.common

/**
 * Phase 2 · Step 2.5 — sealed UiState<T> used by every ViewModel.
 *
 * Models the 4 canonical lifecycle states for a screen fetch:
 *   Idle      — initial state before any load() was called
 *   Loading   — load() in flight
 *   Success   — fetch completed with a value T
 *   Error     — fetch failed (network / 5xx / cancel map)
 *
 * `out T` keeps UiState covariant, so UiState<Nothing> (Idle, Loading,
 * Error) is assignable to UiState<Anything> in flows.
 */
sealed class UiState<out T> {
    object Idle : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<T>(val value: T) : UiState<T>()
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>()

    /**
     * Map a Success value to a new state, keeping Loading/Error/Idle as-is.
     * Useful inside `combine` and `mapLatest` flows.
     */
    inline fun <R> map(transform: (T) -> R): UiState<R> = when (this) {
        is Success -> Success(transform(value))
        is Error -> this
        Idle -> Idle
        Loading -> Loading
    }
}

/**
 * Bridges a [com.streamify.app.data.remote.ApiResult] from the data
 * layer into the [UiState] vocabulary. Failure (network / non-2xx /
 * generic throwable) becomes [UiState.Error]; Success becomes
 * [UiState.Success]. Re-uses the ApiResult.exceptionOrNull helper from
 * Step 2.2 instead of touching Failure fields directly.
 */
inline fun <T> com.streamify.app.data.remote.ApiResult<T>.toUiState(
    fallbackMessage: String = "Something went wrong"
): UiState<T> = when (this) {
    is com.streamify.app.data.remote.ApiResult.Success -> UiState.Success(value)
    is com.streamify.app.data.remote.ApiResult.Failure ->
        UiState.Error(exceptionOrNull()?.message ?: fallbackMessage, exceptionOrNull())
}
