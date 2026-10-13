package io.toolbox.tool.runtime

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for a one-shot platform callback. Returns false when [register] throws or the callback
 * never arrives within [timeoutMillis], so a lost WebView callback cannot hang cleanup forever.
 * Late or repeated callbacks are ignored.
 */
internal suspend fun awaitPlatformCallback(
    timeoutMillis: Long,
    register: (onDone: () -> Unit) -> Unit,
): Boolean = withTimeoutOrNull(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        val done = AtomicBoolean(false)
        try {
            register {
                if (done.compareAndSet(false, true) && continuation.isActive) continuation.resume(true)
            }
        } catch (_: RuntimeException) {
            if (done.compareAndSet(false, true) && continuation.isActive) continuation.resume(false)
        }
    }
} ?: false
