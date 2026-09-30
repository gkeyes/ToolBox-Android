package io.toolbox.tool.runtime

import io.toolbox.core.data.ResourceCapacity

internal class RuntimeEventBudget(private val availableHeap: () -> Long = ResourceCapacity::availableHeapBytes) {
    private var reserved = 0L
    @Synchronized fun acquire(bytes: Long): Boolean {
        // This is a native-event logical charge cap, not a renderer/whole-process memory limit.
        val limit = minOf(GLOBAL_BYTES, availableHeap().coerceAtLeast(0L))
        if (bytes < 0 || bytes > limit - reserved) return false
        reserved += bytes
        return true
    }
    @Synchronized fun release(bytes: Long) {
        check(bytes in 0..reserved)
        reserved -= bytes
    }
    @Synchronized fun reservedBytes(): Long = reserved
    companion object { const val GLOBAL_BYTES = 8L * 1024 * 1024 }
}

/** Retains the admission credit until JS confirms receipt, including the platform message queue. */
internal class RuntimeEventBuffer(private val budget: RuntimeEventBudget = sharedBudget) {
    data class Event(val sequence: Long, val encoded: String)
    private val pending = ArrayDeque<Event>()
    private val retained = linkedMapOf<Long, Long>()
    private var bytes = 0L
    private var sequence = 0L
    private var lastPosted = 0L
    private var acknowledged = 0L
    private var closed = false

    @Synchronized fun offer(encode: (Long) -> String): Boolean {
        if (closed || sequence == MAX_SEQUENCE || retained.size >= MAX_EVENTS) return false
        val next = sequence + 1
        val encoded = encode(next)
        val charge = encoded.length.toLong() * 2 + 128
        if (charge > MAX_BYTES - bytes || !budget.acquire(charge)) return false
        sequence = next
        bytes += charge
        retained[next] = charge
        pending.addLast(Event(next, encoded))
        return true
    }

    @Synchronized fun poll(): Event? = pending.removeFirstOrNull()?.also { lastPosted = it.sequence }
    @Synchronized fun hasPending(): Boolean = pending.isNotEmpty()

    @Synchronized fun acknowledge(upTo: Long) {
        require(upTo in 0..lastPosted) { "Invalid event acknowledgement" }
        if (upTo <= acknowledged) return
        val iterator = retained.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key > upTo) break
            bytes -= entry.value
            budget.release(entry.value)
            iterator.remove()
        }
        acknowledged = upTo
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        budget.release(bytes)
        bytes = 0
        pending.clear()
        retained.clear()
    }
    @Synchronized fun retainedBytes(): Long = bytes
    @Synchronized fun retainedCount(): Int = retained.size

    companion object {
        const val MAX_BYTES = 1024L * 1024
        const val MAX_EVENTS = 4096
        const val BATCH_SIZE = 32
        const val BATCH_NANOS = 2_000_000L
        private const val MAX_SEQUENCE = 9_007_199_254_740_991L
        private val sharedBudget = RuntimeEventBudget()
    }
}
