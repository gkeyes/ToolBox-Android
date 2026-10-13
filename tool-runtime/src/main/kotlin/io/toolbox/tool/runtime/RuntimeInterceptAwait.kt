package io.toolbox.tool.runtime

import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * WebView's shouldInterceptRequest must return the response status and headers synchronously.
 * Instead of runBlocking (a nested event loop on WebView's loader thread that nothing outside
 * can stop), the request runs on [scope] and this thread only waits for its result. There is
 * no deadline, so a slow but working response is never cut off; cancelling [scope] (the
 * WebView was released) or the media session releases the waiting thread at once. A result
 * that arrives after the wait was abandoned is passed to [discard].
 */
internal fun <T> awaitInterceptResult(
    scope: CoroutineScope,
    discard: (T) -> Unit = {},
    block: suspend () -> T,
): T {
    val result = CompletableFuture<T>()
    val job = scope.launch {
        val value = try {
            block()
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            return@launch
        }
        if (!result.complete(value)) discard(value)
    }
    job.invokeOnCompletion { cause -> if (cause != null) result.completeExceptionally(cause) }
    try {
        return result.get()
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    } catch (interrupted: InterruptedException) {
        result.completeExceptionally(interrupted)
        job.cancel()
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Media request wait was interrupted")
    }
}
