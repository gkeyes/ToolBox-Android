package io.toolbox.host.background

import io.toolbox.core.data.ResourceCapacity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull

/** Process-wide reservations, shared by foreground streams and delegated background HTTP. */
internal class NetworkResources(
    private val availableHeap: () -> Long = ResourceCapacity::availableHeapBytes,
    private val infrastructureAvailable: () -> Boolean = { systemAvailable() },
) {
    private data class Waiting(val bytes: Long, val result: CompletableDeferred<Reservation>, var granted: Reservation? = null)
    private val lock = Any()
    private val waiting = linkedMapOf<String, ArrayDeque<Waiting>>()
    private var reserved = 0L

    internal val reservedBytes: Long get() = synchronized(lock) { reserved }
    internal val waitingCount: Int get() = synchronized(lock) { waiting.values.sumOf { it.size } }

    suspend fun admit(owner: String, bytes: Long, control: ToolNetworkStreamControl): Reservation {
        require(bytes > 0)
        val entry = Waiting(bytes, CompletableDeferred())
        synchronized(lock) {
            waiting.getOrPut(owner) { ArrayDeque() }.addLast(entry)
            drain()
        }
        var delivered = false
        try {
            while (true) {
                control.requireActive()
                withTimeoutOrNull(50) { entry.result.await() }?.let { lease ->
                    control.requireActive()
                    delivered = true
                    return lease
                }
                // Memory pressure can change without another network operation releasing a reservation.
                synchronized(lock) { drain() }
            }
        } finally {
            synchronized(lock) {
                waiting[owner]?.let { queue -> queue.remove(entry); if (queue.isEmpty()) waiting.remove(owner) }
                if (!delivered) entry.granted?.close()
                drain()
            }
        }
    }

    fun reserveBuffer(maxBytes: Int, overheadPerByte: Int = 8): Reservation = synchronized(lock) {
        require(maxBytes > 0 && overheadPerByte > 0)
        val bytes = minOf(maxBytes.toLong(), (available() / overheadPerByte).coerceAtLeast(0))
        if (bytes < 1) throw ToolNetworkFailure("INSUFFICIENT_MEMORY")
        reserve(bytes * overheadPerByte, bytes.toInt())
    }

    fun reserveExact(bytes: Long): Reservation = synchronized(lock) {
        if (bytes < 0 || bytes > available()) throw ToolNetworkFailure("INSUFFICIENT_MEMORY")
        reserve(bytes, 0)
    }

    private fun available(): Long = if (infrastructureAvailable()) {
        (availableHeap().coerceAtLeast(0) / 2 - reserved).coerceAtLeast(0)
    } else 0

    private fun reserve(bytes: Long, rawBytes: Int): Reservation {
        reserved += bytes
        return Reservation(bytes, rawBytes)
    }

    // Round-robin among owners with queued work. This is scheduling fairness, not a per-tool quota.
    private fun drain() {
        while (waiting.isNotEmpty()) {
            val (owner, queue) = waiting.entries.first()
            val head = queue.first()
            if (head.bytes > available()) return
            queue.removeFirst()
            waiting.remove(owner)
            if (queue.isNotEmpty()) waiting[owner] = queue
            val reservation = reserve(head.bytes, 0)
            head.granted = reservation
            head.result.complete(reservation)
        }
    }

    inner class Reservation internal constructor(private val bytes: Long, val rawBytes: Int) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) synchronized(lock) { reserved -= bytes; drain() }
        }
    }

    companion object {
        @Volatile private var systemAvailable: () -> Boolean = { true }
        fun configureSystemAvailability(probe: () -> Boolean) { systemAvailable = probe }
        val shared = NetworkResources()
        // Admission above bounds in-flight operations and buffers; CPU count is not a network limit.
        val bodyDispatcher = Dispatchers.IO.limitedParallelism(Int.MAX_VALUE)
        // Conservative reservation for Call, TLS/socket/header bookkeeping; body storage is separate.
        const val OPERATION_BYTES = 64L * 1024
        const val AUTO_CHUNK_BYTES = 64 * 1024
    }
}
