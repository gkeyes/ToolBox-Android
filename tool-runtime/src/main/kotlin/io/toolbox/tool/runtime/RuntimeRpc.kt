package io.toolbox.tool.runtime

import io.toolbox.tool.api.ContractPhase
import io.toolbox.tool.api.GestureRequirement
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxApiV1
import io.toolbox.tool.api.ToolBoxCapabilityId
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    val recentTouchAgeMillis: Long?,
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
    INTERNAL_ERROR,
}

data class RuntimeRpcError(
    val code: RuntimeRpcErrorCode,
    val message: String,
)

sealed interface RuntimeRpcResponse {
    val id: String

    data class Success(override val id: String, val result: RpcValue) : RuntimeRpcResponse
    data class Failure(override val id: String, val error: RuntimeRpcError) : RuntimeRpcResponse
}

sealed interface RuntimePolicyDecision {
    data object Allowed : RuntimePolicyDecision
    data class Denied(val code: RuntimeRpcErrorCode, val message: String) : RuntimePolicyDecision
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
) : Exception(message)

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
}

enum class RuntimeNetworkMethod { GET, POST }

data class RuntimeNetworkRequest(
    val url: String,
    val method: RuntimeNetworkMethod,
    val body: String?,
    val contentType: String?,
)

data class RuntimeNetworkResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

interface RuntimeNotificationHandler {
    suspend fun post(notificationId: String, title: String, body: String)
    suspend fun cancel(notificationId: String)
}

sealed interface RuntimeBackgroundTaskOperation {
    data class HttpGet(val url: String) : RuntimeBackgroundTaskOperation
    data class Notify(val title: String, val body: String) : RuntimeBackgroundTaskOperation
}

data class RuntimeTaskConstraints(
    val network: RuntimeNetworkConstraint?,
    val requiresCharging: Boolean?,
    val batteryNotLow: Boolean?,
)

enum class RuntimeNetworkConstraint { NONE, CONNECTED }

data class RuntimeBackgroundTaskSpec(
    val key: String,
    val operation: RuntimeBackgroundTaskOperation,
    val earliestAt: Long?,
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
)

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

data class RuntimeM2Handlers(
    val network: RuntimeNetworkHandler? = null,
    val notifications: RuntimeNotificationHandler? = null,
    val background: RuntimeBackgroundTaskHandler? = null,
)

fun interface RuntimeClipboardReadHandler {
    suspend fun readText(): String
}

fun interface RuntimeShareTextHandler {
    suspend fun shareText(text: String)
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

data class RuntimeM3Handlers(
    val clipboardRead: RuntimeClipboardReadHandler? = null,
    val shareText: RuntimeShareTextHandler? = null,
    val files: RuntimeFilesHandler? = null,
    val shortcuts: RuntimeShortcutHandler? = null,
    val camera: RuntimeCameraHandler? = null,
    val location: RuntimeLocationHandler? = null,
    val sessionCleanup: RuntimeSessionCleanupHandler? = null,
)

class RuntimeRpcDispatcher(
    private val identity: RuntimeSessionIdentity,
    private val authorization: RuntimeAuthorizationPolicy,
    private val handlers: RuntimeM1Handlers,
    private val m2Handlers: RuntimeM2Handlers = RuntimeM2Handlers(),
    private val m3Handlers: RuntimeM3Handlers = RuntimeM3Handlers(),
    private val recentGestureWindowMillis: Long = 5_000L,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) {
    init {
        require(maxResponseBytes >= MIN_RESPONSE_BYTES)
    }

    suspend fun dispatch(request: RuntimeRpcRequest, inbound: RuntimeInboundContext): RuntimeRpcResponse {
        fun failure(code: RuntimeRpcErrorCode, message: String) =
            RuntimeRpcResponse.Failure(request.id, RuntimeRpcError(code, message))

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
                    request.params.requiredIdentifier("token", MAX_FILE_TOKEN_CHARS),
                )
            } else {
                method.capability
            }
        } catch (failure: RuntimeHandlerException) {
            return failure(failure.errorCode, failure.message)
        } catch (_: IllegalArgumentException) {
            return failure(RuntimeRpcErrorCode.INVALID_REQUEST, "Invalid parameters for ${method.name}")
        }
        if (capability != null) {
            val descriptor = ToolBoxApiV1.capability(capability)
            if (descriptor.wireName !in identity.declaredCapabilities) {
                return failure(RuntimeRpcErrorCode.NOT_DECLARED, "Capability is not declared by this tool")
            }
            if (!authorization.isGranted(identity, capability)) {
                return failure(RuntimeRpcErrorCode.PERMISSION_DENIED, "Capability is disabled for this tool")
            }
            if (!authorization.hasSystemPermissions(identity, descriptor.systemPermissions)) {
                return failure(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "Required Android permission is unavailable")
            }
            if (
                method.name != "files.read" &&
                descriptor.gestureRequirement != GestureRequirement.NONE &&
                (inbound.recentTouchAgeMillis == null || inbound.recentTouchAgeMillis !in 0..recentGestureWindowMillis)
            ) {
                return failure(RuntimeRpcErrorCode.USER_GESTURE_REQUIRED, "A recent real touch is required")
            }
        }
        when (val decision = authorization.admit(identity, method, request.encodedBytes)) {
            RuntimePolicyDecision.Allowed -> Unit
            is RuntimePolicyDecision.Denied -> return failure(decision.code, decision.message)
        }
        if (!authorization.isCurrent(identity)) {
            return failure(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
        }
        if (capability != null) {
            val descriptor = ToolBoxApiV1.capability(capability)
            if (!authorization.isGranted(identity, capability)) {
                return failure(RuntimeRpcErrorCode.PERMISSION_DENIED, "Capability was disabled before execution")
            }
            if (!authorization.hasSystemPermissions(identity, descriptor.systemPermissions)) {
                return failure(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "Android permission changed before execution")
            }
        }
        return try {
            RuntimeRpcResponse.Success(request.id, invoke(method.name, request.params, request.id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeHandlerException) {
            failure(failure.errorCode, failure.message)
        } catch (_: IllegalArgumentException) {
            failure(RuntimeRpcErrorCode.INVALID_REQUEST, "Invalid parameters for ${method.name}")
        } catch (_: Exception) {
            failure(RuntimeRpcErrorCode.INTERNAL_ERROR, "The native operation failed")
        }
    }

    private suspend fun invoke(
        method: String,
        params: RpcValue.ObjectValue,
        requestId: String,
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
            requireHandler(handlers.toast).show(params.requiredString("message", MAX_TOAST_CHARS))
            RpcValue.Null
        }
        "crypto.sha256" -> RpcValue.ObjectValue(
            mapOf("hex" to RpcValue.StringValue(sha256(params.requiredBytes("value", MAX_HASH_BYTES)))),
        )
        "storage.get" -> requireHandler(handlers.storage).get(params.requiredKey()) ?: RpcValue.Null
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
            requireHandler(handlers.clipboardWrite).writeText(params.requiredString("text", MAX_CLIPBOARD_CHARS))
            RpcValue.Null
        }
        "network.request" -> requireHandler(m2Handlers.network)
            .request(params.toNetworkRequest())
            .toRpcValue()
        "notifications.post" -> {
            params.requireOnly("id", "title", "body")
            requireHandler(m2Handlers.notifications).post(
                notificationId = params.requiredIdentifier("id", MAX_NOTIFICATION_ID_CHARS),
                title = params.requiredString("title", MAX_NOTIFICATION_TITLE_CHARS),
                body = params.requiredString("body", MAX_NOTIFICATION_BODY_CHARS),
            )
            RpcValue.Null
        }
        "notifications.cancel" -> {
            params.requireOnly("id")
            requireHandler(m2Handlers.notifications).cancel(
                params.requiredIdentifier("id", MAX_NOTIFICATION_ID_CHARS),
            )
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
        "background.list" -> {
            params.requireOnly()
            RpcValue.ArrayValue(requireHandler(m2Handlers.background).list().map { task -> task.toRpcValue() })
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
            val cancelled = requireHandler(m2Handlers.background).cancel(params.requiredTaskId())
            if (!cancelled) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Background task was not found")
            }
            RpcValue.Null
        }
        "clipboard.readText" -> {
            params.requireOnly()
            RpcValue.StringValue(requireHandler(m3Handlers.clipboardRead).readText())
        }
        "share.text" -> {
            params.requireOnly("text")
            requireHandler(m3Handlers.shareText).shareText(params.requiredString("text", MAX_SHARE_TEXT_CHARS))
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
                content = params.requiredBytes("content", MAX_FILE_CONTENT_BYTES),
            )?.toRpcValue() ?: RpcValue.Null
        }
        "files.read" -> {
            params.requireOnly("token")
            val content = requireHandler(m3Handlers.files).consume(
                params.requiredIdentifier("token", MAX_FILE_TOKEN_CHARS),
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
                timeoutMillis = params.optionalLong("timeoutMs", MIN_LOCATION_TIMEOUT_MS, MAX_LOCATION_TIMEOUT_MS)
                    ?: DEFAULT_LOCATION_TIMEOUT_MS,
            ).toRpcValue()
        }
        else -> throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Method has no native handler")
    }

    private fun <T> requireHandler(handler: T?): T = handler ?: throw RuntimeHandlerException(
        RuntimeRpcErrorCode.UNSUPPORTED,
        "This native capability is unavailable",
    )

    private fun RuntimeBasicDeviceInfo.toRpcValue(): RpcValue.ObjectValue {
        require(apiLevel >= 33)
        require(locale.length in 2..64)
        require(timeZone.length in 1..64)
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
        require(body.toByteArray(StandardCharsets.UTF_8).size <= MAX_NETWORK_RESPONSE_BYTES)
        require(headers.size <= MAX_NETWORK_RESPONSE_HEADERS)
        val rpcHeaders = headers.entries.associate { (name, value) ->
            require(HEADER_NAME.matches(name))
            require(name.lowercase(Locale.ROOT) !in FORBIDDEN_RESPONSE_HEADERS)
            require(value.length <= MAX_NETWORK_HEADER_VALUE_CHARS)
            name to RpcValue.StringValue(value)
        }
        return RpcValue.ObjectValue(
            mapOf(
                "status" to RpcValue.Number(status.toDouble()),
                "headers" to RpcValue.ObjectValue(rpcHeaders),
                "body" to RpcValue.StringValue(body),
            ),
        )
    }

    private fun RuntimeBackgroundTaskSummary.toRpcValue(): RpcValue.ObjectValue {
        requireTaskId(taskId)
        requireIdentifier(key, MAX_TASK_KEY_CHARS)
        nextRunAt?.let { require(it in 0..MAX_SAFE_INTEGER) }
        return RpcValue.ObjectValue(buildMap {
            put("taskId", RpcValue.StringValue(taskId))
            put("key", RpcValue.StringValue(key))
            put("state", RpcValue.StringValue(state.name))
            put("periodic", RpcValue.Bool(periodic))
            nextRunAt?.let { put("nextRunAt", RpcValue.Number(it.toDouble())) }
        })
    }

    private fun RuntimeBackgroundTaskRunResult.toRpcValue(): RpcValue.ObjectValue {
        requireTaskId(taskId)
        require(completedAt in 0..MAX_SAFE_INTEGER)
        status?.let { require(it in 100..599) }
        body?.let { require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_BACKGROUND_RESULT_BYTES) }
        error?.let {
            require(it.message.length in 1..MAX_ERROR_MESSAGE_CHARS)
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
                    RpcValue.ObjectValue(
                        mapOf(
                            "code" to RpcValue.StringValue(it.code.name),
                            "message" to RpcValue.StringValue(it.message),
                        ),
                    ),
                )
            }
        })
    }

    private fun RuntimeFileToken.toRpcValue(): RpcValue.ObjectValue {
        requireIdentifier(token, MAX_FILE_TOKEN_CHARS)
        require(name.isNotBlank() && name.length <= MAX_FILE_NAME_CHARS && name.none(Char::isISOControl))
        requireMimeType(mimeType)
        require(size in 0..MAX_FILE_TOKEN_SIZE)
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

    private fun RpcValue.ObjectValue.requiredString(name: String, maxChars: Int): String {
        val result = (required(name) as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException(name)
        require(result.length in 1..maxChars)
        return result
    }

    private fun RpcValue.ObjectValue.requiredKey(): String {
        val key = requiredString("key", MAX_KEY_CHARS)
        require(!key.any(Char::isISOControl))
        return key
    }

    private fun RpcValue.ObjectValue.requiredIdentifier(name: String, maxChars: Int): String {
        val identifier = requiredString(name, maxChars)
        require(identifier == identifier.trim())
        require(identifier.none(Char::isISOControl))
        return identifier
    }

    private fun RpcValue.ObjectValue.requiredTaskId(): String =
        requiredIdentifier("taskId", MAX_TASK_ID_CHARS)

    private fun requireTaskId(taskId: String) {
        requireIdentifier(taskId, MAX_TASK_ID_CHARS)
    }

    private fun requireIdentifier(value: String, maxChars: Int) {
        require(value.length in 1..maxChars)
        require(value == value.trim())
        require(value.none(Char::isISOControl))
    }

    private fun RpcValue.ObjectValue.optionalString(name: String, maxChars: Int): String? {
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

    private fun RpcValue.ObjectValue.optionalBoolean(name: String): Boolean? = when (val raw = value[name]) {
        null -> null
        is RpcValue.Bool -> raw.value
        else -> throw IllegalArgumentException(name)
    }

    private fun RpcValue.ObjectValue.requiredObject(name: String): RpcValue.ObjectValue =
        required(name) as? RpcValue.ObjectValue ?: throw IllegalArgumentException(name)

    private fun RpcValue.ObjectValue.optionalDisplayName(name: String): String? {
        val result = optionalString(name, MAX_SHORTCUT_NAME_CHARS)?.trim()
        require(result == null || result.isNotEmpty())
        return result
    }

    private fun RpcValue.ObjectValue.requiredFileName(): String {
        val name = requiredString("suggestedName", MAX_FILE_NAME_CHARS)
        require(name == name.trim() && name !in setOf(".", ".."))
        require(name.none { it == '/' || it == '\\' || it.isISOControl() })
        return name
    }

    private fun RpcValue.ObjectValue.requiredMimeType(name: String): String =
        requiredString(name, MAX_MIME_TYPE_CHARS).also(::requireMimeType)

    private fun RpcValue.ObjectValue.optionalMimeTypes(): List<String> {
        requireOnly("mimeTypes")
        val raw = value["mimeTypes"] ?: return emptyList()
        val types = (raw as? RpcValue.ArrayValue)?.value ?: throw IllegalArgumentException("mimeTypes")
        require(types.size <= MAX_MIME_TYPES)
        return types.map {
            ((it as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException("mimeTypes"))
                .also(::requireMimeType)
        }.distinct()
    }

    private fun requireMimeType(value: String) {
        require(value.length in 3..MAX_MIME_TYPE_CHARS && MIME_TYPE.matches(value))
    }

    private fun RpcValue.ObjectValue.toNetworkRequest(): RuntimeNetworkRequest {
        requireOnly("url", "method", "body", "contentType")
        val url = requiredString("url", MAX_NETWORK_URL_CHARS)
        require(url == url.trim() && url.none(Char::isISOControl))
        val method = when (optionalString("method", 4) ?: "GET") {
            "GET" -> RuntimeNetworkMethod.GET
            "POST" -> RuntimeNetworkMethod.POST
            else -> throw IllegalArgumentException("method")
        }
        val body = optionalString("body", MAX_NETWORK_REQUEST_BODY_CHARS)
        body?.let { require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_NETWORK_REQUEST_BODY_BYTES) }
        val contentType = optionalString("contentType", MAX_CONTENT_TYPE_CHARS)
        contentType?.let { require(it in NETWORK_CONTENT_TYPES) }
        if (method == RuntimeNetworkMethod.GET) require(body == null && contentType == null)
        if (contentType != null) require(body != null)
        return RuntimeNetworkRequest(url, method, body, contentType)
    }

    private fun RpcValue.ObjectValue.toBackgroundTaskSpec(periodic: Boolean): RuntimeBackgroundTaskSpec {
        val allowed = if (periodic) {
            arrayOf("key", "operation", "earliestAt", "constraints", "intervalMinutes")
        } else {
            arrayOf("key", "operation", "earliestAt", "constraints")
        }
        requireOnly(*allowed)
        val key = requiredIdentifier("key", MAX_TASK_KEY_CHARS)
        val operation = requiredObject("operation").toBackgroundOperation()
        val earliestAt = optionalLong("earliestAt", 0L, MAX_SAFE_INTEGER)
        val constraints = value["constraints"]?.let {
            (it as? RpcValue.ObjectValue ?: throw IllegalArgumentException("constraints")).toTaskConstraints()
        }
        return RuntimeBackgroundTaskSpec(key, operation, earliestAt, constraints)
    }

    private fun RpcValue.ObjectValue.toBackgroundOperation(): RuntimeBackgroundTaskOperation {
        val type = requiredString("type", 16)
        return when (type) {
            "httpGet" -> {
                requireOnly("type", "url")
                val url = requiredString("url", MAX_NETWORK_URL_CHARS)
                require(url == url.trim() && url.none(Char::isISOControl))
                RuntimeBackgroundTaskOperation.HttpGet(url)
            }
            "notify" -> {
                requireOnly("type", "title", "body")
                RuntimeBackgroundTaskOperation.Notify(
                    title = requiredString("title", MAX_NOTIFICATION_TITLE_CHARS),
                    body = requiredString("body", MAX_NOTIFICATION_BODY_CHARS),
                )
            }
            else -> throw IllegalArgumentException("operation.type")
        }
    }

    private fun RpcValue.ObjectValue.toTaskConstraints(): RuntimeTaskConstraints {
        requireOnly("network", "requiresCharging", "batteryNotLow")
        val network = optionalString("network", 16)?.let {
            when (it) {
                "none" -> RuntimeNetworkConstraint.NONE
                "connected" -> RuntimeNetworkConstraint.CONNECTED
                else -> throw IllegalArgumentException("constraints.network")
            }
        }
        return RuntimeTaskConstraints(
            network = network,
            requiresCharging = optionalBoolean("requiresCharging"),
            batteryNotLow = optionalBoolean("batteryNotLow"),
        )
    }

    private fun RpcValue.ObjectValue.requiredBytes(name: String, maxBytes: Int): ByteArray = when (val raw = required(name)) {
        is RpcValue.StringValue -> raw.value.toByteArray(StandardCharsets.UTF_8)
        is RpcValue.ArrayValue -> raw.value.map {
            val number = (it as? RpcValue.Number)?.value ?: throw IllegalArgumentException(name)
            require(number % 1.0 == 0.0 && number in 0.0..255.0)
            number.toInt().toByte()
        }.toByteArray()
        else -> throw IllegalArgumentException(name)
    }.also { require(it.size <= maxBytes) }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val SUPPORTED_CONTRACT_PHASES = setOf(ContractPhase.M1, ContractPhase.M2, ContractPhase.M3)
        val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$")
        val FORBIDDEN_RESPONSE_HEADERS = setOf("set-cookie", "set-cookie2", "proxy-authenticate")
        val NETWORK_CONTENT_TYPES = setOf("text/plain", "application/json")
        val MIME_TYPE = Regex("^[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+*\\-]+$")
        const val MAX_KEY_CHARS = 128
        const val MAX_TOAST_CHARS = 200
        const val MAX_HASH_BYTES = 1024 * 1024
        const val MAX_CLIPBOARD_CHARS = 64 * 1024
        const val MAX_SHARE_TEXT_CHARS = 64 * 1024
        const val MAX_FILE_CONTENT_BYTES = 1024 * 1024
        const val DEFAULT_MAX_RESPONSE_BYTES = 256 * 1024
        const val MIN_RESPONSE_BYTES = 4 * 1024
        const val MAX_FILE_TOKEN_SIZE = 1024L * 1024L * 1024L
        const val MAX_FILE_TOKEN_CHARS = 128
        const val MAX_FILE_NAME_CHARS = 255
        const val MAX_MIME_TYPE_CHARS = 127
        const val MAX_MIME_TYPES = 16
        const val MAX_SHORTCUT_NAME_CHARS = 64
        const val MIN_LOCATION_TIMEOUT_MS = 1_000L
        const val DEFAULT_LOCATION_TIMEOUT_MS = 10_000L
        const val MAX_LOCATION_TIMEOUT_MS = 30_000L
        const val MAX_NETWORK_URL_CHARS = 2_048
        const val MAX_NETWORK_REQUEST_BODY_CHARS = 64 * 1024
        const val MAX_NETWORK_REQUEST_BODY_BYTES = 64 * 1024
        const val MAX_NETWORK_RESPONSE_BYTES = 1_024 * 1_024
        const val MAX_NETWORK_RESPONSE_HEADERS = 64
        const val MAX_NETWORK_HEADER_VALUE_CHARS = 4_096
        const val MAX_CONTENT_TYPE_CHARS = 32
        const val MAX_NOTIFICATION_ID_CHARS = 64
        const val MAX_NOTIFICATION_TITLE_CHARS = 64
        const val MAX_NOTIFICATION_BODY_CHARS = 256
        const val MAX_TASK_ID_CHARS = 128
        const val MAX_TASK_KEY_CHARS = 64
        const val MAX_BACKGROUND_RESULT_BYTES = 256 * 1024
        const val MAX_ERROR_MESSAGE_CHARS = 256
        const val MIN_PERIODIC_INTERVAL_MINUTES = 15L
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}

internal fun isSafeRuntimeRequestId(id: String): Boolean =
    id.length in 1..128 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

internal fun runtimeFileReadRawBudget(requestId: String, maxResponseBytes: Int): Int {
    require(isSafeRuntimeRequestId(requestId))
    require(maxResponseBytes >= 4 * 1024)
    val availableBase64Bytes = maxResponseBytes - FILE_READ_RESPONSE_FIXED_BYTES - requestId.length
    return availableBase64Bytes.coerceAtLeast(4) / 4 * 3
}

internal fun runtimeFileReadEncodedUpperBound(requestId: String, rawBytes: Int): Int {
    require(isSafeRuntimeRequestId(requestId))
    require(rawBytes >= 0)
    val base64Bytes = 4 * ((rawBytes + 2) / 3)
    return FILE_READ_RESPONSE_FIXED_BYTES + requestId.length + base64Bytes
}

private const val FILE_READ_RESPONSE_FIXED_BYTES = 42

internal class RuntimeSessionJobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun launch(block: suspend () -> Unit) = scope.launch { block() }

    fun close() {
        scope.cancel(CancellationException("ToolBox runtime session ended"))
    }
}
