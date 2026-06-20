package com.streamify.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 2 · Step 2.5 — base class for every screen ViewModel.
 *
 * Sets up the `StateFlow<UiState<T>>` pattern + a structured-concurrency
 * helper [launch] that wraps every suspending call in viewModelScope
 * and maps non-cancel Throwables into `UiState.Error`. CancellationException
 * is always rethrown so coroutine cancellation propagates cleanly
 * (NEVER swallow cancels).
 *
 * `T` is the snapshot model (`MainSnapshot`, `List<Channel>`, etc.).
 * Initial state defaults to [UiState.Idle] so an uninitialized VM
 * screen render is a clean no-op.
 */
abstract class StateViewModel<T>(initial: UiState<T> = UiState.Idle) : ViewModel() {

    private val _state = MutableStateFlow<UiState<T>>(initial)

    /** Public read-only StateFlow<UiState<T>> — UI collects this. */
    val state: StateFlow<UiState<T>> = _state.asStateFlow()

    /** Update the StateFlow. Initial value is [UiState.Idle] unless overridden. */
    protected fun setState(value: UiState<T>) { _state.value = value }

    /**
     * Launch a coroutine in viewModelScope with structured-concurrency
     * cancellation preserved. Any non-cancel Throwable is mapped to
     * [UiState.Error] via [setState]. CancellationException is rethrown
     * unconditionally so structured concurrency stays intact — UI
     * navigation away still cancels in-flight loads.
     */
    protected fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setState(UiState.Error(e.message ?: e.javaClass.simpleName, e))
            }
        }
    }
}
