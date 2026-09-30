package io.toolbox.host.background

import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeNetworkMediaRoute
import io.toolbox.tool.runtime.RuntimeNetworkMediaSession
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.UUID

/** Owns both pending header requests and bodies retained by WebView for each local media URL. */
internal class RuntimeNetworkMediaSessions(private val origin: String?) {
    internal class Entry(val id: String, val url: String, val source: String, val kind: String?) {
        val controls = mutableSetOf<ToolNetworkStreamControl>()
        var mime: String? = null
    }
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val routes = mutableMapOf<String, Entry>()
    private val cancelledBeforeOpen = mutableSetOf<String>()
    private var active = true

    fun reserve(id: String, source: String, kind: String?): Entry = synchronized(lock) {
        if (!active || origin == null) throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "媒体运行环境已结束。")
        if (cancelledBeforeOpen.remove(id)) throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "媒体会话已取消。")
        if (id in entries) throw RuntimeHandlerException(RuntimeRpcErrorCode.BUSY, "媒体会话标识已使用。")
        val token = UUID.randomUUID().toString().replace("-", "")
        val entry = Entry(id, origin.removeSuffix("/") + RuntimeNetworkMediaRoute.PREFIX + token, source, kind)
        entries[id] = entry
        routes[token] = entry
        entry
    }

    fun session(entry: Entry): RuntimeNetworkMediaSession = synchronized(lock) {
        requireCurrent(entry)
        RuntimeNetworkMediaSession(entry.id, entry.url)
    }

    fun find(url: String): Entry? = synchronized(lock) {
        origin?.let { RuntimeNetworkMediaRoute.token(url, it) }?.let(routes::get)
    }

    fun mime(entry: Entry): String? = synchronized(lock) { requireCurrent(entry); entry.mime }

    fun rememberMime(entry: Entry, mime: String) = synchronized(lock) { requireCurrent(entry); entry.mime = mime }

    fun request(entry: Entry): ToolNetworkStreamControl = synchronized(lock) {
        requireCurrent(entry)
        ToolNetworkStreamControl().also { entry.controls.add(it) }
    }

    fun requireCurrent(entry: Entry) = synchronized(lock) {
        if (!active || entries[entry.id] !== entry) throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "媒体会话已关闭。")
    }

    fun finish(entry: Entry, control: ToolNetworkStreamControl) {
        synchronized(lock) { entry.controls.remove(control) }
        control.cancel()
    }

    fun cancel(id: String) {
        val controls = synchronized(lock) {
            val entry = entries.remove(id)
            if (entry == null) {
                if (active) cancelledBeforeOpen.add(id)
                emptyList()
            } else {
                RuntimeNetworkMediaRoute.token(entry.url, requireNotNull(origin))?.let(routes::remove)
                entry.controls.toList().also { entry.controls.clear() }
            }
        }
        controls.forEach { it.cancel() }
    }

    fun clear(end: Boolean = false) {
        val controls = synchronized(lock) {
            if (end) active = false
            entries.values.flatMap { it.controls }.also {
                entries.values.forEach { entry -> entry.controls.clear() }
                entries.clear()
                routes.clear()
                cancelledBeforeOpen.clear()
            }
        }
        controls.forEach { it.cancel() }
    }
}
