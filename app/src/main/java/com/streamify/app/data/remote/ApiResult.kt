package com.streamify.app.data.remote

/**
 * Phase 2 \u00b7 Step 2.2 \u2014 Sealed result type for the network/data layer.
 *
 * We define our own (instead of reusing [kotlin.Result]) because callers want to
 * pattern-match with `when` on a known sealed class hierarchy, and because the
 * common kotlin.Result is final and can't be subclassed by future enhanced
 * variants (e.g. Loading, Empty).
 */
sealed class ApiResult<out T> {

    data class Success<T>(val value: T) : ApiResult<T>()
    data class Failure(
        val throwable: Throwable,
        val message: String = throwable.message ?: throwable.javaClass.simpleName
    ) : ApiResult<Nothing>()

    inline fun <R> fold(
        onSuccess: (T) -> R,
        onFailure: (Throwable, String) -> R
    ): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(throwable, message)
    }

    inline fun getOrNull(): T? = (this as? Success)?.value

    fun isSuccess(): Boolean = this is Success

    fun exceptionOrNull(): Throwable? = (this as? Failure)?.throwable
}

/**
 * Thrown by [ApiClient.get] when the server returns a non-2xx response code.
 * Carries the [code] and a short prefix of the response body for debugging.
 */
class ApiHttpException(
    val code: Int,
    val responseBody: String
) : java.io.IOException("HTTP $code: ${responseBody.take(256)}")
