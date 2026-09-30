package io.toolbox.tool.runtime

import io.toolbox.core.data.ResourceCapacity
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

fun interface RuntimeBridgeProvider {
    fun create(runtime: PreparedToolRuntime): RuntimeBridgeConfiguration
}

data class RuntimeBridgeConfiguration(
    val authorization: RuntimeAuthorizationPolicy,
    val handlers: RuntimeM1Handlers,
    val hostVersion: String,
    val generation: String = "",
    val m2Handlers: RuntimeM2Handlers = RuntimeM2Handlers(),
    val m3Handlers: RuntimeM3Handlers = RuntimeM3Handlers(),
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
    handlers: RuntimeM1Handlers,
    m2Handlers: RuntimeM2Handlers = RuntimeM2Handlers(),
    m3Handlers: RuntimeM3Handlers = RuntimeM3Handlers(),
    private val browserLaunchGuard: () -> Unit = {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "No foreground tool is available")
    },
    ordinaryBudget: RuntimeRequestBudget = RuntimeRequestBudget(),
) {
    // A JSON message is decoded as UTF-16. This is current allocation capacity,
    // not the obsolete per-tool manifest quota.
    private val maxPayloadBytes: Int
        get() = (ResourceCapacity.availableHeapBytes() / Char.SIZE_BYTES).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    private val active = AtomicBoolean(true)
    private val eventReady = AtomicBoolean(false)
    private val inFlightIds = ConcurrentHashMap.newKeySet<String>()
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
    private val jobs = RuntimeSessionJobs(localBudget = ordinaryBudget)
    private var attachedView = WeakReference<WebView>(null)
    // A recovered/background runtime may never have a window. View.post queues
    // work until attachment; native replies and events must target the UI looper.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val dispatcher = RuntimeRpcDispatcher(
        identity = identity,
        authorization = authorization,
        handlers = handlers,
        m2Handlers = m2Handlers,
        m3Handlers = m3Handlers,
        lifecycle = lifecycle,
        browserLaunchGuard = { browserLaunchGuard(); requireForegroundSession() },
        foregroundInteractionGuard = { withContext(Dispatchers.Main.immediate) { requireForegroundSession() } },
    )
    private val sessionCleanup = m3Handlers.sessionCleanup
    private val network = m2Handlers.network

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
        if (encoded.length > maxPayloadBytes) {
            reply(webView, replyProxy, invalidRequest(runtimeRejectedRequestId(encoded), RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Bridge payload is too large"))
            return
        }
        val exactSourceOrigin = sourceOrigin.toString()
        val jobOwner = if (runtimeControlMethod(encoded)) controls else jobs
        val countsAsWrite = AtomicBoolean(jobOwner === jobs)
        if (countsAsWrite.get() && !presentation.admitOrdinaryRequest()) {
            reply(webView, replyProxy, invalidRequest(runtimeRejectedRequestId(encoded), RuntimeRpcErrorCode.SESSION_ENDED, "The final save has completed"))
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
                )
                return@launch
            }
            if (request.method !in RuntimePresentationCoordinator.WRITE_METHODS) releaseWrite()
            if (!inFlightIds.add(request.id)) {
                replyAndAwaitDelivery(
                    webView,
                    replyProxy,
                    invalidRequest(request.id, RuntimeRpcErrorCode.BUSY, "Request id is already active"),
                )
                return@launch
            }
            try {
                val response = dispatcher.dispatch(
                    request,
                    RuntimeInboundContext(exactSourceOrigin, isMainFrame),
                )
                replyAndAwaitDelivery(webView, replyProxy, response)
                if (request.method == "ready" && response is RuntimeRpcResponse.Success) {
                    eventProxy.set(replyProxy)
                    eventReady.set(true)
                    queuePresentation(webView)
                    scheduleDrain(webView)
                }
            } catch (_: CancellationException) {
            } finally {
                inFlightIds.remove(request.id)
            }
        }
        if (admitted == null) {
            releaseWrite()
            reply(webView, replyProxy, invalidRequest(runtimeRejectedRequestId(encoded), RuntimeRpcErrorCode.BUSY, "Insufficient memory for pending ToolBox requests"))
        } else admitted.invokeOnCompletion { releaseWrite() }
    }

    internal fun close(webView: WebView) {
        if (!active.compareAndSet(true, false)) return
        attachedView.clear()
        jobs.close()
        controls.close()
        presentation.release()
        events.close()
        pendingState.set(null)
        runCatching { network?.close() }
        runCatching { sessionCleanup?.close() }
        inFlightIds.clear()
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
        pendingState.set(RuntimeRpcJson.encodeValue(RpcValue.ObjectValue(mapOf(
            "type" to RpcValue.StringValue("runtimeState"),
            "data" to presentation.stateValue(),
        ))))
        scheduleDrain(webView)
    }

    internal fun emitEvent(webView: WebView, name: String, payload: RpcValue): Boolean {
        if (!active.get() || !EVENT_NAME.matches(name)) return false
        val admitted = events.offer { sequence ->
            RuntimeRpcJson.encodeValue(RpcValue.ObjectValue(mapOf(
                "type" to RpcValue.StringValue("event"),
                "event" to RpcValue.StringValue(name),
                "generation" to RpcValue.StringValue(identity.generation),
                "sequence" to RpcValue.Number(sequence.toDouble()),
                "timestamp" to RpcValue.Number(System.currentTimeMillis().toDouble()),
                "data" to payload,
            )))
        }
        if (admitted) scheduleDrain(webView)
        return admitted
    }

    private fun scheduleDrain(webView: WebView) {
        if (!active.get() || !eventReady.get() || !drainScheduled.compareAndSet(false, true)) return
        if (!mainHandler.post {
            try {
                val proxy = eventProxy.get()
                if (!active.get() || attachedView.get() !== webView || !eventReady.get() || proxy == null) return@post
                pendingState.getAndSet(null)?.let { proxy.postMessage(it) }
                val started = System.nanoTime()
                var count = 0
                while (count < RuntimeEventBuffer.BATCH_SIZE && System.nanoTime() - started < RuntimeEventBuffer.BATCH_NANOS) {
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

    private suspend fun replyAndAwaitDelivery(webView: WebView, proxy: JavaScriptReplyProxy, response: RuntimeRpcResponse) {
        val delivered = CompletableDeferred<Unit>()
        try {
            reply(webView, proxy, response) {
                (response as? RuntimeRpcResponse.Success)?.release?.invoke()
                delivered.complete(Unit)
            }
        } catch (error: Throwable) {
            (response as? RuntimeRpcResponse.Success)?.release?.invoke()
            throw error
        }
        // Cancellation may stop waiting, but the posted Runnable retains its reservation until delivery.
        delivered.await()
    }

    private fun reply(
        webView: WebView,
        proxy: JavaScriptReplyProxy,
        response: RuntimeRpcResponse,
        onDelivered: () -> Unit = {},
    ) {
        if (!active.get()) {
            onDelivered()
            return
        }
        val candidate = RuntimeRpcJson.prepareResponse(response)
        val encoded = enforceRuntimeResponseLimit(candidate, maxPayloadBytes) {
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
        val deliver = Runnable {
            try {
                if (active.get() && attachedView.get() === webView) runCatching { proxy.postMessage(encoded) }
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
              // Native unacknowledged events and this per-document backlog have separate budgets.
              const earlyEvents = new Map();
              const queuedNames = new Map();
              const lostEvents = new Map();
              const stateListeners = new Map();
              const flushHandlers = new Set();
              const writeMethods = new Set(['storage.apply', 'storage.set', 'storage.remove', 'storage.clear', 'storage.secure.set', 'storage.secure.remove']);
              let sequence = 0;
              let runtimeState = null;
              let readyPromise = null;
              let closeToken = null;
              let handledCloseToken = null;
              let flushFinishedToken = null;
              let disposed = false;
              let earlyBytes = 0;
              let replayIterator = null;
              let replayScheduled = false;
              let restartReplay = false;
              let receivedSequence = 0;
              let acknowledgedSequence = 0;
              let ackTimer = null;
              let ackBackoff = false;
              let ackInFlight = false;
              let ackFailed = false;
              const runtimeError = (code, message, extra = {}) => Object.assign(new Error(message), { code, ...extra });
              const reportError = error => {
                try { globalThis.dispatchEvent(new CustomEvent('toolbox:runtime.error', { detail: error })); } catch (_) {}
              };
              const dispatchEventPayload = (name, payload) => {
                const callbacks = listeners.get(name);
                callbacks?.forEach(callback => { try { callback(payload); } catch (_) {} });
              };
              const scheduleReplay = () => {
                if (disposed || replayScheduled) return;
                replayScheduled = true;
                setTimeout(replayEvents, 0);
              };
              const replayEvents = () => {
                replayScheduled = false;
                if (disposed) return;
                if (!replayIterator || restartReplay) {
                  replayIterator = earlyEvents.entries();
                  restartReplay = false;
                }
                const started = performance.now();
                let inspected = 0;
                while (inspected < 32 && performance.now() - started < 2) {
                  const next = replayIterator.next();
                  if (next.done) { replayIterator = null; break; }
                  inspected++;
                  const [id, entry] = next.value;
                  if (!listeners.get(entry.name)?.size) continue;
                  earlyEvents.delete(id);
                  earlyBytes -= entry.charge;
                  const remaining = queuedNames.get(entry.name) - 1;
                  remaining ? queuedNames.set(entry.name, remaining) : queuedNames.delete(entry.name);
                  const payload = JSON.parse(entry.encoded).data;
                  dispatchEventPayload(entry.name, payload);
                }
                if (replayIterator || restartReplay) scheduleReplay();
              };
              const retainEvent = (encoded, response) => {
                const charge = encoded.length * 2 + 128;
                if (earlyEvents.size >= 4096 || charge > 1024 * 1024 - earlyBytes) {
                  const droppedCount = (lostEvents.get(response.event) || 0) + 1;
                  lostEvents.set(response.event, droppedCount);
                  reportError(runtimeError('EVENT_BACKLOG_OVERFLOW', 'Unsubscribed tool events exceeded the backlog budget', { event: response.event, droppedCount }));
                  return;
                }
                earlyEvents.set(response.sequence, { name: response.event, encoded, charge });
                earlyBytes += charge;
                queuedNames.set(response.event, (queuedNames.get(response.event) || 0) + 1);
                if (listeners.get(response.event)?.size) scheduleReplay();
              };
              const acknowledgeEvents = () => {
                if (ackTimer !== null) { clearTimeout(ackTimer); ackTimer = null; }
                ackBackoff = false;
                if (disposed || ackFailed || ackInFlight || receivedSequence <= acknowledgedSequence) return;
                const upTo = receivedSequence;
                ackInFlight = true;
                call('runtime.ackEvents', { sequence: upTo }).then(() => {
                  acknowledgedSequence = upTo;
                }).catch(error => {
                  if (error.code === 'BUSY') {
                    ackBackoff = true;
                    ackTimer = setTimeout(acknowledgeEvents, 50);
                  }
                  else { ackFailed = true; reportError(error); }
                }).finally(() => {
                  ackInFlight = false;
                  arrangeAck();
                });
              };
              const arrangeAck = () => {
                if (disposed || ackFailed || ackInFlight || ackBackoff || receivedSequence <= acknowledgedSequence) return;
                if (receivedSequence - acknowledgedSequence >= 32) acknowledgeEvents();
                else if (ackTimer === null) ackTimer = setTimeout(acknowledgeEvents, 50);
              };
              const replayState = listener => {
                if (!runtimeState || !stateListeners.has(listener) || stateListeners.get(listener) >= runtimeState.revision) return;
                stateListeners.set(listener, runtimeState.revision);
                try { listener(runtimeState); } catch (_) {}
              };
              const receiveState = state => {
                if (!state || state.generation !== $generation || !Number.isSafeInteger(state.revision) ||
                    state.revision < 0 || typeof state.foreground !== 'boolean' || typeof state.closing !== 'boolean' ||
                    (runtimeState && state.revision <= runtimeState.revision)) return;
                runtimeState = Object.freeze({ generation: state.generation, revision: state.revision, foreground: state.foreground, closing: state.closing });
                closeToken = state.closing ? state.closeToken : null;
                stateListeners.forEach((_, listener) => replayState(listener));
                if (!state.closing || typeof closeToken !== 'string' || closeToken === handledCloseToken) return;
                const ticket = closeToken;
                handledCloseToken = ticket;
                for (const streamId of streams.keys()) cancelStream(streamId).catch(() => undefined);
                Promise.allSettled([...flushHandlers].map(handler => Promise.resolve().then(handler))).then(results => {
                  const saved = results.every(result => result.status === 'fulfilled');
                  if (disposed || closeToken !== ticket) return;
                  flushFinishedToken = ticket;
                  return call('runtime.flushComplete', { closeToken: ticket, saved });
                }).catch(error => { if (error.code !== 'CANCELLED' && error.code !== 'INVALID_SESSION') reportError(error); });
              };
              nativeBridge.onmessage = event => {
                let response;
                try { response = JSON.parse(event.data); } catch (_) { return; }
                if (response.type === 'runtimeState') { receiveState(response.data); return; }
                if (response.type === 'event' && typeof response.event === 'string') {
                  if (response.generation !== $generation || !Number.isSafeInteger(response.sequence) || response.sequence <= receivedSequence) return;
                  receivedSequence = response.sequence;
                  if (listeners.get(response.event)?.size && earlyEvents.size === 0) dispatchEventPayload(response.event, response.data);
                  else retainEvent(event.data, response);
                  try { globalThis.dispatchEvent(new CustomEvent(`toolbox:${'$'}{response.event}`, { detail: response.data })); } catch (_) {}
                  arrangeAck();
                  return;
                }
                const waiter = pending.get(response.id);
                if (!waiter) return;
                pending.delete(response.id);
                response.ok ? waiter.resolve(response.result) : waiter.reject(Object.assign(new Error(response.error.message), response.error));
              };
              const invalidStorageMutation = () => Object.assign(new Error('Storage mutation must contain JSON values'), { code: 'INVALID_REQUEST' });
              const storageJson = (_, value) => {
                if (typeof value === 'undefined' || typeof value === 'function' || typeof value === 'symbol' ||
                    typeof value === 'bigint' || (typeof value === 'number' && !Number.isFinite(value))) throw invalidStorageMutation();
                return value;
              };
              const call = (method, params = {}) => {
                if (disposed) return Promise.reject(runtimeError('SESSION_ENDED', 'The tool document has ended'));
                const isWrite = writeMethods.has(method);
                const ticket = isWrite || method === 'runtime.flushComplete' ? closeToken : null;
                if (isWrite && ticket && flushFinishedToken === ticket) return Promise.reject(runtimeError('SESSION_ENDED', 'The final save has completed'));
                const id = `${'$'}{Date.now().toString(36)}-${'$'}{(++sequence).toString(36)}`;
                let encoded;
                try {
                  encoded = JSON.stringify({ id, method, params, nonce: $nonce, toolId: $toolId,
                    versionCode: ${identity.versionCode}, generation: $generation
                  }, method === 'storage.apply' ? storageJson : undefined);
                } catch (error) { return Promise.reject(error); }
                const attempt = () => new Promise((resolve, reject) => {
                  if (disposed || (ticket && closeToken !== ticket)) { reject(runtimeError('CANCELLED', 'The close request was cancelled')); return; }
                  pending.set(id, { resolve, reject });
                  try { nativeBridge.postMessage(encoded); } catch (error) { pending.delete(id); reject(error); }
                }).catch(error => {
                  // Retry final writes/acknowledgement while this close is current; keep native budgets unchanged.
                  if (ticket && closeToken === ticket && error.code === 'BUSY') return new Promise(resolve => setTimeout(resolve, 50)).then(attempt);
                  throw error;
                });
                return attempt();
              };
              const ensureReady = () => {
                if (!readyPromise) readyPromise = call('ready').then(result => {
                  receiveState(result.runtimeState);
                  return { ...result, runtimeState };
                }).catch(error => { readyPromise = null; throw error; });
                return readyPromise;
              };
              const subscribeState = listener => {
                if (typeof listener !== 'function') throw new TypeError('listener must be a function');
                stateListeners.set(listener, -1);
                queueMicrotask(() => replayState(listener));
                ensureReady().catch(reportError);
                return () => stateListeners.delete(listener);
              };
              const bytes = value => value instanceof Uint8Array ? Array.from(value) : value;
              const networkRequest = request => {
                if (request?.maxResponseBytes !== undefined &&
                    (!Number.isSafeInteger(request.maxResponseBytes) || request.maxResponseBytes < 1)) {
                  throw Object.assign(new TypeError('maxResponseBytes must be a positive safe integer'), { code: 'INVALID_REQUEST' });
                }
                return request && request.body instanceof Uint8Array
                  ? { ...request, body: Array.from(request.body), bodyEncoding: 'bytes' } : request;
              };
              const streams = new Map();
              const streamCancelled = () => Object.assign(new Error('Network stream cancelled'), { code: 'CANCELLED' });
              const forgetStream = streamId => {
                const state = streams.get(streamId);
                if (state) state.signal?.removeEventListener('abort', state.abort);
                streams.delete(streamId);
              };
              const cancelStream = streamId => {
                const state = streams.get(streamId);
                if (state) state.cancelled = true;
                forgetStream(streamId);
                return call('network.cancelStream', { streamId });
              };
              globalThis.addEventListener('pagehide', () => {
                for (const streamId of streams.keys()) cancelStream(streamId).catch(() => undefined);
              });
              const openStream = async (request, options = {}) => {
                const streamId = 'stream-' + Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('');
                const state = { signal: options.signal, cancelled: false, abort: () => cancelStream(streamId).catch(() => undefined) };
                streams.set(streamId, state);
                state.signal?.addEventListener('abort', state.abort, { once: true });
                if (state.signal?.aborted) { forgetStream(streamId); throw streamCancelled(); }
                try {
                  const opened = await call('network.openStream', { streamId, request: networkRequest(request) });
                  if (state.cancelled) {
                    cancelStream(streamId).catch(() => undefined);
                    throw streamCancelled();
                  }
                  return opened;
                } catch (error) {
                  forgetStream(streamId);
                  if (state.cancelled) throw streamCancelled();
                  throw error;
                }
              };
              const readStream = async (streamId, options = {}) => {
                const expectedChunkBytes = options.expectedChunkBytes;
                if (expectedChunkBytes !== undefined && (!Number.isSafeInteger(expectedChunkBytes) || expectedChunkBytes < 1)) {
                  throw Object.assign(new TypeError('expectedChunkBytes must be a positive safe integer'), { code: 'INVALID_REQUEST' });
                }
                const state = streams.get(streamId);
                try {
                  const chunk = await call('network.readStream', { streamId, ...(expectedChunkBytes === undefined ? {} : { expectedChunkBytes }) });
                  if (state?.cancelled) throw streamCancelled();
                  if (chunk.done) forgetStream(streamId);
                  return { ...chunk, data: Uint8Array.from(atob(chunk.data), character => character.charCodeAt(0)) };
                } catch (error) {
                  if (error.code !== 'BUSY') cancelStream(streamId).catch(() => undefined);
                  throw error;
                }
              };
              const subscribe = (name, listener) => {
                if (typeof listener !== 'function') throw new TypeError('listener must be a function');
                if (lostEvents.has(name)) throw runtimeError('EVENT_BACKLOG_OVERFLOW', 'Some early events were lost', { event: name, droppedCount: lostEvents.get(name) });
                let callbacks = listeners.get(name);
                if (!callbacks) listeners.set(name, callbacks = new Set());
                callbacks.add(listener);
                if (queuedNames.has(name)) { restartReplay = true; scheduleReplay(); }
                ensureReady().catch(reportError);
                return () => { callbacks.delete(listener); if (!callbacks.size) listeners.delete(name); };
              };
              const api = {
                ready: ensureReady,
                runtime: {
                  getState: () => call('runtime.getState').then(state => { receiveState(state); return runtimeState; }),
                  onStateChanged: subscribeState,
                  registerFlushHandler: handler => {
                    if (typeof handler !== 'function') throw new TypeError('handler must be a function');
                    flushHandlers.add(handler);
                    ensureReady().catch(reportError);
                    return () => flushHandlers.delete(handler);
                  }
                },
                ui: { toast: message => call('ui.toast', { message }) },
                crypto: { sha256: value => call('crypto.sha256', { value: bytes(value) }) },
                storage: {
                  get: key => call('storage.get', { key }),
                  getMany: keys => call('storage.getMany', { keys }),
                  apply: mutation => mutation && typeof mutation === 'object' && !Array.isArray(mutation)
                    ? call('storage.apply', mutation) : Promise.reject(invalidStorageMutation()),
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
                network: { request: request => call('network.request', networkRequest(request)), openStream, readStream, cancelStream },
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
                  updateActivity: request => call('background.updateActivity', request),
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
                browser: { open: url => call('browser.open', { url }).then(() => undefined) },
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
              globalThis.addEventListener('pagehide', () => {
                disposed = true;
                if (ackTimer !== null) clearTimeout(ackTimer);
                earlyEvents.clear(); queuedNames.clear(); lostEvents.clear();
                listeners.clear(); stateListeners.clear(); flushHandlers.clear();
                earlyBytes = 0;
                pending.forEach(waiter => waiter.reject(runtimeError('SESSION_ENDED', 'The tool document has ended')));
                pending.clear();
              });
              queueMicrotask(() => ensureReady().catch(reportError));
            })();
        """.trimIndent()
    }

    private companion object {
        val controlBudget = RuntimeRequestBudget(64 * 1024)
        const val BRIDGE_OBJECT = "__toolboxNative"
        val EVENT_NAME = Regex("^[a-z][a-zA-Z0-9.]+$")
    }
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
        m2Handlers = configuration.m2Handlers,
        m3Handlers = configuration.m3Handlers,
        browserLaunchGuard = configuration.browserLaunchGuard,
    )
}
