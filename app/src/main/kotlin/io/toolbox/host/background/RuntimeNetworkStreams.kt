package io.toolbox.host.background

import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class RuntimeNetworkStreams(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Entry(val control: ToolNetworkStreamControl, var stream: ToolNetworkStream? = null, var expiry: Job? = null)
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val cancelledBeforeOpen = mutableSetOf<String>()
    private val retired = linkedSetOf<String>()
    private var active = true

    fun reserve(streamId: String, timeoutMillis: Long): ToolNetworkStreamControl = synchronized(lock) {
        if (!active) throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "网络流会话已结束。")
        if (cancelledBeforeOpen.remove(streamId)) {
            retire(streamId)
            throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "网络流已取消。")
        }
        if (streamId in retired) throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "网络流已结束，请创建新流。")
        if (entries.containsKey(streamId)) throw RuntimeHandlerException(RuntimeRpcErrorCode.BUSY, "网络流标识已使用。")
        if (entries.size >= 2) throw RuntimeHandlerException(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "最多同时打开两个网络流。")
        val entry = Entry(ToolNetworkStreamControl())
        entries[streamId] = entry
        entry.expiry = scope.launch {
            delay(timeoutMillis)
            release(streamId, entry.control, "NETWORK_TIMEOUT")
        }
        entry.control
    }

    fun attach(streamId: String, control: ToolNetworkStreamControl, stream: ToolNetworkStream) = synchronized(lock) {
        val entry = entries[streamId]
        if (!active || entry?.control !== control) {
            control.cancel()
            throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "网络流已取消。")
        }
        entry.stream = stream
    }

    fun get(streamId: String): ToolNetworkStream = synchronized(lock) {
        val entry = entries[streamId]
            ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "网络流不存在或已结束。")
        entry.stream ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.BUSY, "网络流仍在等待响应头。")
    }

    fun finish(streamId: String, stream: ToolNetworkStream) {
        val entry = synchronized(lock) {
            if (entries[streamId]?.stream !== stream) null
            else entries.remove(streamId).also { retire(streamId) }
        }
        entry?.expiry?.cancel()
        entry?.control?.cancel()
    }

    fun release(streamId: String, control: ToolNetworkStreamControl, code: String = "CANCELLED") {
        synchronized(lock) {
            if (entries[streamId]?.control === control) {
                entries.remove(streamId)?.expiry?.cancel()
                retire(streamId)
            }
        }
        control.cancel(code)
    }

    fun cancel(streamId: String) {
        val controls = synchronized(lock) {
            val entry = entries.remove(streamId)
            if (entry != null) {
                retire(streamId)
                entry.expiry?.cancel()
                return@synchronized listOf(entry.control)
            }
            if (!active || streamId in retired || streamId in cancelledBeforeOpen) return@synchronized emptyList()
            if (cancelledBeforeOpen.size < 128) {
                cancelledBeforeOpen.add(streamId)
                emptyList()
            } else {
                active = false
                scope.cancel()
                cancelledBeforeOpen.clear()
                entries.values.map(Entry::control).also { entries.clear() }
            }
        }
        controls.forEach { it.cancel() }
    }

    fun close() {
        val controls = synchronized(lock) {
            active = false
            scope.cancel()
            cancelledBeforeOpen.clear()
            retired.clear()
            entries.values.map(Entry::control).also { entries.clear() }
        }
        controls.forEach { it.cancel() }
    }

    fun clear() {
        val controls = synchronized(lock) {
            entries.keys.forEach(::retire)
            entries.values.forEach { it.expiry?.cancel() }
            entries.values.map(Entry::control).also { entries.clear() }
        }
        controls.forEach { it.cancel() }
    }

    private fun retire(streamId: String) {
        retired.add(streamId)
        if (retired.size > 128) retired.remove(retired.first())
    }
}
