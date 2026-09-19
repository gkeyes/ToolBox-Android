package io.toolbox.tool.runtime

import io.toolbox.core.data.ResourceCapacity
import io.toolbox.tool.api.ContractPhase
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxApiV1
import io.toolbox.tool.api.ToolBoxCapabilityId
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.Base64
import kotlinx.coroutines.CancellationException

sealed interface RpcValue {
    data object Null : RpcValue
    data class Bool(val value: Boolean) : RpcValue
    data class Number(val value: Double) : RpcValue
    data class StringValue(val value: String) : RpcValue
    data class ArrayValue(val value: List<RpcValue>) : RpcValue
    data class ObjectValue(val value: Map<String, RpcValue>) : RpcValue
}

data class RuntimeSessionIdentity(
    val toolId: String,
    val versionCode: Int,
    val generation: String,
    val hostVersion: String,
    val nonce: String,
    val exactOrigin: String,
    val declaredCapabilities: Set<String>,
)

data class RuntimeInboundContext(
    val sourceOrigin: String,
    val isMainFrame: Boolean,
    /** Legacy call context, ignored: authorization does not expire with touch age. */
    val recentTouchAgeMillis: Long? = null,
)

data class RuntimeRpcRequest(
    val id: String,
    val method: String,
    val nonce: String,
    val toolId: String,
    val versionCode: Int,
    val generation: String,
    val params: RpcValue.ObjectValue,
    val encodedBytes: Int,
)

enum class RuntimeRpcErrorCode {
    UNSUPPORTED,
    INVALID_REQUEST,
    INVALID_SESSION,
    WRONG_ORIGIN,
    NOT_MAIN_FRAME,
    NOT_DECLARED,
    PERMISSION_DENIED,
    SYSTEM_PERMISSION_DENIED,
    USER_GESTURE_REQUIRED,
    BUSY,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    CANCELLED,
    SESSION_ENDED,
    NOT_FOUND,
    DUPLICATE_TASK,
    NETWORK_BLOCKED,
    NETWORK_UNAVAILABLE,
    NETWORK_TIMEOUT,
    INTERNAL_ERROR,
}

data class RuntimeRpcError(
    val code: RuntimeRpcErrorCode,
    val message: String,
    val retryAfterMs: Long? = null,
) {
    init { requireValidRetryAfterMs(retryAfterMs) }
}

internal fun requireValidRetryAfterMs(value: Long?) {
    require(value == null || value in 0..9_007_199_254_740_991L)
}

internal fun RuntimeRpcError.toRpcValue(): RpcValue.ObjectValue = RpcValue.ObjectValue(buildMap {
    put("code", RpcValue.StringValue(code.name))
    put("message", RpcValue.StringValue(message))
    retryAfterMs?.let { put("retryAfterMs", RpcValue.Number(it.toDouble())) }
})

sealed interface RuntimeRpcResponse {
    val id: String

    data class Success(override val id: String, val result: RpcValue, val release: () -> Unit = {}) : RuntimeRpcResponse
    data class Failure(override val id: String, val error: RuntimeRpcError) : RuntimeRpcResponse
}

sealed interface RuntimePolicyDecision {
    data object Allowed : RuntimePolicyDecision
    data class Denied(val code: RuntimeRpcErrorCode, val message: String, val retryAfterMs: Long? = null) : RuntimePolicyDecision {
        init { requireValidRetryAfterMs(retryAfterMs) }
    }
}

interface RuntimeAuthorizationPolicy {
    suspend fun isCurrent(identity: RuntimeSessionIdentity): Boolean
    suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId): Boolean
    suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>): Boolean
    suspend fun admit(
        identity: RuntimeSessionIdentity,
        method: MethodDescriptor,
        encodedBytes: Int,
    ): RuntimePolicyDecision
}

class RuntimeHandlerException(
    val errorCode: RuntimeRpcErrorCode,
    override val message: String,
    val retryAfterMs: Long? = null,
) : Exception(message) {
    init { requireValidRetryAfterMs(retryAfterMs) }
}

fun interface RuntimeToastHandler {
    suspend fun show(message: String)
}

interface RuntimeStorageHandler {
    suspend fun get(key: String): RpcValue?
    suspend fun set(key: String, value: RpcValue)
    suspend fun remove(key: String)
    suspend fun keys(): List<String>
    suspend fun clear()
}

data class RuntimeStorageSet(val key: String, val value: RpcValue)

data class RuntimeStorageMutation(
    val set: List<RuntimeStorageSet> = emptyList(),
    val remove: List<String> = emptyList(),
)

/** Ordinary storage only: secure storage deliberately has no batch interface. */
interface RuntimeBatchStorageHandler : RuntimeStorageHandler {
    suspend fun getMany(keys: List<String>): List<RpcValue?>
    suspend fun apply(mutation: RuntimeStorageMutation)
}

fun interface RuntimeDeviceBasicHandler {
    suspend fun getBasicInfo(): RuntimeBasicDeviceInfo
}

data class RuntimeBasicDeviceInfo(
    val apiLevel: Int,
    val locale: String,
    val timeZone: String,
    val screenClass: String,
)

fun interface RuntimeHapticsHandler {
    suspend fun perform(effect: String)
}

fun interface RuntimeClipboardWriteHandler {
    suspend fun writeText(text: String)
}

data class RuntimeM1Handlers(
    val toast: RuntimeToastHandler? = null,
    val storage: RuntimeStorageHandler? = null,
    val secureStorage: RuntimeStorageHandler? = null,
    val deviceBasic: RuntimeDeviceBasicHandler? = null,
    val haptics: RuntimeHapticsHandler? = null,
    val clipboardWrite: RuntimeClipboardWriteHandler? = null,
)

fun interface RuntimeNetworkHandler {
    suspend fun request(request: RuntimeNetworkRequest): RuntimeNetworkResponse
    suspend fun openStream(streamId: String, request: RuntimeNetworkRequest): RuntimeNetworkStreamResponse =
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Network streaming is unavailable")
    suspend fun readStream(streamId: String, maxChunkBytes: Int): RuntimeNetworkStreamChunk =
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Network streaming is unavailable")
    suspend fun cancelStream(streamId: String): Unit =
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Network streaming is unavailable")
    fun cancelStreams() = Unit
    fun close() = Unit
}

enum class RuntimeNetworkMethod { GET, POST, PUT, PATCH, DELETE, HEAD }

data class RuntimeNetworkRequest(
    val url: String,
    val method: RuntimeNetworkMethod,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val bodyIsJson: Boolean = false,
    val timeoutMillis: Long? = null,
    val maxResponseBytes: Long? = null,
)

data class RuntimeNetworkResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val bodyEncoding: RuntimeNetworkBodyEncoding = RuntimeNetworkBodyEncoding.TEXT,
    val release: () -> Unit = {},
)

enum class RuntimeNetworkBodyEncoding { TEXT, BASE64 }

interface RuntimeNotificationHandler {
    suspend fun post(notificationId: String, title: String, body: String)
    suspend fun update(notificationId: String, title: String, body: String) = post(notificationId, title, body)
    suspend fun cancel(notificationId: String)
    suspend fun startLive(request: RuntimeLiveNotificationRequest): RuntimeLiveNotificationResult = throw RuntimeHandlerException(
        RuntimeRpcErrorCode.UNSUPPORTED,
        "Live notifications are unavailable",
    )

    suspend fun updateLive(request: RuntimeLiveNotificationRequest): RuntimeLiveNotificationResult = startLive(request)

    suspend fun endLive(sessionId: String): Unit = throw RuntimeHandlerException(
        RuntimeRpcErrorCode.UNSUPPORTED,
        "Live notifications are unavailable",
    )
}

enum class RuntimeLiveNotificationTone { NEUTRAL, POSITIVE, NEGATIVE, WARNING }

data class RuntimeLiveNotificationRequest(
    val sessionId: String,
    val title: String,
    val primaryText: String,
    val secondaryText: String?,
    val body: String?,
    val shortText: String?,
    val updatedAt: Long?,
    val progress: Int?,
    val accentColor: String?,
    val tone: RuntimeLiveNotificationTone,
)

enum class RuntimeAndroidLiveStatus { REQUESTED, UNAVAILABLE, NOT_ALLOWED }

enum class RuntimeHyperOsIslandStatus { REQUESTED, UNAVAILABLE }

data class RuntimeLiveNotificationResult(
    val androidLive: RuntimeAndroidLiveStatus,
    val hyperOsIsland: RuntimeHyperOsIslandStatus,
    val hyperOsProtocolVersion: Int,
    val hyperOsPermissionReported: Boolean,
)

sealed interface RuntimeBackgroundTaskOperation {
    data class HttpGet(val url: String) : RuntimeBackgroundTaskOperation
    data class Notify(val title: String, val body: String) : RuntimeBackgroundTaskOperation
}

data class RuntimeTaskConstraints(
    val network: RuntimeNetworkConstraint?,
)

enum class RuntimeNetworkConstraint { NONE, CONNECTED }

data class RuntimeBackgroundTaskSpec(
    val key: String,
    val operation: RuntimeBackgroundTaskOperation,
    val constraints: RuntimeTaskConstraints?,
)

enum class RuntimeBackgroundTaskState { QUEUED, RUNNING, COMPLETED, CANCELLED }
enum class RuntimeBackgroundRunOutcome { SUCCEEDED, FAILED, CANCELLED }

data class RuntimeBackgroundTaskSummary(
    val taskId: String,
    val key: String,
    val state: RuntimeBackgroundTaskState,
    val periodic: Boolean,
    val nextRunAt: Long?,
)

data class RuntimeBackgroundTaskError(
    val code: RuntimeRpcErrorCode,
    val message: String,
    val retryAfterMs: Long? = null,
) {
    init { requireValidRetryAfterMs(retryAfterMs) }
}

data class RuntimeBackgroundTaskRunResult(
    val taskId: String,
    val outcome: RuntimeBackgroundRunOutcome,
    val completedAt: Long,
    val status: Int?,
    val body: String?,
    val error: RuntimeBackgroundTaskError?,
)

interface RuntimeBackgroundTaskHandler {
    suspend fun enqueue(spec: RuntimeBackgroundTaskSpec): String
    suspend fun schedulePeriodic(spec: RuntimeBackgroundTaskSpec, intervalMinutes: Long): String
    suspend fun list(): List<RuntimeBackgroundTaskSummary>
    suspend fun getResult(taskId: String): RuntimeBackgroundTaskRunResult?

    suspend fun cancel(taskId: String): Boolean
}

data class RuntimeBackgroundStartOptions(
    val restoreAfterProcessDeath: Boolean,
    val restoreAfterReboot: Boolean,
)

data class RuntimeBackgroundSessionSummary(
    val sessionId: String,
    val startedAt: Long,
    val restoreAfterProcessDeath: Boolean,
    val restoreAfterReboot: Boolean,
)

interface RuntimeContinuousBackgroundHandler {
    suspend fun start(options: RuntimeBackgroundStartOptions): RuntimeBackgroundSessionSummary
    suspend fun stop(sessionId: String): Boolean
    suspend fun status(sessionId: String): RuntimeBackgroundSessionSummary?
    suspend fun list(): List<RuntimeBackgroundSessionSummary>
    suspend fun setTimer(key: String, intervalMillis: Long)
    suspend fun cancelTimer(key: String): Boolean
}

data class RuntimeAlarmSummary(
    val alarmId: String,
    val triggerAt: Long,
    val scheduledAt: Long,
)

interface RuntimeAlarmHandler {
    suspend fun schedule(alarm: RuntimeAlarmSummary): RuntimeAlarmSummary
    suspend fun list(): List<RuntimeAlarmSummary>
    suspend fun cancel(alarmId: String): Boolean
}

data class RuntimeM2Handlers(
    val network: RuntimeNetworkHandler? = null,
    val notifications: RuntimeNotificationHandler? = null,
    val background: RuntimeBackgroundTaskHandler? = null,
    val continuousBackground: RuntimeContinuousBackgroundHandler? = null,
    val alarms: RuntimeAlarmHandler? = null,
)

fun interface RuntimeClipboardReadHandler {
    suspend fun readText(): String
}

fun interface RuntimeShareTextHandler {
    suspend fun shareText(text: String)
}

/** The native launcher must invoke beforeLaunch immediately before its foreground side effect. */
fun interface RuntimeBrowserOpenHandler {
    suspend fun open(url: String, beforeLaunch: suspend () -> Unit)
}

data class RuntimeFileToken(
    val token: String,
    val name: String,
    val mimeType: String,
    val size: Long,
)

interface RuntimeFilesHandler {
    suspend fun open(mimeTypes: List<String>): RuntimeFileToken?
    suspend fun save(suggestedName: String, mimeType: String, content: ByteArray): RuntimeFileToken?
    suspend fun capabilityFor(token: String): ToolBoxCapabilityId
    suspend fun consume(token: String, maxBytes: Int): ByteArray
}

fun interface RuntimeSessionCleanupHandler {
    fun close()
}

fun interface RuntimeShortcutHandler {
    suspend fun pin(name: String?): Boolean
}

fun interface RuntimeCameraHandler {
    suspend fun capture(): RuntimeFileToken?
}

data class RuntimeLocationResult(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: Long,
)

fun interface RuntimeLocationHandler {
    suspend fun getCurrent(precise: Boolean, timeoutMillis: Long): RuntimeLocationResult
}

data class RuntimeLocationWatchOptions(
    val precise: Boolean,
    val intervalMillis: Long,
    val minDistanceMeters: Float,
    val allowBackground: Boolean,
)

interface RuntimeLocationWatchHandler {
    suspend fun watch(options: RuntimeLocationWatchOptions): String
    suspend fun clearWatch(watchId: String): Boolean
}

interface RuntimeNetworkDomainHandler {
    suspend fun authorizeDomain(domain: String): Boolean
    suspend fun listDomains(): List<String>
}

data class RuntimeM3Handlers(
    val clipboardRead: RuntimeClipboardReadHandler? = null,
    val shareText: RuntimeShareTextHandler? = null,
    val browserOpen: RuntimeBrowserOpenHandler? = null,
    val files: RuntimeFilesHandler? = null,
    val shortcuts: RuntimeShortcutHandler? = null,
    val camera: RuntimeCameraHandler? = null,
    val location: RuntimeLocationHandler? = null,
    val locationWatch: RuntimeLocationWatchHandler? = null,
    val sessionCleanup: RuntimeSessionCleanupHandler? = null,
    val networkDomains: RuntimeNetworkDomainHandler? = null,
)

class RuntimeRpcDispatcher(
    private val identity: RuntimeSessionIdentity,
    private val authorization: RuntimeAuthorizationPolicy,
    private val handlers: RuntimeM1Handlers,
    private val m2Handlers: RuntimeM2Handlers = RuntimeM2Handlers(),
    private val m3Handlers: RuntimeM3Handlers = RuntimeM3Handlers(),
    private val foregroundInteractionGuard: suspend () -> Unit = {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "No foreground tool session is available")
    },
    private val browserLaunchGuard: suspend () -> Unit = {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "No foreground tool session is available")
    },
) {
    private val maxResponseBytes: Int
        get() = (ResourceCapacity.availableHeapBytes() / Char.SIZE_BYTES).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

    suspend fun dispatch(request: RuntimeRpcRequest, inbound: RuntimeInboundContext): RuntimeRpcResponse {
        fun failure(code: RuntimeRpcErrorCode, message: String, retryAfterMs: Long? = null) =
            RuntimeRpcResponse.Failure(request.id, RuntimeRpcError(code, message, retryAfterMs))

        if (!isSafeRuntimeRequestId(request.id)) {
            return RuntimeRpcResponse.Failure(
                "",
                RuntimeRpcError(RuntimeRpcErrorCode.INVALID_REQUEST, "Request id is invalid"),
            )
        }

        if (!sameExactOrigin(inbound.sourceOrigin, identity.exactOrigin)) {
            return failure(RuntimeRpcErrorCode.WRONG_ORIGIN, "Request source is not this tool origin")
        }
        if (!inbound.isMainFrame) {
            return failure(RuntimeRpcErrorCode.NOT_MAIN_FRAME, "Only the main frame may call ToolBox")
        }
        if (
            request.nonce != identity.nonce ||
            request.toolId != identity.toolId ||
            request.versionCode != identity.versionCode ||
            request.generation != identity.generation
        ) {
            return failure(RuntimeRpcErrorCode.INVALID_SESSION, "ToolBox session identity is stale")
        }
        if (!authorization.isCurrent(identity)) {
            m2Handlers.network?.cancelStreams()
            return failure(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
        }
        val method = ToolBoxApiV1.method(request.method)
            ?: return failure(RuntimeRpcErrorCode.UNSUPPORTED, "Unknown ToolBox method")
        if (method.contractPhase !in SUPPORTED_CONTRACT_PHASES) {
            return failure(RuntimeRpcErrorCode.UNSUPPORTED, "Method is not available in this host milestone")
        }
        val capability = try {
            if (method.name == "files.read") {
                requireHandler(m3Handlers.files).capabilityFor(
                    request.params.requiredIdentifier("token"),
                )
            } else {
                method.capability
            }
        } catch (failure: RuntimeHandlerException) {
            return failure(failure.errorCode, failure.message, failure.retryAfterMs)
        } catch (_: IllegalArgumentException) {
            return failure(RuntimeRpcErrorCode.INVALID_REQUEST, "Invalid parameters for ${method.name}")
        }
        if (capability != null) {
            val descriptor = ToolBoxApiV1.capability(capability)
            if (descriptor.wireName !in identity.declaredCapabilities) {
                return failure(RuntimeRpcErrorCode.NOT_DECLARED, "Capability is not declared by this tool")
            }
            if (!authorization.isGranted(identity, capability)) {
                if (capability == ToolBoxCapabilityId.NETWORK) m2Handlers.network?.cancelStreams()
                return failure(RuntimeRpcErrorCode.PERMISSION_DENIED, "Capability is disabled for this tool")
            }
            if (!authorization.hasSystemPermissions(identity, descriptor.systemPermissions)) {
                if (capability == ToolBoxCapabilityId.NETWORK) m2Handlers.network?.cancelStreams()
                return failure(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "Required Android permission is unavailable")
            }

        }
        when (val decision = authorization.admit(identity, method, request.encodedBytes)) {
            RuntimePolicyDecision.Allowed -> Unit
            is RuntimePolicyDecision.Denied -> return failure(decision.code, decision.message, decision.retryAfterMs)
        }
        if (!authorization.isCurrent(identity)) {
            m2Handlers.network?.cancelStreams()
            return failure(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
        }
        if (capability != null) {
            val descriptor = ToolBoxApiV1.capability(capability)
            if (!authorization.isGranted(identity, capability)) {
                if (capability == ToolBoxCapabilityId.NETWORK) m2Handlers.network?.cancelStreams()
                return failure(RuntimeRpcErrorCode.PERMISSION_DENIED, "Capability was disabled before execution")
            }
            if (!authorization.hasSystemPermissions(identity, descriptor.systemPermissions)) {
                if (capability == ToolBoxCapabilityId.NETWORK) m2Handlers.network?.cancelStreams()
                return failure(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "Android permission changed before execution")
            }
        }
        val retained = mutableListOf<() -> Unit>()
        val released = java.util.concurrent.atomic.AtomicBoolean()
        val release = { if (released.compareAndSet(false, true)) retained.forEach { it() }; Unit }
        var transferred = false
        return try {
            // Existing grants authorize these effects. Require the current visible tool,
            // without a second permission or an arbitrary touch-screen countdown.
            if (capability in FOREGROUND_INTERACTION_CAPABILITIES && method.name != "files.read") {
                foregroundInteractionGuard()
            }
            val result = invoke(method.name, request.params, request.id, retained)
            RuntimeRpcResponse.Success(request.id, result, release).also { transferred = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeHandlerException) {
            failure(failure.errorCode, failure.message, failure.retryAfterMs)
        } catch (_: IllegalArgumentException) {
            failure(RuntimeRpcErrorCode.INVALID_REQUEST, "Invalid parameters for ${method.name}")
        } catch (error: Exception) {
            // Android's Binder capacity is a system boundary, not a ToolBox text quota.
            val causes = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            val ipcTooLarge = generateSequence<Throwable>(error) { it.cause }.takeWhile(causes::add)
                .any { it is android.os.TransactionTooLargeException }
            if (ipcTooLarge) {
                failure(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Android rejected this IPC payload; use a file for large content")
            } else {
                failure(RuntimeRpcErrorCode.INTERNAL_ERROR, "The native operation failed")
            }
        } finally { if (!transferred) release() }
    }

    private suspend fun invoke(
        method: String,
        params: RpcValue.ObjectValue,
        requestId: String,
        retained: MutableList<() -> Unit>,
    ): RpcValue = when (method) {
        "ready" -> RpcValue.ObjectValue(
            mapOf(
                "apiVersion" to RpcValue.StringValue(ToolBoxApiV1.API_VERSION),
                "hostVersion" to RpcValue.StringValue(identity.hostVersion),
                "toolId" to RpcValue.StringValue(identity.toolId),
                "generation" to RpcValue.StringValue(identity.generation),
            ),
        )
        "ui.toast" -> {
            requireHandler(handlers.toast).show(params.requiredString("message", maxResponseBytes))
            RpcValue.Null
        }
        "crypto.sha256" -> RpcValue.ObjectValue(
            mapOf("hex" to RpcValue.StringValue(sha256(params.requiredBytes("value", maxResponseBytes)))),
        )
        "storage.get" -> requireHandler(handlers.storage).get(params.requiredKey()) ?: RpcValue.Null
        "storage.getMany" -> {
            params.requireOnly("keys")
            val keys = params.storageKeys("keys")
            RpcValue.ArrayValue(requireHandler(handlers.storage as? RuntimeBatchStorageHandler).getMany(keys).map { it ?: RpcValue.Null })
        }
        "storage.apply" -> {
            val mutation = params.storageMutation()
            requireHandler(handlers.storage as? RuntimeBatchStorageHandler).apply(mutation)
            RpcValue.Null
        }
        "storage.set" -> {
            requireHandler(handlers.storage).set(params.requiredKey(), params.required("value"))
            RpcValue.Null
        }
        "storage.remove" -> {
            requireHandler(handlers.storage).remove(params.requiredKey())
            RpcValue.Null
        }
        "storage.keys" -> RpcValue.ArrayValue(
            requireHandler(handlers.storage).keys().map(RpcValue::StringValue),
        )
        "storage.clear" -> {
            requireHandler(handlers.storage).clear()
            RpcValue.Null
        }
        "storage.secure.get" -> requireHandler(handlers.secureStorage).get(params.requiredKey()) ?: RpcValue.Null
        "storage.secure.set" -> {
            requireHandler(handlers.secureStorage).set(params.requiredKey(), params.required("value"))
            RpcValue.Null
        }
        "storage.secure.remove" -> {
            requireHandler(handlers.secureStorage).remove(params.requiredKey())
            RpcValue.Null
        }
        "device.getBasicInfo" -> requireHandler(handlers.deviceBasic).getBasicInfo().toRpcValue()
        "haptics.perform" -> {
            val effect = params.requiredString("effect", 16)
            require(effect in setOf("click", "confirm", "reject"))
            requireHandler(handlers.haptics).perform(effect)
            RpcValue.Null
        }
        "clipboard.writeText" -> {
            requireHandler(handlers.clipboardWrite).writeText(params.requiredText("text", maxResponseBytes))
            RpcValue.Null
        }
        "network.authorizeDomain" -> {
            params.requireOnly("domain")
            val handler = requireHandler(m3Handlers.networkDomains)
            RpcValue.Bool(handler.authorizeDomain(params.requiredString("domain", 253)))
        }
        "network.listDomains" -> {
            params.requireOnly()
            RpcValue.ArrayValue(requireHandler(m3Handlers.networkDomains).listDomains().map(RpcValue::StringValue))
        }
        "network.request" -> {
            val response = requireHandler(m2Handlers.network).request(params.toNetworkRequest())
            retained += response.release
            requireNetworkStreamAuthorization()
            response.toRpcValue()
        }
        "network.openStream" -> {
            params.requireOnly("streamId", "request")
            val streamId = params.requiredNetworkStreamId()
            val networkRequest = (params.required("request") as? RpcValue.ObjectValue
                ?: throw IllegalArgumentException("request")).toNetworkRequest()
            val network = requireHandler(m2Handlers.network)
            val response = network.openStream(streamId, networkRequest)
            try {
                require(response.streamId == streamId)
                requireNetworkStreamAuthorization()
                val headers = RuntimeNetworkResponse(response.status, response.headers, "").toRpcValue().value.getValue("headers")
                streamResponseWithinBudget(requestId, RpcValue.ObjectValue(mapOf(
                    "streamId" to RpcValue.StringValue(streamId),
                    "status" to RpcValue.Number(response.status.toDouble()),
                    "headers" to headers,
                )))
            } catch (error: Exception) {
                runCatching { network.cancelStream(streamId) }
                throw error
            }
        }
        "network.readStream" -> {
            params.requireOnly("streamId", "expectedChunkBytes")
            val streamId = params.requiredNetworkStreamId()
            val expected = params.optionalLong("expectedChunkBytes", 1, MAX_SAFE_INTEGER)
            val rawBudget = runtimeNetworkStreamRawBudget(requestId, maxResponseBytes, expected)
            val network = requireHandler(m2Handlers.network)
            val chunk = network.readStream(streamId, rawBudget)
            retained += chunk.release
            run {
                requireNetworkStreamAuthorization()
                require(chunk.receivedBytes in 0..MAX_SAFE_INTEGER)
                require(chunk.data.size <= rawBudget)
                streamResponseWithinBudget(requestId, RpcValue.ObjectValue(mapOf(
                    "data" to RpcValue.StringValue(Base64.getEncoder().encodeToString(chunk.data)),
                    "done" to RpcValue.Bool(chunk.done),
                    "receivedBytes" to RpcValue.Number(chunk.receivedBytes.toDouble()),
                )))
            }
        }
        "network.cancelStream" -> {
            params.requireOnly("streamId")
            requireHandler(m2Handlers.network).cancelStream(params.requiredNetworkStreamId())
            RpcValue.Null
        }
        "notifications.post" -> {
            params.requireOnly("id", "title", "body")
            requireHandler(m2Handlers.notifications).post(
                notificationId = params.requiredIdentifier("id"),
                title = params.requiredString("title", maxResponseBytes),
                body = params.requiredText("body", maxResponseBytes),
            )
            RpcValue.Null
        }
        "notifications.update" -> {
            params.requireOnly("id", "title", "body")
            requireHandler(m2Handlers.notifications).update(
                notificationId = params.requiredIdentifier("id"),
                title = params.requiredString("title", maxResponseBytes),
                body = params.requiredText("body", maxResponseBytes),
            )
            RpcValue.Null
        }
        "notifications.cancel" -> {
            params.requireOnly("id")
            requireHandler(m2Handlers.notifications).cancel(
                params.requiredIdentifier("id"),
            )
            RpcValue.Null
        }
        "notifications.live.start" -> {
            requireHandler(m2Handlers.notifications)
                .startLive(params.toLiveNotificationRequest())
                .toRpcValue()
        }
        "notifications.live.update" -> {
            requireHandler(m2Handlers.notifications)
                .updateLive(params.toLiveNotificationRequest())
                .toRpcValue()
        }
        "notifications.live.end" -> {
            params.requireOnly("sessionId")
            requireHandler(m2Handlers.notifications).endLive(params.requiredSessionId())
            RpcValue.Null
        }
        "background.enqueue" -> RpcValue.StringValue(
            requireHandler(m2Handlers.background).enqueue(params.toBackgroundTaskSpec(periodic = false))
                .also { requireTaskId(it) },
        )
        "background.schedulePeriodic" -> {
            val spec = params.toBackgroundTaskSpec(periodic = true)
            val intervalMinutes = params.requiredLong("intervalMinutes", MIN_PERIODIC_INTERVAL_MINUTES, MAX_SAFE_INTEGER)
            RpcValue.StringValue(
                requireHandler(m2Handlers.background).schedulePeriodic(spec, intervalMinutes)
                    .also { requireTaskId(it) },
            )
        }
        "background.start" -> {
            params.requireOnly("restoreAfterProcessDeath", "restoreAfterReboot")
            requireHandler(m2Handlers.continuousBackground).start(
                RuntimeBackgroundStartOptions(
                    restoreAfterProcessDeath = params.optionalBoolean("restoreAfterProcessDeath") ?: true,
                    restoreAfterReboot = params.optionalBoolean("restoreAfterReboot") ?: false,
                ),
            ).toRpcValue()
        }
        "background.stop" -> {
            params.requireOnly("sessionId")
            if (!requireHandler(m2Handlers.continuousBackground).stop(params.requiredSessionId())) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Background session was not found")
            }
            RpcValue.Null
        }
        "background.status" -> {
            params.requireOnly("sessionId")
            requireHandler(m2Handlers.continuousBackground)
                .status(params.requiredSessionId())
                ?.toRpcValue()
                ?: RpcValue.Null
        }
        "background.list" -> {
            params.requireOnly()
            RpcValue.ArrayValue(requireHandler(m2Handlers.background).list().map { task -> task.toRpcValue() })
        }
        "background.listSessions" -> {
            params.requireOnly()
            RpcValue.ArrayValue(
                requireHandler(m2Handlers.continuousBackground).list().map { session -> session.toRpcValue() },
            )
        }
        "background.getResult" -> {
            params.requireOnly("taskId")
            requireHandler(m2Handlers.background)
                .getResult(params.requiredTaskId())
                ?.toRpcValue()
                ?: RpcValue.Null
        }
        "background.cancel" -> {
            params.requireOnly("taskId")
            val taskId = params.requiredTaskId()
            val cancelled = requireHandler(m2Handlers.background).cancel(taskId)
            if (!cancelled) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Background task was not found")
            }
            RpcValue.Null
        }
        "background.setTimer" -> {
            params.requireOnly("key", "intervalMs")
            requireHandler(m2Handlers.continuousBackground).setTimer(
                key = params.requiredIdentifier("key"),
                intervalMillis = params.requiredLong("intervalMs", 1, MAX_SAFE_INTEGER),
            )
            RpcValue.Null
        }
        "background.cancelTimer" -> {
            params.requireOnly("key")
            val cancelled = requireHandler(m2Handlers.continuousBackground).cancelTimer(
                params.requiredIdentifier("key"),
            )
            if (!cancelled) throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Timer was not found")
            RpcValue.Null
        }
        "clipboard.readText" -> {
            params.requireOnly()
            RpcValue.StringValue(requireHandler(m3Handlers.clipboardRead).readText())
        }
        "share.text" -> {
            params.requireOnly("text")
            requireHandler(m3Handlers.shareText).shareText(params.requiredText("text", maxResponseBytes))
            RpcValue.Null
        }
        "browser.open" -> {
            params.requireOnly("url")
            val url = validateRuntimeBrowserUrl(params.requiredString("url", maxResponseBytes))
            requireHandler(m3Handlers.browserOpen).open(url) {
                if (!authorization.isCurrent(identity)) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
                }
                if (!authorization.isGranted(identity, ToolBoxCapabilityId.BROWSER)) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Browser permission was disabled before launch")
                }
                // Runs after suspending authorization reads, on the launcher's Main dispatcher.
                browserLaunchGuard()
            }
            RpcValue.Null
        }
        "files.open" -> requireHandler(m3Handlers.files)
            .open(params.optionalMimeTypes())
            ?.toRpcValue()
            ?: RpcValue.Null
        "files.save" -> {
            params.requireOnly("suggestedName", "mimeType", "content")
            requireHandler(m3Handlers.files).save(
                suggestedName = params.requiredFileName(),
                mimeType = params.requiredMimeType("mimeType"),
                content = params.requiredBytes("content", maxResponseBytes),
            )?.toRpcValue() ?: RpcValue.Null
        }
        "files.read" -> {
            params.requireOnly("token")
            val content = requireHandler(m3Handlers.files).consume(
                params.requiredIdentifier("token"),
                runtimeFileReadRawBudget(requestId, maxResponseBytes),
            )
            require(content.size <= runtimeFileReadRawBudget(requestId, maxResponseBytes))
            RpcValue.ObjectValue(
                mapOf("base64" to RpcValue.StringValue(Base64.getEncoder().encodeToString(content))),
            )
        }
        "shortcuts.pin" -> {
            params.requireOnly("name")
            RpcValue.Bool(requireHandler(m3Handlers.shortcuts).pin(params.optionalDisplayName("name")))
        }
        "camera.capture" -> {
            params.requireOnly()
            requireHandler(m3Handlers.camera).capture()?.toRpcValue() ?: RpcValue.Null
        }
        "location.getCurrent" -> {
            params.requireOnly("accuracy", "timeoutMs")
            val precise = when (params.optionalString("accuracy", 16) ?: "coarse") {
                "coarse" -> false
                "precise" -> true
                else -> throw IllegalArgumentException("accuracy")
            }
            requireHandler(m3Handlers.location).getCurrent(
                precise = precise,
                timeoutMillis = params.optionalLong("timeoutMs", MIN_LOCATION_TIMEOUT_MS, MAX_SAFE_INTEGER)
                    ?: DEFAULT_LOCATION_TIMEOUT_MS,
            ).toRpcValue()
        }
        "location.watch" -> {
            params.requireOnly("accuracy", "intervalMs", "minDistanceMeters", "allowBackground")
            val precise = when (params.optionalString("accuracy", 16) ?: "coarse") {
                "coarse" -> false
                "precise" -> true
                else -> throw IllegalArgumentException("accuracy")
            }
            RpcValue.StringValue(
                requireHandler(m3Handlers.locationWatch).watch(
                    RuntimeLocationWatchOptions(
                        precise = precise,
                        intervalMillis = params.optionalLong("intervalMs", 0, MAX_SAFE_INTEGER) ?: 0,
                        minDistanceMeters = params.optionalFloat("minDistanceMeters", 0f) ?: 0f,
                        allowBackground = params.optionalBoolean("allowBackground") ?: false,
                    ),
                ).also { requireIdentifier(it) },
            )
        }
        "location.clearWatch" -> {
            params.requireOnly("watchId")
            val removed = requireHandler(m3Handlers.locationWatch).clearWatch(
                params.requiredIdentifier("watchId"),
            )
            if (!removed) throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Location watch was not found")
            RpcValue.Null
        }
        "alarms.schedule" -> {
            params.requireOnly("id", "triggerAt")
            val now = System.currentTimeMillis()
            requireHandler(m2Handlers.alarms).schedule(
                RuntimeAlarmSummary(
                    alarmId = params.requiredIdentifier("id"),
                    triggerAt = params.requiredLong("triggerAt", 0, MAX_SAFE_INTEGER),
                    scheduledAt = now,
                ),
            ).toRpcValue()
        }
        "alarms.list" -> {
            params.requireOnly()
            RpcValue.ArrayValue(requireHandler(m2Handlers.alarms).list().map { it.toRpcValue() })
        }
        "alarms.cancel" -> {
            params.requireOnly("id")
            val cancelled = requireHandler(m2Handlers.alarms).cancel(
                params.requiredIdentifier("id"),
            )
            if (!cancelled) throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Alarm was not found")
            RpcValue.Null
        }
        else -> throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Method has no native handler")
    }

    private fun <T> requireHandler(handler: T?): T = handler ?: throw RuntimeHandlerException(
        RuntimeRpcErrorCode.UNSUPPORTED,
        "This native capability is unavailable",
    )

    private suspend fun requireNetworkStreamAuthorization() {
        val code = when {
            !authorization.isCurrent(identity) -> RuntimeRpcErrorCode.INVALID_SESSION
            !authorization.isGranted(identity, ToolBoxCapabilityId.NETWORK) -> RuntimeRpcErrorCode.PERMISSION_DENIED
            !authorization.hasSystemPermissions(identity, ToolBoxApiV1.capability(ToolBoxCapabilityId.NETWORK).systemPermissions) -> RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED
            else -> return
        }
        m2Handlers.network?.cancelStreams()
        throw RuntimeHandlerException(code, "Network stream authorization changed before delivery")
    }

    private fun streamResponseWithinBudget(requestId: String, value: RpcValue): RpcValue {
        val encodedUpperBound = RuntimeRpcJson.encodeValue(RpcValue.ObjectValue(mapOf(
            "id" to RpcValue.StringValue(requestId), "ok" to RpcValue.Bool(true), "result" to value,
        ))).replace("</", "<\\/")
        if (encodedUpperBound.toByteArray(StandardCharsets.UTF_8).size > maxResponseBytes) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Network stream response exceeds the bridge message limit")
        }
        return value
    }

    private fun RpcValue.ObjectValue.requiredNetworkStreamId(): String =
        requiredString("streamId", 39).also { require(isNetworkStreamId(it)) }

    private fun RuntimeBasicDeviceInfo.toRpcValue(): RpcValue.ObjectValue {
        require(apiLevel >= 33)
        require(locale.isNotBlank())
        require(timeZone.isNotBlank())
        require(screenClass in setOf("compact", "medium", "expanded"))
        return RpcValue.ObjectValue(
            mapOf(
                "apiLevel" to RpcValue.Number(apiLevel.toDouble()),
                "locale" to RpcValue.StringValue(locale),
                "timeZone" to RpcValue.StringValue(timeZone),
                "screenClass" to RpcValue.StringValue(screenClass),
            ),
        )
    }

    private fun RuntimeNetworkResponse.toRpcValue(): RpcValue.ObjectValue {
        require(status in 100..599)
        val rpcHeaders = headers.entries.associate { (name, value) ->
            require(HEADER_NAME.matches(name))
            require(name.lowercase(Locale.ROOT) !in FORBIDDEN_RESPONSE_HEADERS)
            name to RpcValue.StringValue(value)
        }
        return RpcValue.ObjectValue(
            mapOf(
                "status" to RpcValue.Number(status.toDouble()),
                "headers" to RpcValue.ObjectValue(rpcHeaders),
                "body" to RpcValue.StringValue(body),
                "bodyEncoding" to RpcValue.StringValue(bodyEncoding.name.lowercase(Locale.ROOT)),
            ),
        )
    }

    private fun RuntimeBackgroundTaskSummary.toRpcValue(): RpcValue.ObjectValue {
        requireTaskId(taskId)
        requireIdentifier(key)
        nextRunAt?.let { require(it in 0..MAX_SAFE_INTEGER) }
        return RpcValue.ObjectValue(buildMap {
            put("kind", RpcValue.StringValue("task"))
            put("taskId", RpcValue.StringValue(taskId))
            put("key", RpcValue.StringValue(key))
            put("state", RpcValue.StringValue(state.name))
            put("periodic", RpcValue.Bool(periodic))
            nextRunAt?.let { put("nextRunAt", RpcValue.Number(it.toDouble())) }
        })
    }

    private fun RuntimeBackgroundSessionSummary.toRpcValue(): RpcValue.ObjectValue {
        requireIdentifier(sessionId)
        require(startedAt in 0..MAX_SAFE_INTEGER)
        return RpcValue.ObjectValue(
            mapOf(
                "sessionId" to RpcValue.StringValue(sessionId),
                "startedAt" to RpcValue.Number(startedAt.toDouble()),
                "restoreAfterProcessDeath" to RpcValue.Bool(restoreAfterProcessDeath),
                "restoreAfterReboot" to RpcValue.Bool(restoreAfterReboot),
            ),
        )
    }

    private fun RuntimeLiveNotificationResult.toRpcValue(): RpcValue.ObjectValue {
        require(hyperOsProtocolVersion >= 0)
        return RpcValue.ObjectValue(
            mapOf(
                "standard" to RpcValue.StringValue("POSTED"),
                "androidLive" to RpcValue.StringValue(androidLive.name),
                "hyperOsIsland" to RpcValue.StringValue(hyperOsIsland.name),
                "hyperOsProtocolVersion" to RpcValue.Number(hyperOsProtocolVersion.toDouble()),
                "hyperOsPermissionReported" to RpcValue.Bool(hyperOsPermissionReported),
            ),
        )
    }

    private fun RuntimeAlarmSummary.toRpcValue(): RpcValue.ObjectValue {
        requireIdentifier(alarmId)
        require(triggerAt in 0..MAX_SAFE_INTEGER)
        require(scheduledAt in 0..MAX_SAFE_INTEGER)
        return RpcValue.ObjectValue(
            mapOf(
                "id" to RpcValue.StringValue(alarmId),
                "triggerAt" to RpcValue.Number(triggerAt.toDouble()),
                "scheduledAt" to RpcValue.Number(scheduledAt.toDouble()),
            ),
        )
    }

    private fun RuntimeBackgroundTaskRunResult.toRpcValue(): RpcValue.ObjectValue {
        requireTaskId(taskId)
        require(completedAt in 0..MAX_SAFE_INTEGER)
        status?.let { require(it in 100..599) }
        error?.let {
            require(it.message.isNotEmpty())
        }
        return RpcValue.ObjectValue(buildMap {
            put("taskId", RpcValue.StringValue(taskId))
            put("outcome", RpcValue.StringValue(outcome.name))
            put("completedAt", RpcValue.Number(completedAt.toDouble()))
            status?.let { put("status", RpcValue.Number(it.toDouble())) }
            body?.let { put("body", RpcValue.StringValue(it)) }
            error?.let {
                put(
                    "error",
                    RuntimeRpcError(it.code, it.message, it.retryAfterMs).toRpcValue(),
                )
            }
        })
    }

    private fun RuntimeFileToken.toRpcValue(): RpcValue.ObjectValue {
        requireIdentifier(token)
        require(name.isNotBlank() && name.none(Char::isISOControl))
        requireMimeType(mimeType)
        require(size in 0..MAX_SAFE_INTEGER)
        return RpcValue.ObjectValue(
            mapOf(
                "token" to RpcValue.StringValue(token),
                "name" to RpcValue.StringValue(name),
                "mimeType" to RpcValue.StringValue(mimeType),
                "size" to RpcValue.Number(size.toDouble()),
            ),
        )
    }

    private fun RuntimeLocationResult.toRpcValue(): RpcValue.ObjectValue {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters >= 0.0)
        require(capturedAt in 0..MAX_SAFE_INTEGER)
        return RpcValue.ObjectValue(
            mapOf(
                "latitude" to RpcValue.Number(latitude),
                "longitude" to RpcValue.Number(longitude),
                "accuracyMeters" to RpcValue.Number(accuracyMeters),
                "capturedAt" to RpcValue.Number(capturedAt.toDouble()),
            ),
        )
    }

    private fun sameExactOrigin(actual: String, expected: String): Boolean {
        val left = runCatching { URI(actual) }.getOrNull() ?: return false
        val right = runCatching { URI(expected) }.getOrNull() ?: return false
        return left.scheme?.lowercase(Locale.ROOT) == "https" &&
            right.scheme?.lowercase(Locale.ROOT) == "https" &&
            left.rawUserInfo == null && right.rawUserInfo == null &&
            left.host == right.host && left.port == -1 && right.port == -1 &&
            left.rawPath.orEmpty().let { it.isEmpty() || it == "/" } &&
            right.rawPath.orEmpty().let { it.isEmpty() || it == "/" } &&
            left.rawQuery == null && right.rawQuery == null &&
            left.rawFragment == null && right.rawFragment == null
    }

    private fun RpcValue.ObjectValue.required(name: String): RpcValue =
        value[name] ?: throw IllegalArgumentException("Missing $name")

    private fun RpcValue.ObjectValue.requireOnly(vararg allowed: String) {
        require(value.keys.all { it in allowed })
    }

    private fun RpcValue.ObjectValue.requiredString(name: String, maxChars: Int = Int.MAX_VALUE): String {
        val result = (required(name) as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException(name)
        require(result.length in 1..maxChars)
        return result
    }

    private fun RpcValue.ObjectValue.requiredText(name: String, maxChars: Int = Int.MAX_VALUE): String {
        val text = (required(name) as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException(name)
        require(text.length <= maxChars)
        return text
    }

    private fun RpcValue.ObjectValue.storageKeys(name: String): List<String> {
        val entries = (required(name) as? RpcValue.ArrayValue)?.value ?: throw IllegalArgumentException(name)

        return entries.map { entry ->
            val key = (entry as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException(name)
            require(key.isNotBlank() && key.none(Char::isISOControl))
            key
        }
    }

    private fun RpcValue.ObjectValue.storageMutation(): RuntimeStorageMutation {
        requireOnly("set", "remove")
        val set = value["set"]?.let { raw ->
            val entries = (raw as? RpcValue.ArrayValue)?.value ?: throw IllegalArgumentException("set")

            entries.map { entry ->
                val item = entry as? RpcValue.ObjectValue ?: throw IllegalArgumentException("set")
                item.requireOnly("key", "value")
                val key = item.requiredKey()
                require(key.isNotBlank())
                val value = item.required("value")
                requireStorageJson(value)
                RuntimeStorageSet(key, value)
            }
        }.orEmpty()
        val remove = if ("remove" in value) storageKeys("remove") else emptyList()
        val keys = set.map { it.key } + remove
        require(keys.distinct().size == keys.size)
        return RuntimeStorageMutation(set, remove)
    }

    private fun requireStorageJson(value: RpcValue) {
        val pending = ArrayDeque<RpcValue>()
        pending.addLast(value)
        while (pending.isNotEmpty()) {
            when (val item = pending.removeLast()) {
                is RpcValue.Number -> require(item.value.isFinite())
                is RpcValue.ArrayValue -> pending.addAll(item.value)
                is RpcValue.ObjectValue -> pending.addAll(item.value.values)
                else -> Unit
            }
        }
    }

    private fun RpcValue.ObjectValue.requiredKey(): String {
        val key = requiredString("key")
        require(!key.any(Char::isISOControl))
        return key
    }

    private fun RpcValue.ObjectValue.requiredIdentifier(name: String): String {
        val identifier = requiredString(name)
        require(identifier == identifier.trim())
        require(identifier.none(Char::isISOControl))
        return identifier
    }

    private fun RpcValue.ObjectValue.requiredTaskId(): String =
        requiredIdentifier("taskId")

    private fun RpcValue.ObjectValue.requiredSessionId(): String =
        requiredIdentifier("sessionId")

    private fun requireTaskId(taskId: String) {
        requireIdentifier(taskId)
    }

    private fun requireIdentifier(value: String) {
        require(value.isNotEmpty())
        require(value == value.trim())
        require(value.none(Char::isISOControl))
    }

    private fun RpcValue.ObjectValue.optionalString(name: String, maxChars: Int = Int.MAX_VALUE): String? {
        val raw = value[name] ?: return null
        val string = (raw as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException(name)
        require(string.length <= maxChars)
        return string
    }

    private fun RpcValue.ObjectValue.requiredLong(name: String, min: Long, max: Long): Long {
        val number = (required(name) as? RpcValue.Number)?.value ?: throw IllegalArgumentException(name)
        require(number.isFinite() && number % 1.0 == 0.0)
        require(number >= min.toDouble() && number <= max.toDouble())
        return number.toLong()
    }

    private fun RpcValue.ObjectValue.optionalLong(name: String, min: Long, max: Long): Long? =
        if (name in value) requiredLong(name, min, max) else null

    private fun RpcValue.ObjectValue.optionalInt(name: String, min: Int, max: Int): Int? =
        optionalLong(name, min.toLong(), max.toLong())?.toInt()

    private fun RpcValue.ObjectValue.optionalBoolean(name: String): Boolean? = when (val raw = value[name]) {
        null -> null
        is RpcValue.Bool -> raw.value
        else -> throw IllegalArgumentException(name)
    }

    private fun RpcValue.ObjectValue.optionalFloat(name: String, min: Float): Float? {
        val number = (value[name] ?: return null) as? RpcValue.Number ?: throw IllegalArgumentException(name)
        require(number.value.isFinite() && number.value >= min.toDouble() && number.value <= Float.MAX_VALUE)
        return number.value.toFloat()
    }

    private fun RpcValue.ObjectValue.requiredObject(name: String): RpcValue.ObjectValue =
        required(name) as? RpcValue.ObjectValue ?: throw IllegalArgumentException(name)

    private fun RpcValue.ObjectValue.optionalDisplayName(name: String): String? {
        val result = optionalString(name)?.trim()
        require(result == null || result.isNotEmpty())
        return result
    }

    private fun RpcValue.ObjectValue.requiredFileName(): String {
        val name = requiredString("suggestedName")
        require(name == name.trim() && name !in setOf(".", ".."))
        require(name.none { it == '/' || it == '\\' || it.isISOControl() })
        return name
    }

    private fun RpcValue.ObjectValue.requiredMimeType(name: String): String =
        requiredString(name).also(::requireMimeType)

    private fun RpcValue.ObjectValue.optionalMimeTypes(): List<String> {
        requireOnly("mimeTypes")
        val raw = value["mimeTypes"] ?: return emptyList()
        val types = (raw as? RpcValue.ArrayValue)?.value ?: throw IllegalArgumentException("mimeTypes")
        return types.map {
            ((it as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException("mimeTypes"))
                .also(::requireMimeType)
        }.distinct()
    }

    private fun requireMimeType(value: String) {
        require(MIME_TYPE.matches(value))
    }

    private fun RpcValue.ObjectValue.toNetworkRequest(): RuntimeNetworkRequest {
        requireOnly("url", "method", "headers", "body", "bodyEncoding", "timeoutMs", "maxResponseBytes")
        val url = requiredString("url", maxResponseBytes)
        require(url == url.trim() && url.none(Char::isISOControl))
        val method = when (optionalString("method", 6) ?: "GET") {
            "GET" -> RuntimeNetworkMethod.GET
            "POST" -> RuntimeNetworkMethod.POST
            "PUT" -> RuntimeNetworkMethod.PUT
            "PATCH" -> RuntimeNetworkMethod.PATCH
            "DELETE" -> RuntimeNetworkMethod.DELETE
            "HEAD" -> RuntimeNetworkMethod.HEAD
            else -> throw IllegalArgumentException("method")
        }
        val headers = (value["headers"] as? RpcValue.ObjectValue)?.value?.mapValues { (name, raw) ->
            require(REQUEST_HEADER_NAME.matches(name))
            require(name.lowercase(Locale.ROOT) !in FORBIDDEN_REQUEST_HEADERS)
            val headerValue = (raw as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException("headers")
            require(headerValue.none { it == '\r' || it == '\n' || it.isISOControl() })
            headerValue
        }.orEmpty()
        val rawBody = value["body"]
        val bodyEncoding = optionalString("bodyEncoding", 8)
        val body = when {
            bodyEncoding == "bytes" -> requiredBytes("body", maxResponseBytes)
            bodyEncoding != null -> throw IllegalArgumentException("bodyEncoding")
            rawBody == null -> null
            rawBody is RpcValue.StringValue -> rawBody.value.toByteArray(StandardCharsets.UTF_8)
            else -> RuntimeRpcJson.encodeValue(rawBody).toByteArray(StandardCharsets.UTF_8)
        }
        body?.let { require(it.size <= maxResponseBytes) }
        require(method !in setOf(RuntimeNetworkMethod.GET, RuntimeNetworkMethod.HEAD) || body == null)
        return RuntimeNetworkRequest(
            url = url,
            method = method,
            headers = headers,
            body = body,
            bodyIsJson = bodyEncoding == null && rawBody != null && rawBody !is RpcValue.StringValue,
            timeoutMillis = optionalLong("timeoutMs", MIN_NETWORK_TIMEOUT_MS, MAX_NETWORK_TIMEOUT_MS),
            maxResponseBytes = optionalLong(
                "maxResponseBytes",
                MIN_NETWORK_RESPONSE_BYTES.toLong(),
                MAX_SAFE_INTEGER,
            ),
        )
    }

    private fun RpcValue.ObjectValue.toLiveNotificationRequest(): RuntimeLiveNotificationRequest {
        requireOnly(
            "sessionId",
            "title",
            "primaryText",
            "secondaryText",
            "body",
            "shortText",
            "updatedAt",
            "progress",
            "accentColor",
            "tone",
        )
        val accentColor = optionalString("accentColor", 7)
        require(accentColor == null || LIVE_NOTIFICATION_COLOR.matches(accentColor))
        val tone = when (optionalString("tone", 8) ?: "neutral") {
            "neutral" -> RuntimeLiveNotificationTone.NEUTRAL
            "positive" -> RuntimeLiveNotificationTone.POSITIVE
            "negative" -> RuntimeLiveNotificationTone.NEGATIVE
            "warning" -> RuntimeLiveNotificationTone.WARNING
            else -> throw IllegalArgumentException("tone")
        }
        return RuntimeLiveNotificationRequest(
            sessionId = requiredSessionId(),
            title = requiredDisplayText("title"),
            primaryText = requiredDisplayText("primaryText"),
            secondaryText = optionalDisplayText("secondaryText"),
            body = optionalDisplayText("body"),
            shortText = optionalDisplayText("shortText"),
            updatedAt = optionalLong("updatedAt", 0, MAX_SAFE_INTEGER),
            progress = optionalInt("progress", 0, 100),
            accentColor = accentColor,
            tone = tone,
        )
    }

    private fun RpcValue.ObjectValue.requiredDisplayText(name: String): String =
        requiredString(name).also { require(it.isNotBlank() && it.none(Char::isISOControl)) }

    private fun RpcValue.ObjectValue.optionalDisplayText(name: String): String? =
        value[name]?.let { requiredText(name).also { text -> require(text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) } }

    private fun RpcValue.ObjectValue.toBackgroundTaskSpec(periodic: Boolean): RuntimeBackgroundTaskSpec {
        val allowed = if (periodic) {
            arrayOf("key", "operation", "constraints", "intervalMinutes")
        } else {
            arrayOf("key", "operation", "constraints")
        }
        requireOnly(*allowed)
        val key = requiredIdentifier("key")
        val operation = requiredObject("operation").toBackgroundOperation()
        val constraints = value["constraints"]?.let {
            (it as? RpcValue.ObjectValue ?: throw IllegalArgumentException("constraints")).toTaskConstraints()
        }
        return RuntimeBackgroundTaskSpec(key, operation, constraints)
    }

    private fun RpcValue.ObjectValue.toBackgroundOperation(): RuntimeBackgroundTaskOperation {
        val type = requiredString("type", 16)
        return when (type) {
            "httpGet" -> {
                requireOnly("type", "url")
                val url = requiredString("url", maxResponseBytes)
                require(url == url.trim() && url.none(Char::isISOControl))
                RuntimeBackgroundTaskOperation.HttpGet(url)
            }
            "notify" -> {
                requireOnly("type", "title", "body")
                RuntimeBackgroundTaskOperation.Notify(
                    title = requiredString("title", maxResponseBytes),
                    body = requiredText("body", maxResponseBytes),
                )
            }
            else -> throw IllegalArgumentException("operation.type")
        }
    }

    private fun RpcValue.ObjectValue.toTaskConstraints(): RuntimeTaskConstraints {
        requireOnly("network")
        val network = optionalString("network", 16)?.let {
            when (it) {
                "none" -> RuntimeNetworkConstraint.NONE
                "connected" -> RuntimeNetworkConstraint.CONNECTED
                else -> throw IllegalArgumentException("constraints.network")
            }
        }
        return RuntimeTaskConstraints(network = network)
    }

    private fun RpcValue.ObjectValue.requiredBytes(name: String, maxBytes: Int): ByteArray = when (val raw = required(name)) {
        is RpcValue.StringValue -> {
            require(raw.value.length <= maxBytes)
            raw.value.toByteArray(StandardCharsets.UTF_8)
        }
        is RpcValue.ArrayValue -> {
            require(raw.value.size <= maxBytes)
            ByteArray(raw.value.size) { index ->
                val number = (raw.value[index] as? RpcValue.Number)?.value ?: throw IllegalArgumentException(name)
                require(number % 1.0 == 0.0 && number in 0.0..255.0)
                number.toInt().toByte()
            }
        }
        else -> throw IllegalArgumentException(name)
    }.also { require(it.size <= maxBytes) }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val SUPPORTED_CONTRACT_PHASES = setOf(ContractPhase.M1, ContractPhase.M2, ContractPhase.M3)
        val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        val REQUEST_HEADER_NAME = HEADER_NAME
        val FORBIDDEN_RESPONSE_HEADERS = setOf("set-cookie", "set-cookie2", "proxy-authenticate")
        val FORBIDDEN_REQUEST_HEADERS = setOf(
            "connection", "content-length", "host", "proxy-authorization", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade",
        )
        val MIME_TYPE = Regex("^[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+*\\-]+$")
        val LIVE_NOTIFICATION_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        private val FOREGROUND_INTERACTION_CAPABILITIES = setOf(
            ToolBoxCapabilityId.CLIPBOARD_WRITE, ToolBoxCapabilityId.CLIPBOARD_READ,
            ToolBoxCapabilityId.SHARE, ToolBoxCapabilityId.BROWSER,
            ToolBoxCapabilityId.FILES_OPEN, ToolBoxCapabilityId.FILES_SAVE,
            ToolBoxCapabilityId.HAPTICS, ToolBoxCapabilityId.SHORTCUTS, ToolBoxCapabilityId.CAMERA,
        )
        const val MIN_LOCATION_TIMEOUT_MS = 0L
        const val DEFAULT_LOCATION_TIMEOUT_MS = 0L
        const val MIN_NETWORK_RESPONSE_BYTES = 1
        const val MIN_NETWORK_TIMEOUT_MS = 0L
        // OkHttp represents timeout milliseconds as a non-negative Int.
        const val MAX_NETWORK_TIMEOUT_MS = Int.MAX_VALUE.toLong()
        const val MIN_PERIODIC_INTERVAL_MINUTES = 15L
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}

internal fun isSafeRuntimeRequestId(id: String): Boolean =
    id.isNotEmpty() && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

internal fun runtimeFileReadRawBudget(requestId: String, maxResponseBytes: Int): Int {
    require(isSafeRuntimeRequestId(requestId))
    require(maxResponseBytes >= 0)
    val availableBase64Bytes = maxResponseBytes - FILE_READ_RESPONSE_FIXED_BYTES - requestId.length
    return availableBase64Bytes.coerceAtLeast(0) / 4 * 3
}

internal fun runtimeFileReadEncodedUpperBound(requestId: String, rawBytes: Int): Int {
    require(isSafeRuntimeRequestId(requestId))
    require(rawBytes >= 0)
    val base64Bytes = 4L * ((rawBytes.toLong() + 2) / 3)
    return (FILE_READ_RESPONSE_FIXED_BYTES + requestId.length + base64Bytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private const val FILE_READ_RESPONSE_FIXED_BYTES = 42
