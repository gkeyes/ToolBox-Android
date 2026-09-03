package io.toolbox.tool.runtime

import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

fun interface RuntimeBridgeProvider {
    fun create(runtime: PreparedToolRuntime): RuntimeBridgeConfiguration
}

data class RuntimeBridgeConfiguration(
    val authorization: RuntimeAuthorizationPolicy,
    val handlers: RuntimeM1Handlers,
    val hostVersion: String,
    val generation: String = "",
    val maxPayloadBytes: Int = DEFAULT_MAX_BRIDGE_PAYLOAD_BYTES,
    val m2Handlers: RuntimeM2Handlers = RuntimeM2Handlers(),
    val m3Handlers: RuntimeM3Handlers = RuntimeM3Handlers(),
) {
    init {
        require(hostVersion.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")))
        require(maxPayloadBytes in MIN_BRIDGE_PAYLOAD_BYTES..MAX_BRIDGE_PAYLOAD_BYTES)
    }

    companion object {
        const val DEFAULT_MAX_BRIDGE_PAYLOAD_BYTES = 256 * 1024
        const val MIN_BRIDGE_PAYLOAD_BYTES = 4 * 1024
        const val MAX_BRIDGE_PAYLOAD_BYTES = 8 * 1024 * 1024
    }
}

class RuntimeBridgeSession internal constructor(
    private val identity: RuntimeSessionIdentity,
    authorization: RuntimeAuthorizationPolicy,
    handlers: RuntimeM1Handlers,
    private val maxPayloadBytes: Int,
    m2Handlers: RuntimeM2Handlers = RuntimeM2Handlers(),
    m3Handlers: RuntimeM3Handlers = RuntimeM3Handlers(),
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val active = AtomicBoolean(true)
    private val eventReady = AtomicBoolean(false)
    private val gestureAtMillis = AtomicLong(NO_GESTURE)
    private val inFlightIds = ConcurrentHashMap.newKeySet<String>()
    private val eventProxy = AtomicReference<JavaScriptReplyProxy?>(null)
    private val pendingEvents = ArrayDeque<String>()
    private val jobs = RuntimeSessionJobs()
    private val dispatcher = RuntimeRpcDispatcher(
        identity = identity,
        authorization = authorization,
        handlers = handlers,
        m2Handlers = m2Handlers,
        m3Handlers = m3Handlers,
        maxResponseBytes = maxPayloadBytes,
    )
    private val sessionCleanup = m3Handlers.sessionCleanup

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
        WebViewCompat.addDocumentStartJavaScript(webView, shim(identity), setOf(allowedOrigin))
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                gestureAtMillis.set(clockMillis())
            }
            false
        }
        RuntimeBridgeLifecycle.register(webView, this)
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
        if (encoded.length > maxPayloadBytes) {
            reply(webView, replyProxy, invalidRequest("", RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Bridge payload is too large"))
            return
        }
        val exactSourceOrigin = sourceOrigin.toString()
        val now = clockMillis()
        val touchedAt = gestureAtMillis.get()
        val touchAge = if (touchedAt == NO_GESTURE) null else now - touchedAt
        jobs.launch {
            if (encoded.toByteArray(Charsets.UTF_8).size > maxPayloadBytes) {
                reply(
                    webView,
                    replyProxy,
                    invalidRequest("", RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Bridge payload is too large"),
                )
                return@launch
            }
            val request = try {
                RuntimeRpcJson.decodeRequest(encoded)
            } catch (_: Exception) {
                reply(
                    webView,
                    replyProxy,
                    invalidRequest("", RuntimeRpcErrorCode.INVALID_REQUEST, "Malformed ToolBox request"),
                )
                return@launch
            }
            if (!inFlightIds.add(request.id)) {
                reply(
                    webView,
                    replyProxy,
                    invalidRequest(request.id, RuntimeRpcErrorCode.BUSY, "Request id is already active"),
                )
                return@launch
            }
            try {
                val response = dispatcher.dispatch(
                    request,
                    RuntimeInboundContext(exactSourceOrigin, isMainFrame, touchAge),
                )
                reply(webView, replyProxy, response)
                if (request.method == "ready" && response is RuntimeRpcResponse.Success) {
                    eventProxy.set(replyProxy)
                    eventReady.set(true)
                    flushPendingEvents(webView, replyProxy)
                }
            } catch (_: CancellationException) {
            } finally {
                inFlightIds.remove(request.id)
            }
        }
    }

    internal fun close(webView: WebView) {
        if (!active.compareAndSet(true, false)) return
        jobs.close()
        runCatching { sessionCleanup?.close() }
        inFlightIds.clear()
        eventReady.set(false)
        eventProxy.set(null)
        synchronized(pendingEvents) { pendingEvents.clear() }
        gestureAtMillis.set(NO_GESTURE)
        runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_OBJECT) }
        webView.setOnTouchListener(null)
    }

    internal fun emitEvent(webView: WebView, name: String, payload: RpcValue): Boolean {
        if (!active.get() || !EVENT_NAME.matches(name)) return false
        val encoded = JSONObject()
            .put("type", "event")
            .put("event", name)
            .put("generation", identity.generation)
            .put("timestamp", System.currentTimeMillis())
            .put("data", JSONTokener(RuntimeRpcJson.encodeValue(payload)).nextValue())
            .toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > maxPayloadBytes) return false
        val proxy = eventProxy.get()
        if (!eventReady.get() || proxy == null) {
            synchronized(pendingEvents) {
                if (pendingEvents.size == MAX_PENDING_EVENTS) pendingEvents.removeFirst()
                pendingEvents.addLast(encoded)
            }
            return true
        }
        webView.post {
            if (active.get() && eventReady.get() && eventProxy.get() === proxy) {
                runCatching { proxy.postMessage(encoded) }
            }
        }
        return true
    }

    private fun flushPendingEvents(webView: WebView, proxy: JavaScriptReplyProxy) {
        val queued = synchronized(pendingEvents) {
            buildList {
                while (pendingEvents.isNotEmpty()) add(pendingEvents.removeFirst())
            }
        }
        if (queued.isEmpty()) return
        webView.post {
            if (!active.get() || !eventReady.get() || eventProxy.get() !== proxy) return@post
            queued.forEach { encoded -> runCatching { proxy.postMessage(encoded) } }
        }
    }

    private fun reply(webView: WebView, proxy: JavaScriptReplyProxy, response: RuntimeRpcResponse) {
        if (!active.get()) return
        val candidate = RuntimeRpcJson.encodeResponse(response)
        val encoded = enforceRuntimeResponseLimit(candidate, maxPayloadBytes) {
            RuntimeRpcJson.encodeResponse(
                RuntimeRpcResponse.Failure(
                    response.id,
                    RuntimeRpcError(
                        RuntimeRpcErrorCode.QUOTA_EXCEEDED,
                        "响应编码后超过 $maxPayloadBytes 字节消息上限；请减少单页数据或提高 manifest 的 limits.maxBridgePayloadBytes。",
                    ),
                ),
            )
        }
        webView.post {
            if (active.get()) runCatching { proxy.postMessage(encoded) }
        }
    }

    private fun invalidRequest(id: String, code: RuntimeRpcErrorCode, message: String) =
        RuntimeRpcResponse.Failure(id, RuntimeRpcError(code, message))

    private fun shim(identity: RuntimeSessionIdentity): String {
        val nonce = JSONObject.quote(identity.nonce)
        val toolId = JSONObject.quote(identity.toolId)
        val generation = JSONObject.quote(identity.generation)
        return """
            (() => {
              'use strict';
              const nativeBridge = globalThis.$BRIDGE_OBJECT;
              const pending = new Map();
              const listeners = new Map();
              const earlyEvents = new Map();
              let earlyEventCount = 0;
              let sequence = 0;
              nativeBridge.onmessage = event => {
                let response;
                try { response = JSON.parse(event.data); } catch (_) { return; }
                if (response.type === 'event' && typeof response.event === 'string') {
                  const callbacks = listeners.get(response.event);
                  if (callbacks && callbacks.size > 0) {
                    callbacks.forEach(callback => {
                      try { callback(response.data); } catch (_) {}
                    });
                  } else {
                    if (earlyEventCount >= 64) {
                      for (const [name, values] of earlyEvents) {
                        if (values.length > 0) {
                          values.shift(); earlyEventCount -= 1;
                          if (values.length === 0) earlyEvents.delete(name);
                          break;
                        }
                      }
                    }
                    let queue = earlyEvents.get(response.event);
                    if (!queue) earlyEvents.set(response.event, queue = []);
                    queue.push(response.data); earlyEventCount += 1;
                  }
                  try { globalThis.dispatchEvent(new CustomEvent(`toolbox:${'$'}{response.event}`, { detail: response.data })); } catch (_) {}
                  return;
                }
                const waiter = pending.get(response.id);
                if (!waiter) return;
                pending.delete(response.id);
                response.ok ? waiter.resolve(response.result) : waiter.reject(Object.assign(new Error(response.error.message), response.error));
              };
              const call = (method, params = {}) => new Promise((resolve, reject) => {
                const id = `${'$'}{Date.now().toString(36)}-${'$'}{(++sequence).toString(36)}`;
                pending.set(id, { resolve, reject });
                nativeBridge.postMessage(JSON.stringify({
                  id, method, params, nonce: $nonce, toolId: $toolId,
                  versionCode: ${identity.versionCode}, generation: $generation
                }));
              });
              const bytes = value => value instanceof Uint8Array ? Array.from(value) : value;
              const subscribe = (name, listener) => {
                if (typeof listener !== 'function') throw new TypeError('listener must be a function');
                let callbacks = listeners.get(name);
                if (!callbacks) listeners.set(name, callbacks = new Set());
                callbacks.add(listener);
                const queued = earlyEvents.get(name);
                if (queued) {
                  earlyEvents.delete(name);
                  earlyEventCount -= queued.length;
                  queueMicrotask(() => queued.forEach(payload => {
                    try { listener(payload); } catch (_) {}
                  }));
                }
                return () => callbacks.delete(listener);
              };
              const api = {
                ready: () => call('ready'),
                ui: { toast: message => call('ui.toast', { message }) },
                crypto: { sha256: value => call('crypto.sha256', { value: bytes(value) }) },
                storage: {
                  get: key => call('storage.get', { key }),
                  set: (key, value) => call('storage.set', { key, value }),
                  remove: key => call('storage.remove', { key }),
                  keys: () => call('storage.keys'),
                  clear: () => call('storage.clear'),
                  secure: {
                    get: key => call('storage.secure.get', { key }),
                    set: (key, value) => call('storage.secure.set', { key, value }),
                    remove: key => call('storage.secure.remove', { key })
                  }
                },
                device: { getBasicInfo: () => call('device.getBasicInfo') },
                haptics: { perform: effect => call('haptics.perform', { effect }) },
                clipboard: {
                  writeText: text => call('clipboard.writeText', { text }),
                  readText: () => call('clipboard.readText')
                },
                network: { request: request => {
                  if (request && request.body instanceof Uint8Array) {
                    return call('network.request', { ...request, body: Array.from(request.body), bodyEncoding: 'bytes' });
                  }
                  return call('network.request', request);
                } },
                notifications: {
                  post: (id, title, body) => call('notifications.post', { id, title, body }),
                  update: (id, title, body) => call('notifications.update', { id, title, body }),
                  cancel: id => call('notifications.cancel', { id }),
                  live: {
                    start: request => call('notifications.live.start', request),
                    update: request => call('notifications.live.update', request),
                    end: sessionId => call('notifications.live.end', { sessionId })
                  }
                },
                background: {
                  enqueue: spec => call('background.enqueue', spec),
                  schedulePeriodic: spec => call('background.schedulePeriodic', spec),
                  start: options => call('background.start', options === undefined ? {} : options),
                  stop: sessionId => call('background.stop', { sessionId }),
                  status: sessionId => call('background.status', { sessionId }),
                  list: () => call('background.list'),
                  listSessions: () => call('background.listSessions'),
                  getResult: taskId => call('background.getResult', { taskId }),
                  cancel: taskId => call('background.cancel', { taskId }),
                  setTimer: (key, intervalMs) => call('background.setTimer', { key, intervalMs }),
                  cancelTimer: key => call('background.cancelTimer', { key }),
                  onRestore: listener => subscribe('background.restore', listener),
                  onTimer: listener => subscribe('background.timer', listener)
                },
                share: { text: text => call('share.text', { text }) },
                files: {
                  open: mimeTypes => call('files.open', mimeTypes === undefined ? {} : { mimeTypes }),
                  save: (suggestedName, mimeType, content) => call('files.save', { suggestedName, mimeType, content: bytes(content) }),
                  read: token => call('files.read', { token }).then(result => {
                    const binary = atob(result.base64);
                    return Uint8Array.from(binary, character => character.charCodeAt(0));
                  })
                },
                shortcuts: { pin: name => call('shortcuts.pin', name === undefined ? {} : { name }) },
                camera: { capture: () => call('camera.capture') },
                location: {
                  getCurrent: (accuracy, timeoutMs) => call('location.getCurrent', {
                    ...(accuracy === undefined ? {} : { accuracy }),
                    ...(timeoutMs === undefined ? {} : { timeoutMs })
                  }),
                  watch: options => call('location.watch', options === undefined ? {} : options),
                  clearWatch: watchId => call('location.clearWatch', { watchId }),
                  onChanged: listener => subscribe('location.onChanged', listener)
                },
                alarms: {
                  schedule: options => call('alarms.schedule', options),
                  list: () => call('alarms.list'),
                  cancel: id => call('alarms.cancel', { id }),
                  onAlarm: listener => subscribe('alarm', listener)
                }
              };
              Object.defineProperty(globalThis, 'ToolBox', { value: Object.freeze(api), configurable: false, writable: false });
            })();
        """.trimIndent()
    }

    private companion object {
        const val BRIDGE_OBJECT = "__toolboxNative"
        const val NO_GESTURE = Long.MIN_VALUE
        const val MAX_PENDING_EVENTS = 64
        val EVENT_NAME = Regex("^[a-z][a-zA-Z0-9.]{1,63}$")
    }
}

internal inline fun enforceRuntimeResponseLimit(
    candidate: String,
    maxBytes: Int,
    quotaFailure: () -> String,
): String {
    require(maxBytes > 0)
    return if (candidate.toByteArray(Charsets.UTF_8).size <= maxBytes) candidate else quotaFailure()
}

internal object RuntimeBridgeLifecycle {
    private val sessions = Collections.synchronizedMap(IdentityHashMap<WebView, RuntimeBridgeSession>())

    fun register(webView: WebView, session: RuntimeBridgeSession) {
        check(sessions.put(webView, session) == null)
    }

    fun release(webView: WebView) {
        sessions.remove(webView)?.close(webView)
    }

    fun emitEvent(webView: WebView, name: String, payload: RpcValue): Boolean =
        sessions[webView]?.emitEvent(webView, name, payload) == true
}

internal object RuntimeRpcJson {
    fun encodeValue(value: RpcValue): String = buildString { appendJson(value) }

    fun decodeRequest(encoded: String): RuntimeRpcRequest {
        val root = JSONTokener(encoded).nextValue() as? JSONObject ?: throw IllegalArgumentException("request")
        val allowedKeys = setOf("id", "method", "nonce", "toolId", "versionCode", "generation", "params")
        require(root.keys().asSequence().all { it in allowedKeys })
        val params = if (root.has("params")) root.get("params") else JSONObject()
        return RuntimeRpcRequest(
            id = root.requiredString("id", 1, 128).also { require(isSafeRuntimeRequestId(it)) },
            method = root.requiredString("method", 1, 96),
            nonce = root.requiredString("nonce", 16, 128),
            toolId = root.requiredString("toolId", 5, 120),
            versionCode = root.getInt("versionCode").also { require(it > 0) },
            generation = root.requiredString("generation", 1, 256),
            params = fromJson(params) as? RpcValue.ObjectValue ?: throw IllegalArgumentException("params"),
            encodedBytes = encoded.toByteArray(Charsets.UTF_8).size,
        )
    }

    fun encodeResponse(response: RuntimeRpcResponse): String = when (response) {
        is RuntimeRpcResponse.Success -> JSONObject()
            .put("id", response.id)
            .put("ok", true)
            .put("result", toJson(response.result))
            .toString()
        is RuntimeRpcResponse.Failure -> JSONObject()
            .put("id", response.id)
            .put("ok", false)
            .put(
                "error",
                JSONObject()
                    .put("code", response.error.code.name)
                    .put("message", response.error.message),
            )
            .toString()
    }

    private fun JSONObject.requiredString(name: String, min: Int, max: Int): String =
        getString(name).also { require(it.length in min..max) }

    private fun fromJson(value: Any?): RpcValue = when (value) {
        null, JSONObject.NULL -> RpcValue.Null
        is Boolean -> RpcValue.Bool(value)
        is Number -> RpcValue.Number(value.toDouble())
        is String -> RpcValue.StringValue(value)
        is JSONArray -> RpcValue.ArrayValue((0 until value.length()).map { fromJson(value.get(it)) })
        is JSONObject -> RpcValue.ObjectValue(value.keys().asSequence().associateWith { fromJson(value.get(it)) })
        else -> throw IllegalArgumentException("json")
    }

    private fun StringBuilder.appendJson(value: RpcValue) {
        when (value) {
            RpcValue.Null -> append("null")
            is RpcValue.Bool -> append(value.value)
            is RpcValue.Number -> {
                require(value.value.isFinite())
                append(value.value)
            }
            is RpcValue.StringValue -> appendJsonString(value.value)
            is RpcValue.ArrayValue -> {
                append('[')
                value.value.forEachIndexed { index, child ->
                    if (index > 0) append(',')
                    appendJson(child)
                }
                append(']')
            }
            is RpcValue.ObjectValue -> {
                append('{')
                value.value.entries.forEachIndexed { index, (name, child) ->
                    if (index > 0) append(',')
                    appendJsonString(name)
                    append(':')
                    appendJson(child)
                }
                append('}')
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

    private fun toJson(value: RpcValue): Any = when (value) {
        RpcValue.Null -> JSONObject.NULL
        is RpcValue.Bool -> value.value
        is RpcValue.Number -> value.value
        is RpcValue.StringValue -> value.value
        is RpcValue.ArrayValue -> JSONArray().also { output -> value.value.forEach { output.put(toJson(it)) } }
        is RpcValue.ObjectValue -> JSONObject().also { output ->
            value.value.forEach { (name, child) -> output.put(name, toJson(child)) }
        }
    }

    private const val HEX = "0123456789abcdef"
}

internal fun createRuntimeBridgeSession(
    runtime: PreparedToolRuntime,
    configuration: RuntimeBridgeConfiguration,
): RuntimeBridgeSession {
    val generation = configuration.generation.ifBlank { "${runtime.toolId}:${runtime.versionCode}" }
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
        m2Handlers = configuration.m2Handlers,
        m3Handlers = configuration.m3Handlers,
        maxPayloadBytes = minOf(configuration.maxPayloadBytes, runtime.maxBridgePayloadBytes),
    )
}
