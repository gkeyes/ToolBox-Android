package io.toolbox.host.background

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.Response

internal data class ToolNetworkRequest(
    val url: String,
    val method: NetworkRequestMethod,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val bodyIsJson: Boolean,
    val allowedHosts: Set<String>,
    val allowRedirects: Boolean,
    val timeoutMillis: Long,
    val maxResponseBytes: Int,
    val acceptHttpErrors: Boolean = true,
)

internal class ToolNetworkFailure(val code: String, val retryable: Boolean = false) : IOException(code)

internal class ToolNetworkStreamControl {
    private val lock = Any()
    private var call: Call? = null
    private var response: Response? = null
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
        if (!active) { response.close(); requireActive() }
    }

    fun cancel(code: String = "CANCELLED") {
        val resources = synchronized(lock) {
            if (cancellationCode == null) cancellationCode = code
            val resources = call to response
            call = null
            response = null
            resources
        }
        resources.first?.cancel()
        resources.second?.closeOnIo()
    }
}

internal class ToolNetworkStream(
    val response: Response,
    val finalUrl: String,
    private val control: ToolNetworkStreamControl,
    private val maxResponseBytes: Int,
) {
    private var receivedBytes = 0L
    private val reading = AtomicBoolean(false)

    fun read(maxChunkBytes: Int): ToolNetworkChunk {
        require(maxChunkBytes in 1..16 * 1024)
        if (!reading.compareAndSet(false, true)) throw ToolNetworkFailure("STREAM_BUSY")
        try {
            control.requireActive()
            val buffer = ByteArray(minOf(maxChunkBytes.toLong(), maxResponseBytes - receivedBytes + 1).toInt())
            val count = response.body.byteStream().read(buffer)
            control.requireActive()
            if (count < 0) return ToolNetworkChunk(ByteArray(0), true, receivedBytes)
            receivedBytes += count
            if (receivedBytes > maxResponseBytes) throw ToolNetworkFailure("RESULT_TOO_LARGE")
            return ToolNetworkChunk(buffer.copyOf(count), false, receivedBytes)
        } catch (error: IOException) {
            control.requireActive()
            throw error
        } finally {
            reading.set(false)
        }
    }
}

internal data class ToolNetworkChunk(val data: ByteArray, val done: Boolean, val receivedBytes: Long)

internal fun Response.closeOnIo() = Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { close() })
