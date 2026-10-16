package io.toolbox.host.runtime

import io.toolbox.tool.runtime.RpcValue
import java.util.concurrent.CopyOnWriteArraySet

internal data class ToolNotificationOpen(val notificationId: String, val openedAt: Long)

/**
 * Taps on a tool's own notifications, kept until the tool's page has loaded and received them as
 * `notifications.open` events. Process memory is enough: the tap itself starts the tool in this process.
 */
internal object ToolNotificationOpens {
    const val EVENT_NOTIFICATION_OPEN = "notifications.open"

    private val pending = linkedMapOf<String, MutableList<ToolNotificationOpen>>()
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    fun record(toolId: String, notificationId: String, openedAt: Long = System.currentTimeMillis()) {
        synchronized(pending) {
            val opens = pending.getOrPut(toolId, ::mutableListOf)
            // A second tap on the same notification before the page loaded is one open.
            opens.removeAll { it.notificationId == notificationId }
            opens += ToolNotificationOpen(notificationId, openedAt)
        }
        listeners.forEach { it(toolId) }
    }

    /** Called with a tool id whenever a tap for that tool is recorded. */
    fun addListener(listener: (String) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /**
     * Hands the tool's pending taps to [emit] in tap order. Stops at the first one [emit] could not
     * deliver and keeps it and the rest for the next load.
     */
    fun deliver(toolId: String, emit: (ToolNotificationOpen) -> Boolean) {
        val opens = synchronized(pending) { pending.remove(toolId) } ?: return
        val undelivered = opens.dropWhile(emit)
        if (undelivered.isEmpty()) return
        synchronized(pending) {
            // Taps recorded while delivering come after the ones that failed.
            pending[toolId] = (undelivered + pending[toolId].orEmpty()).toMutableList()
        }
    }

    internal fun resetForTest() {
        synchronized(pending) { pending.clear() }
        listeners.clear()
    }
}

internal fun ToolNotificationOpen.toEvent(): RpcValue.ObjectValue = RpcValue.ObjectValue(
    mapOf(
        "id" to RpcValue.StringValue(notificationId),
        "openedAt" to RpcValue.Number(openedAt.toDouble()),
    ),
)
