package com.streamify.app.ui.common

import com.google.common.truth.Truth.assertThat
import com.streamify.app.data.remote.ApiResult
import org.junit.jupiter.api.Test

class UiStateTest {

    @Test
    fun `Idle is a singleton`() {
        assertThat(UiState.Idle === UiState.Idle).isTrue()
    }

    @Test
    fun `Loading is a singleton`() {
        assertThat(UiState.Loading === UiState.Loading).isTrue()
    }

    @Test
    fun `Success holds value`() {
        val state = UiState.Success("hello")
        assertThat(state.value).isEqualTo("hello")
    }

    @Test
    fun `Error holds message`() {
        val state = UiState.Error("network timeout")
        assertThat(state.message).isEqualTo("network timeout")
        assertThat(state.cause).isNull()
    }

    @Test
    fun `Error holds message and cause`() {
        val ex = RuntimeException("boom")
        val state = UiState.Error("failed", ex)
        assertThat(state.message).isEqualTo("failed")
        assertThat(state.cause).isSameInstanceAs(ex)
    }

    @Test
    fun `map - Success transforms value`() {
        val src = UiState.Success(42)
        val result = src.map { it * 2 }
        assertThat(result is UiState.Success<*>).isTrue()
        assertThat((result as UiState.Success<Int>).value).isEqualTo(84)
    }

    @Test
    fun `map - Loading stays Loading`() {
        val src: UiState<Nothing> = UiState.Loading
        val result: UiState<Int> = src.map { 0 }
        assertThat(result is UiState.Loading).isTrue()
    }

    @Test
    fun `map - Idle stays Idle`() {
        val src: UiState<Nothing> = UiState.Idle
        val result: UiState<Int> = src.map { 0 }
        assertThat(result is UiState.Idle).isTrue()
    }

    @Test
    fun `map - Error stays Error`() {
        val src = UiState.Error("fail")
        val result: UiState<Int> = src.map { 0 }
        assertThat(result is UiState.Error).isTrue()
    }

    @Test
    fun `toUiState - Success converts`() {
        val api: ApiResult<String> = ApiResult.Success("data")
        val ui = api.toUiState()
        assertThat(ui is UiState.Success<*>).isTrue()
    }

    @Test
    fun `toUiState - Failure converts`() {
        val ex = RuntimeException("network error")
        val api: ApiResult<String> = ApiResult.Failure(throwable = ex)
        val ui = api.toUiState()
        assertThat(ui is UiState.Error).isTrue()
    }

    @Test
    fun `toUiState - Failure with fallback`() {
        val ex = RuntimeException()
        val api: ApiResult<String> = ApiResult.Failure(throwable = ex)
        val ui = api.toUiState("custom fallback")
        val err = ui as UiState.Error
        assertThat(err.message).isEqualTo("custom fallback")
    }
}
