package io.toolbox.tool.runtime

import io.toolbox.core.data.ResourceCapacity
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.Handler
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.security.SecureRandom
import java.lang.ref.WeakReference
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

fun interface RuntimeBridgeProvider {
    fun create(runtime: PreparedToolRuntime): RuntimeBridgeConfiguration
}

data class RuntimeBridgeConfiguration(
    val authorization: RuntimeAuthorizationPolicy,
    val handlers: RuntimeCoreHandlers,
    val hostVersion: String,
    val generation: String = "",
    val serviceHandlers: RuntimeServiceHandlers = RuntimeServiceHandlers(),
    val systemHandlers: RuntimeSystemHandlers = RuntimeSystemHandlers(),
    val browserLaunchGuard: () -> Unit = {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "No foreground tool is available")
    },
) {
    init {
        require(hostVersion.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")))
    }

}

class RuntimeBridgeSession internal constructor(
    private val identity: RuntimeSessionIdentity,
    authorization: RuntimeAuthorizationPolicy,
    handlers: RuntimeCoreHandlers,
    serviceHandlers: RuntimeServiceHandlers = RuntimeServiceHandlers(),
    systemHandlers: RuntimeSystemHandlers = RuntimeSystemHandlers(),
    private val browserLaunchGuard: () -> Unit = {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "No foreground tool is available")
    },
    ordinaryBudget: RuntimeRequestBudget = RuntimeRequestBudget(),
    ordinaryDispatcher: CoroutineDispatcher? = null,
) {
    // A JSON message is decoded as UTF-16. This is current allocation capacity,
    // not the obsolete per-tool manifest quota.
    private val maxPayloadBytes: Int
        get() = (ResourceCapacity.availableHeapBytes() / Char.SIZE_BYTES).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    private val active = AtomicBoolean(true)
    private val navigationEpoch = AtomicLong()
    private val eventReady = AtomicBoolean(false)
    private val inFlightIds = ConcurrentHashMap.newKeySet<Pair<Long, String>>()
    private val nonWriteJobs = ConcurrentHashMap<Job, Long>()
    private val pendingDeliveries = ConcurrentHashMap.newKeySet<ReplyDelivery>()
    private val eventProxy = AtomicReference<JavaScriptReplyProxy?>(null)
    private val events = RuntimeEventBuffer()
    private val presentation = RuntimePresentationCoordinator(identity.generation, android.os.SystemClock::elapsedRealtime)
    private val pendingState = AtomicReference<String?>(null)
    private val drainScheduled = AtomicBoolean(false)
    private val controls = RuntimeSessionJobs(
        localBudget = RuntimeRequestBudget(4 * 1024),
        globalBudget = controlBudget,
    )
    private val lifecycle = object : RuntimeLifecycleHandler {
        override fun stateValue() = presentation.stateValue()
        override fun checkMethodAvailable(method: String) = presentation.checkMethodAvailable(method)
        override fun acknowledgeEvents(sequence: Long) = events.acknowledge(sequence)
        override fun completeFlush(token: String, saved: Boolean) = presentation.completeFlush(token, saved)
    }
    private val jobs = if (ordinaryDispatcher == null) RuntimeSessionJobs(localBudget = ordinaryBudget)
        else RuntimeSessionJobs(dispatcher = ordinaryDispatcher, localBudget = ordinaryBudget)
    private var attachedView = WeakReference<WebView>(null)
    // A recovered/background runtime may never have a window. View.post queues
    // work until attachment; native replies and events must target the UI looper.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val dispatcher = RuntimeRpcDispatcher(
        identity = identity,
        authorization = authorization,
        handlers = handlers,
        serviceHandlers = serviceHandlers,
        systemHandlers = systemHandlers,
        lifecycle = lifecycle,
        browserLaunchGuard = { browserLaunchGuard(); requireForegroundSession() },
        foregroundInteractionGuard = { withContext(Dispatchers.Main.immediate) { requireForegroundSession() } },
    )
    private val sessionCleanup = systemHandlers.sessionCleanup
    private val network = serviceHandlers.network

    private class ReplyDelivery(val epoch: Long, encoded: String, completed: () -> Unit) {
        private val message = AtomicReference<String?>(encoded)
        private val completion = AtomicReference<(() -> Unit)?>(completed)
        fun encoded(): String? = message.get()
        fun finish() {
            message.set(null)
            completion.getAndSet(null)?.invoke()
        }
    }

    internal fun attach(webView: WebView) {
        check(active.get())
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val allowedOrigin = identity.exactOrigin.removeSuffix("/")
        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_OBJECT,
            setOf(allowedOrigin),
        ) { view, message, sourceOrigin, isMainFrame, replyProxy ->
            accept(view, message, sourceOrigin, isMainFrame, replyProxy)
        }
        WebViewCompat.addDocumentStartJavaScript(webView, shim(webView.context, identity), setOf(allowedOrigin))
        RuntimeBridgeLifecycle.register(webView, this)
        attachedView = WeakReference(webView)
    }

    private fun requireForegroundSession() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val view = attachedView.get()
        if (!active.get() || view == null || !RuntimeBridgeLifecycle.isCurrent(view, this)) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The request belongs to an ended tool session")
        }
        if (!view.isAttachedToWindow || !view.isShown || !view.hasWindowFocus()) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "Open this tool in the foreground before using this capability")
        }

    }

    private fun accept(
        webView: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (!active.get()) return
        val encoded = message.data ?: return
        val epoch = navigationEpoch.get()
        if (encoded.length > maxPayloadBytes) {
            reply(webView, replyProxy, invalidRequest(runtimeRejectedRequestId(encoded), RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Bridge payload is too large"), epoch)
            return
        }
        val exactSourceOrigin = sourceOrigin.toString()
        val jobOwner = if (runtimeControlMethod(encoded)) controls else jobs
        val countsAsWrite = AtomicBoolean(jobOwner === jobs)
        if (countsAsWrite.get() && !presentation.admitOrdinaryRequest()) {
            reply(webView, replyProxy, invalidRequest(runtimeRejectedRequestId(encoded), RuntimeRpcErrorCode.SESSION_ENDED, "The final save has completed"), epoch)
            return
        }
        fun releaseWrite() { if (countsAsWrite.compareAndSet(true, false)) presentation.releaseOrdinaryRequest() }
        val admitted = jobOwner.launch(retainedBytes = (encoded.length.toLong() * Char.SIZE_BYTES).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
            val utf8 = encoded.toByteArray(Charsets.UTF_8)
            if (utf8.size > maxPayloadBytes) {
                replyAndAwaitDelivery(
                    webView,
                    replyProxy,
                    invalidRequest(runtimeRejectedRequestId(encoded), RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Bridge payload is too large"),
                    epoch,
                )
                return@launch
            }
            val request = try {
                RuntimeRpcJson.decodeRequest(utf8)
            } catch (_: Exception) {
                replyAndAwaitDelivery(
                    webView,
                    replyProxy,
                    invalidRequest(runtimeRejectedRequestId(encoded), RuntimeRpcErrorCode.INVALID_REQUEST, "Malformed ToolBox request"),
                    epoch,
                )
                return@launch
            }
            val survivesNavigation = request.method in RuntimePresentationCoordinator.WRITE_METHODS ||
                request.method == "runtime.flushComplete" || request.method == "network.closeMedia"
            val currentJob = currentCoroutineContext().job
            if (request.method !in RuntimePresentationCoordinator.WRITE_METHODS) releaseWrite()
            if (!survivesNavigation) {
                synchronized(navigationEpoch) {
                    if (!active.get() || navigationEpoch.get() != epoch) return@launch
                    nonWriteJobs[currentJob] = epoch
                }
            }
            val requestKey = epoch to request.id
            if (!inFlightIds.add(requestKey)) {
                nonWriteJobs.remove(currentJob)
                replyAndAwaitDelivery(
                    webView,
                    replyProxy,
                    invalidRequest(request.id, RuntimeRpcErrorCode.BUSY, "Request id is already active"),
                    epoch,
                )
                return@launch
            }
            try {
                val response = dispatcher.dispatch(
                    request,
                    RuntimeInboundContext(exactSourceOrigin, isMainFrame),
                    beforeInvoke = { method ->
                        if (method !in RuntimePresentationCoordinator.WRITE_METHODS &&
                            method != "runtime.flushComplete" && method != "network.closeMedia" &&
                            navigationEpoch.get() != epoch
                        ) throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "The tool document navigated")
                    },
                )
                replyAndAwaitDelivery(webView, replyProxy, response, epoch)
                if (request.method == "ready" && response is RuntimeRpcResponse.Success) {
                    val current = synchronized(navigationEpoch) {
                        if (active.get() && navigationEpoch.get() == epoch) {
                            eventProxy.set(replyProxy)
                            eventReady.set(true)
                            true
                        } else false
                    }
                    if (current) queuePresentation(webView)
                }
            } catch (_: CancellationException) {
            } finally {
                inFlightIds.remove(requestKey)
                nonWriteJobs.remove(currentJob)
            }
        }
        if (admitted == null) {
            releaseWrite()
            reply(webView, replyProxy, invalidRequest(runtimeRejectedRequestId(encoded), RuntimeRpcErrorCode.BUSY, "Insufficient memory for pending ToolBox requests"), epoch)
        } else admitted.invokeOnCompletion { releaseWrite() }
    }

    internal fun resetForNavigation() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!active.get()) return
        val oldJobs = synchronized(navigationEpoch) {
            val nextEpoch = navigationEpoch.incrementAndGet()
            eventReady.set(false)
            eventProxy.set(null)
            pendingState.set(null)
            events.resetForNavigation()
            nonWriteJobs.filterValues { it < nextEpoch }.keys.toList()
        }
        oldJobs.forEach { it.cancel(CancellationException("ToolBox document navigated")) }
        val currentEpoch = navigationEpoch.get()
        pendingDeliveries.filter { it.epoch < currentEpoch }.forEach(ReplyDelivery::finish)
        runCatching { network?.cancelStreams() }
    }

    internal fun close(webView: WebView) {
        if (!active.compareAndSet(true, false)) return
        attachedView.clear()
        pendingDeliveries.toList().forEach(ReplyDelivery::finish)
        jobs.close()
        controls.close()
        presentation.release()
        events.close()
        pendingState.set(null)
        runCatching { network?.close() }
        runCatching { sessionCleanup?.close() }
        inFlightIds.clear()
        nonWriteJobs.clear()
        eventReady.set(false)
        eventProxy.set(null)

        runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_OBJECT) }
    }

    internal fun isClosing(): Boolean = presentation.state().closing

    internal suspend fun awaitOpenForEvents(): Boolean {
        presentation.awaitOpenForEvents()
        return active.get()
    }

    internal fun setForeground(webView: WebView, foreground: Boolean) {
        if (active.get() && presentation.setForeground(foreground)) queuePresentation(webView)
    }

    internal suspend fun flushBeforeClose(webView: WebView, timeoutMillis: Long?): Boolean {
        if (!active.get()) return false
        val ticket = presentation.beginClose(timeoutMillis)
        network?.cancelStreams()
        queuePresentation(webView)
        suspend fun drain(): Boolean {
            if (!ticket.result.await()) return false
            presentation.awaitWrites()
            return active.get() && presentation.isCurrent(ticket)
        }
        val remaining = ticket.deadline?.let { (it - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) }
        val limit = listOfNotNull(remaining, timeoutMillis).minOrNull() ?: return drain()
        return withTimeoutOrNull(limit) { drain() } == true
    }

    internal fun cancelClose(webView: WebView) {
        presentation.cancelClose()
        if (active.get()) queuePresentation(webView)
    }

    private fun queuePresentation(webView: WebView) {
        synchronized(navigationEpoch) {
            pendingState.set(RuntimeRpcJson.encodeValue(RpcValue.ObjectValue(mapOf(
                "type" to RpcValue.StringValue("runtimeState"),
                "data" to presentation.stateValue(),
            ))))
        }
        scheduleDrain(webView)
    }

    internal fun emitEvent(webView: WebView, name: String, payload: RpcValue): Boolean {
        if (!active.get() || !EVENT_NAME.matches(name)) return false
        val admitted = synchronized(navigationEpoch) {
            if (!active.get()) return false
            events.offer { sequence ->
                RuntimeRpcJson.encodeValue(RpcValue.ObjectValue(mapOf(
                    "type" to RpcValue.StringValue("event"),
                    "event" to RpcValue.StringValue(name),
                    "generation" to RpcValue.StringValue(identity.generation),
                    "sequence" to RpcValue.Number(sequence.toDouble()),
                    "timestamp" to RpcValue.Number(System.currentTimeMillis().toDouble()),
                    "data" to payload,
                )))
            }
        }
        if (admitted) scheduleDrain(webView)
        return admitted
    }

    private fun scheduleDrain(webView: WebView) {
        if (!active.get() || !eventReady.get() || !drainScheduled.compareAndSet(false, true)) return
        val epoch = navigationEpoch.get()
        if (!mainHandler.post {
            try {
                val proxy = eventProxy.get()
                if (!active.get() || navigationEpoch.get() != epoch || attachedView.get() !== webView || !eventReady.get() || proxy == null) return@post
                pendingState.getAndSet(null)?.let { proxy.postMessage(it) }
                val started = System.nanoTime()
                var count = 0
                while (count < RuntimeEventBuffer.BATCH_SIZE && System.nanoTime() - started < RuntimeEventBuffer.BATCH_NANOS) {
                    if (navigationEpoch.get() != epoch) break
                    val event = events.poll() ?: break
                    proxy.postMessage(event.encoded)
                    count++
                }
            } catch (_: RuntimeException) {
                events.close()
            } finally {
                drainScheduled.set(false)
                if (events.hasPending() || pendingState.get() != null) scheduleDrain(webView)
            }
        }) drainScheduled.set(false)
    }

    private suspend fun replyAndAwaitDelivery(webView: WebView, proxy: JavaScriptReplyProxy, response: RuntimeRpcResponse, epoch: Long) {
        if (!active.get() || navigationEpoch.get() != epoch) {
            (response as? RuntimeRpcResponse.Success)?.release?.invoke()
            return
        }
        val encoded = try {
            encodeReply(response)
        } catch (error: Throwable) {
            (response as? RuntimeRpcResponse.Success)?.release?.invoke()
            throw error
        }
        val delivered = CompletableDeferred<Unit>()
        lateinit var lease: ReplyDelivery
        lease = ReplyDelivery(epoch, encoded) {
            pendingDeliveries.remove(lease)
            (response as? RuntimeRpcResponse.Success)?.release?.invoke()
            delivered.complete(Unit)
        }
        pendingDeliveries.add(lease)
        try {
            postReply(webView, proxy, lease::encoded, epoch, lease::finish)
        } catch (error: Throwable) {
            lease.finish()
            throw error
        }
        // Navigation releases old response reservations even when their main-looper delivery is still queued.
        delivered.await()
    }

    private fun reply(
        webView: WebView,
        proxy: JavaScriptReplyProxy,
        response: RuntimeRpcResponse,
        epoch: Long,
    ) {
        if (!active.get() || navigationEpoch.get() != epoch) return
        val encoded = encodeReply(response)
        postReply(webView, proxy, { encoded }, epoch) {}
    }

    private fun encodeReply(response: RuntimeRpcResponse): String {
        val candidate = RuntimeRpcJson.prepareResponse(response)
        return enforceRuntimeResponseLimit(candidate, maxPayloadBytes) {
            RuntimeRpcJson.encodeResponse(
                RuntimeRpcResponse.Failure(
                    response.id,
                    RuntimeRpcError(
                        RuntimeRpcErrorCode.QUOTA_EXCEEDED,
                        "当前可用内存不足以编码响应；请分块读取，或释放内存后重试。",
                    ),
                ),
            )
        }
    }

    private fun postReply(
        webView: WebView,
        proxy: JavaScriptReplyProxy,
        encoded: () -> String?,
        epoch: Long,
        onDelivered: () -> Unit,
    ) {
        if (!active.get() || navigationEpoch.get() != epoch) {
            onDelivered()
            return
        }
        val deliver = Runnable {
            try {
                if (active.get() && navigationEpoch.get() == epoch && attachedView.get() === webView) {
                    encoded()?.let { runCatching { proxy.postMessage(it) } }
                }
            } finally {
                onDelivered()
            }
        }
        // Admission failures already arrive on Main: don't create an unbounded reply queue.
        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run()
        else if (!mainHandler.post(deliver)) onDelivered()
    }

    private fun invalidRequest(id: String, code: RuntimeRpcErrorCode, message: String) =
        RuntimeRpcResponse.Failure(id, RuntimeRpcError(code, message))

    private fun shim(context: Context, identity: RuntimeSessionIdentity): String {
        val values = mapOf(
            "__TOOLBOX_BRIDGE_OBJECT__" to BRIDGE_OBJECT,
            "__TOOLBOX_NONCE__" to JSONObject.quote(identity.nonce),
            "__TOOLBOX_TOOL_ID__" to JSONObject.quote(identity.toolId),
            "__TOOLBOX_GENERATION__" to JSONObject.quote(identity.generation),
            "__TOOLBOX_VERSION_CODE__" to identity.versionCode.toString(),
        )
        // One pass: substituted values are never rescanned for placeholders.
        return SHIM_PLACEHOLDER.replace(RuntimeSdkShim.template(context)) { values.getValue(it.value) }
    }

    private companion object {
        val controlBudget = RuntimeRequestBudget(64 * 1024)
        const val BRIDGE_OBJECT = "__toolboxNative"
        val EVENT_NAME = Regex("^[a-z][a-zA-Z0-9.]+$")
        val SHIM_PLACEHOLDER = Regex("__TOOLBOX_[A-Z_]+__")
    }
}

/** The document-start SDK lives in assets/toolbox-runtime/runtime-sdk.js; read once per process. */
internal object RuntimeSdkShim {
    private const val ASSET = "toolbox-runtime/runtime-sdk.js"
    @Volatile private var cached: String? = null

    fun template(context: Context): String = cached ?: context.assets.open(ASSET)
        .use { it.readBytes().toString(Charsets.UTF_8) }
        // The asset file ends with a newline; the injected script never did.
        .removeSuffix("\n")
        .also { cached = it }
}

internal inline fun enforceRuntimeResponseLimit(
    candidate: String,
    maxBytes: Int,
    quotaFailure: () -> String,
): String = enforceRuntimeResponseLimit(EncodedRuntimeResponse(candidate), maxBytes, quotaFailure)

internal inline fun enforceRuntimeResponseLimit(
    candidate: EncodedRuntimeResponse,
    maxBytes: Int,
    quotaFailure: () -> String,
): String {
    require(maxBytes > 0)
    return if (candidate.utf8Bytes <= maxBytes) candidate.text else quotaFailure()
}

internal class EncodedRuntimeResponse(val text: String) {
    val utf8Bytes: Int = text.toByteArray(Charsets.UTF_8).size
    // Preserve the stream gate's existing conservative </ escaping budget without another string.
    val streamUpperBoundBytes: Long
        get() = utf8Bytes.toLong() + text.indices.count { index ->
            text[index] == '/' && index > 0 && text[index - 1] == '<'
        }
}

internal object RuntimeBridgeLifecycle {
    private val sessions = Collections.synchronizedMap(IdentityHashMap<WebView, RuntimeBridgeSession>())

    fun register(webView: WebView, session: RuntimeBridgeSession) {
        check(sessions.put(webView, session) == null)
    }

    fun release(webView: WebView) {
        sessions.remove(webView)?.close(webView)
    }

    fun resetForNavigation(webView: WebView) {
        sessions[webView]?.resetForNavigation()
    }

    fun isCurrent(webView: WebView, session: RuntimeBridgeSession): Boolean = sessions[webView] === session

    fun emitEvent(webView: WebView, name: String, payload: RpcValue): Boolean =
        sessions[webView]?.emitEvent(webView, name, payload) == true

    fun setForeground(webView: WebView, foreground: Boolean) {
        sessions[webView]?.setForeground(webView, foreground)
    }

    suspend fun flushBeforeClose(webView: WebView, timeoutMillis: Long?): Boolean = sessions[webView]?.flushBeforeClose(webView, timeoutMillis) == true

    fun cancelClose(webView: WebView) { sessions[webView]?.cancelClose(webView) }
    fun isClosing(webView: WebView): Boolean = sessions[webView]?.isClosing() == true
    suspend fun awaitOpenForEvents(webView: WebView): Boolean = sessions[webView]?.awaitOpenForEvents() == true
}

object RuntimeRpcJson {
    fun encodeValue(value: RpcValue): String = buildString { appendJson(value) }

    fun parseValue(encoded: String): RpcValue = parseValue(encoded.toByteArray(Charsets.UTF_8))

    fun parseValue(encoded: ByteArray): RpcValue {
        val pending = ArrayDeque<Pair<Any, RpcValue>>()
        fun allocate(source: Any?): RpcValue = when (source) {
            null -> RpcValue.Null
            is Boolean -> RpcValue.Bool(source)
            is Number -> RpcValue.Number(source.toDouble().also { require(it.isFinite()) })
            is String -> RpcValue.StringValue(source)
            is Map<*, *> -> RpcValue.ObjectValue(linkedMapOf()).also { pending.addLast(source to it) }
            is List<*> -> RpcValue.ArrayValue(mutableListOf()).also { pending.addLast(source to it) }
            else -> throw IllegalArgumentException("json")
        }
        val result = allocate(io.toolbox.tool.packagekit.backup.BackupJson.parse(encoded))
        while (pending.isNotEmpty()) {
            val (source, target) = pending.removeLast()
            when (target) {
                is RpcValue.ObjectValue -> {
                    @Suppress("UNCHECKED_CAST") val map = target.value as MutableMap<String, RpcValue>
                    (source as Map<*, *>).forEach { (key, child) -> map[key as String] = allocate(child) }
                }
                is RpcValue.ArrayValue -> {
                    @Suppress("UNCHECKED_CAST") val list = target.value as MutableList<RpcValue>
                    (source as List<*>).forEach { list.add(allocate(it)) }
                }
                else -> error("JSON_CONTAINER")
            }
        }
        return result
    }

    fun decodeRequest(encoded: String): RuntimeRpcRequest = decodeRequest(encoded.toByteArray(Charsets.UTF_8))

    fun decodeRequest(encoded: ByteArray): RuntimeRpcRequest {
        val root = (parseValue(encoded) as? RpcValue.ObjectValue)?.value ?: throw IllegalArgumentException("request")
        val allowedKeys = setOf("id", "method", "nonce", "toolId", "versionCode", "generation", "params")
        require(root.keys.all { it in allowedKeys })
        fun string(name: String): String = (root[name] as? RpcValue.StringValue)?.value
            ?.also { require(it.isNotEmpty()) } ?: throw IllegalArgumentException(name)
        val version = (root["versionCode"] as? RpcValue.Number)?.value ?: throw IllegalArgumentException("versionCode")
        require(version in 1.0..Int.MAX_VALUE.toDouble() && version % 1 == 0.0)
        return RuntimeRpcRequest(
            id = string("id").also { require(isSafeRuntimeRequestId(it)) },
            method = string("method"),
            nonce = string("nonce"),
            toolId = string("toolId"),
            versionCode = version.toInt(),
            generation = string("generation"),
            params = (root["params"] ?: RpcValue.ObjectValue(emptyMap())) as? RpcValue.ObjectValue
                ?: throw IllegalArgumentException("params"),
            encodedBytes = encoded.size,
        )
    }

    fun encodeResponse(response: RuntimeRpcResponse): String = encodeValue(RpcValue.ObjectValue(when (response) {
        is RuntimeRpcResponse.Success -> mapOf(
            "id" to RpcValue.StringValue(response.id), "ok" to RpcValue.Bool(true), "result" to response.result,
        )
        is RuntimeRpcResponse.Failure -> mapOf(
            "id" to RpcValue.StringValue(response.id), "ok" to RpcValue.Bool(false), "error" to response.error.toRpcValue(),
        )
    }))

    internal fun prepareResponse(response: RuntimeRpcResponse): EncodedRuntimeResponse =
        (response as? RuntimeRpcResponse.Success)?.preparedEncoding ?: EncodedRuntimeResponse(encodeResponse(response))

    private fun StringBuilder.appendJson(value: RpcValue) {
        val pending = ArrayDeque<Any>()
        pending.addLast(value)
        while (pending.isNotEmpty()) {
            when (val item = pending.removeLast()) {
                is String -> append(item)
                RpcValue.Null -> append("null")
                is RpcValue.Bool -> append(item.value)
                is RpcValue.Number -> { require(item.value.isFinite()); append(item.value) }
                is RpcValue.StringValue -> appendJsonString(item.value)
                is RpcValue.ArrayValue -> {
                    append('[')
                    pending.addLast("]")
                    item.value.indices.reversed().forEach { index ->
                        pending.addLast(item.value[index])
                        if (index > 0) pending.addLast(",")
                    }
                }
                is RpcValue.ObjectValue -> {
                    append('{')
                    pending.addLast("}")
                    item.value.entries.toList().asReversed().forEachIndexed { index, (name, child) ->
                        pending.addLast(child)
                        pending.addLast(":")
                        pending.addLast(RpcValue.StringValue(name))
                        if (index < item.value.size - 1) pending.addLast(",")
                    }
                }
            }
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20 || char.code in 0xD800..0xDFFF || char == '\u2028' || char == '\u2029') {
                    append("\\u")
                    repeat(4) { shift -> append(HEX[(char.code ushr (12 - shift * 4)) and 0xF]) }
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }

    private const val HEX = "0123456789abcdef"
}

internal fun createRuntimeBridgeSession(
    runtime: PreparedToolRuntime,
    configuration: RuntimeBridgeConfiguration,
): RuntimeBridgeSession {
    val generation = configuration.generation.ifBlank { java.util.UUID.randomUUID().toString() }
    val nonceBytes = ByteArray(32).also(SecureRandom()::nextBytes)
    val identity = RuntimeSessionIdentity(
        toolId = runtime.toolId,
        versionCode = runtime.versionCode,
        generation = generation,
        hostVersion = configuration.hostVersion,
        nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes),
        exactOrigin = runtime.origin,
        declaredCapabilities = runtime.declaredCapabilities,
    )
    return RuntimeBridgeSession(
        identity = identity,
        authorization = configuration.authorization,
        handlers = configuration.handlers,
        serviceHandlers = configuration.serviceHandlers,
        systemHandlers = configuration.systemHandlers,
        browserLaunchGuard = configuration.browserLaunchGuard,
    )
}
