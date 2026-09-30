package io.toolbox.tool.runtime

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

data class RuntimePresentationState(
    val generation: String,
    val revision: Long,
    val foreground: Boolean,
    val closing: Boolean,
)

interface RuntimeLifecycleHandler {
    fun stateValue(): RpcValue.ObjectValue
    fun checkMethodAvailable(method: String)
    fun acknowledgeEvents(sequence: Long)
    fun completeFlush(token: String, saved: Boolean)
}

/** One state and one close ticket for the lifetime of a bridge generation. */
internal class RuntimePresentationCoordinator(
    private val generation: String,
    private val nowMillis: () -> Long,
) {
    data class CloseTicket(
        val token: String,
        val deadline: Long?,
        val result: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private var current = RuntimePresentationState(generation, 0, foreground = false, closing = false)
    private var closeTicket: CloseTicket? = null
    private var writesSealed = false
    private val pendingWrites = MutableStateFlow(0)
    private val openForEvents = MutableStateFlow(true)

    // Reserve before dispatch/JSON parsing, so unparsed admitted writes are inside the cutoff.
    @Synchronized fun admitOrdinaryRequest(): Boolean {
        if (writesSealed) return false
        pendingWrites.value += 1
        return true
    }

    @Synchronized fun releaseOrdinaryRequest() {
        check(pendingWrites.value > 0)
        pendingWrites.value -= 1
    }

    suspend fun awaitWrites() { pendingWrites.first { it == 0 } }
    suspend fun awaitOpenForEvents() { openForEvents.first { it } }

    @Synchronized fun state(): RuntimePresentationState = current

    @Synchronized fun setForeground(foreground: Boolean): Boolean {
        if (current.foreground == foreground) return false
        current = current.copy(revision = current.revision + 1, foreground = foreground)
        return true
    }

    @Synchronized fun beginClose(timeoutMillis: Long? = CLOSE_TIMEOUT_MILLIS): CloseTicket {
        closeTicket?.let { return it }
        return CloseTicket(UUID.randomUUID().toString(), timeoutMillis?.let { nowMillis() + it }).also {
            closeTicket = it
            openForEvents.value = false
            current = current.copy(revision = current.revision + 1, closing = true)
        }
    }

    @Synchronized fun isCurrent(ticket: CloseTicket): Boolean = closeTicket === ticket

    @Synchronized fun cancelClose() { closeTicket?.let(::cancelClose) }

    @Synchronized fun cancelClose(ticket: CloseTicket) {
        if (closeTicket !== ticket) return
        ticket.result.complete(false)
        closeTicket = null
        writesSealed = false
        openForEvents.value = true
        current = current.copy(revision = current.revision + 1, closing = false)
    }

    @Synchronized fun completeFlush(token: String, saved: Boolean) {
        val ticket = closeTicket
        if (ticket == null || ticket.token != token || (ticket.deadline != null && nowMillis() > ticket.deadline)) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "The close request is no longer current")
        }
        if (ticket.result.isCompleted) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "The close request was already acknowledged")
        }
        if (saved) writesSealed = true
        ticket.result.complete(saved)
    }

    @Synchronized fun release() {
        closeTicket?.result?.complete(false)
        closeTicket = null
        openForEvents.value = true
    }

    @Synchronized fun stateValue(): RpcValue.ObjectValue = RpcValue.ObjectValue(buildMap {
        put("generation", RpcValue.StringValue(generation))
        put("revision", RpcValue.Number(current.revision.toDouble()))
        put("foreground", RpcValue.Bool(current.foreground))
        put("closing", RpcValue.Bool(current.closing))
        closeTicket?.let { put("closeToken", RpcValue.StringValue(it.token)) }
    })

    @Synchronized fun checkMethodAvailable(method: String) {
        if (current.closing && method !in CLOSE_METHODS) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "The tool is saving before closing")
        }
    }

    companion object {
        const val CLOSE_TIMEOUT_MILLIS = 2_000L
        val CONTROL_METHODS = setOf("ready", "runtime.getState", "runtime.ackEvents", "runtime.flushComplete")
        private val CLOSE_METHODS = CONTROL_METHODS + setOf(
            "network.cancelStream", "storage.get", "storage.getMany", "storage.apply", "storage.set",
            "storage.remove", "storage.keys", "storage.clear", "storage.secure.get", "storage.secure.set",
            "storage.secure.remove",
        )
        val WRITE_METHODS = setOf(
            "storage.apply", "storage.set", "storage.remove", "storage.clear",
            "storage.secure.set", "storage.secure.remove", "background.stop",
        )
    }
}
