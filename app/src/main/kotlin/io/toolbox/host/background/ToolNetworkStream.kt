package io.toolbox.host.background

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response

internal data class ToolNetworkRequest(
    val url: String,
    val method: NetworkRequestMethod,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val bodyIsJson: Boolean,
    val timeoutMillis: Long,
    val maxResponseBytes: Long?,
    val acceptHttpErrors: Boolean = true,
    val resourceOwner: String = "background",
)

internal class ToolNetworkFailure(val code: String, val retryable: Boolean = false) : IOException(code)

/** Owns the Call until the body is closed, not merely until headers arrive. */
internal class ToolNetworkStreamControl {
    private val lock = Any()
    private var call: Call? = null
    private var response: Response? = null
    private var reservation: AutoCloseable? = null
    private var cancellationCode: String? = null

    fun requireActive() = synchronized(lock) {
        cancellationCode?.let { throw ToolNetworkFailure(it) }
    }

    fun attach(call: Call) {
        val active = synchronized(lock) {
            if (cancellationCode != null) false else { this.call = call; true }
        }
        if (!active) { call.cancel(); requireActive() }
    }

    fun attach(response: Response) {
        val active = synchronized(lock) {
            if (cancellationCode != null) false else { this.response = response; true }
        }
        if (!active) { response.closeOnIo(); requireActive() }
    }

    fun attach(reservation: AutoCloseable) {
        val active = synchronized(lock) {
            if (cancellationCode != null) false else { this.reservation = reservation; true }
        }
        if (!active) { reservation.close(); requireActive() }
    }

    fun cancel(code: String = "CANCELLED") {
        val resources = synchronized(lock) {
            if (cancellationCode == null) cancellationCode = code
            Triple(call, response, reservation).also { call = null; response = null; reservation = null }
        }
        // Socket cancellation is synchronous and interrupts blocked reads; close may block, so runs on IO.
        resources.first?.cancel()
        resources.second?.closeOnIo()
        resources.third?.close()
    }

    suspend fun <T> readBody(onDiscard: (T) -> Unit = {}, block: () -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        try { NetworkResources.bodyDispatcher.dispatch(EmptyCoroutineContext, Runnable {
            try {
                requireActive()
                val result = block()
                continuation.resume(result, onCancellation = { _, value, _ -> onDiscard(value) })
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }) } catch (_: java.util.concurrent.RejectedExecutionException) {
            cancel()
            continuation.resumeWith(Result.failure(ToolNetworkFailure("RESOURCE_UNAVAILABLE")))
        }
    }
}

internal class ToolNetworkStream(
    val response: Response,
    val finalUrl: String,
    private val control: ToolNetworkStreamControl,
    private val maxResponseBytes: Long?,
    private val resources: NetworkResources,
) {
    private var receivedBytes = 0L
    private val reading = AtomicBoolean(false)

    suspend fun read(maxChunkBytes: Int): ToolNetworkChunk {
        require(maxChunkBytes > 0)
        if (!reading.compareAndSet(false, true)) throw ToolNetworkFailure("STREAM_BUSY")
        try {
            control.requireActive()
            val remaining = maxResponseBytes?.let { it - receivedBytes + 1 } ?: Long.MAX_VALUE
            return control.readBody(onDiscard = { it.release() }) {
                val reservation = resources.reserveBuffer(minOf(maxChunkBytes.toLong(), remaining).toInt())
                try {
                    val buffer = ByteArray(reservation.rawBytes)
                    val count = response.body.byteStream().read(buffer)
                    control.requireActive()
                    if (count < 0) ToolNetworkChunk(ByteArray(0), true, receivedBytes, reservation::close)
                    else {
                        receivedBytes = Math.addExact(receivedBytes, count.toLong())
                        if (maxResponseBytes != null && receivedBytes > maxResponseBytes) throw ToolNetworkFailure("RESULT_TOO_LARGE")
                        if (receivedBytes > MAX_SAFE_NETWORK_BYTES) throw ToolNetworkFailure("RESULT_TOO_LARGE")
                        ToolNetworkChunk(if (count == buffer.size) buffer else buffer.copyOf(count), false, receivedBytes, reservation::close)
                    }
                } catch (error: Throwable) { reservation.close(); throw error }
            }
        } catch (error: IOException) {
            control.requireActive()
            throw error
        } finally { reading.set(false) }
    }

    fun cancel() = control.cancel()
}

internal const val MAX_SAFE_NETWORK_BYTES = 9_007_199_254_740_991L
internal data class ToolNetworkChunk(val data: ByteArray, val done: Boolean, val receivedBytes: Long, val release: () -> Unit = {})
internal fun Response.closeOnIo() {
    try { NetworkResources.bodyDispatcher.dispatch(EmptyCoroutineContext, Runnable { runCatching { close() } }) }
    catch (_: java.util.concurrent.RejectedExecutionException) { runCatching { close() } }
}
