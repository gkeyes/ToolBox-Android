package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxApiV1
import io.toolbox.tool.api.ToolBoxCapabilityId
import java.net.URI
import java.util.Locale
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
    BUSY,
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
)

internal fun RuntimeRpcError.toRpcValue(): RpcValue.ObjectValue = RpcValue.ObjectValue(buildMap {
    put("code", RpcValue.StringValue(code.name))
    put("message", RpcValue.StringValue(message))
})

sealed interface RuntimeRpcResponse {
    val id: String

    data class Success(override val id: String, val result: RpcValue, val release: () -> Unit = {}) : RuntimeRpcResponse {
        // Prepared only for this response; data-class copies deliberately re-encode.
        internal var preparedEncoding: EncodedRuntimeResponse? = null
    }
    data class Failure(override val id: String, val error: RuntimeRpcError) : RuntimeRpcResponse
}

sealed interface RuntimePolicyDecision {
    data object Allowed : RuntimePolicyDecision
    data class Denied(val code: RuntimeRpcErrorCode, val message: String) : RuntimePolicyDecision
}

interface RuntimeAuthorizationPolicy {
    /** Each read checks one fact; the dispatcher repeats the stage after suspend points. */
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

/** In-app features: toast, storage, device info, haptics, clipboard write. */
data class RuntimeCoreHandlers(
    val toast: RuntimeToastHandler? = null,
    val storage: RuntimeStorageHandler? = null,
    val secureStorage: RuntimeStorageHandler? = null,
    val deviceBasic: RuntimeDeviceBasicHandler? = null,
    val haptics: RuntimeHapticsHandler? = null,
    val clipboardWrite: RuntimeClipboardWriteHandler? = null,
)

/** Network, notifications, background tasks/runtime and alarms. */
data class RuntimeServiceHandlers(
    val network: RuntimeNetworkHandler? = null,
    val notifications: RuntimeNotificationHandler? = null,
    val background: RuntimeBackgroundTaskHandler? = null,
    val continuousBackground: RuntimeContinuousBackgroundHandler? = null,
    val alarms: RuntimeAlarmHandler? = null,
)

/** Android system integrations: clipboard read, share, browser, files, shortcuts, camera, location. */
data class RuntimeSystemHandlers(
    val clipboardRead: RuntimeClipboardReadHandler? = null,
    val shareText: RuntimeShareTextHandler? = null,
    val browserOpen: RuntimeBrowserOpenHandler? = null,
    val files: RuntimeFilesHandler? = null,
    val shortcuts: RuntimeShortcutHandler? = null,
    val camera: RuntimeCameraHandler? = null,
    val location: RuntimeLocationHandler? = null,
    val locationWatch: RuntimeLocationWatchHandler? = null,
    val sessionCleanup: RuntimeSessionCleanupHandler? = null,
)

class RuntimeRpcDispatcher(
    internal val identity: RuntimeSessionIdentity,
    internal val authorization: RuntimeAuthorizationPolicy,
    internal val handlers: RuntimeCoreHandlers,
    internal val serviceHandlers: RuntimeServiceHandlers = RuntimeServiceHandlers(),
    internal val systemHandlers: RuntimeSystemHandlers = RuntimeSystemHandlers(),
    private val lifecycle: RuntimeLifecycleHandler? = null,
    internal val foregroundInteractionGuard: suspend () -> Unit = {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "No foreground tool session is available")
    },
    internal val browserLaunchGuard: suspend () -> Unit = {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "No foreground tool session is available")
    },
) {
    suspend fun dispatch(
        request: RuntimeRpcRequest,
        inbound: RuntimeInboundContext,
        beforeInvoke: (String) -> Unit = {},
    ): RuntimeRpcResponse {
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
            serviceHandlers.network?.cancelStreams()
            return failure(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
        }
        val method = ToolBoxApiV1.method(request.method)
            ?: return failure(RuntimeRpcErrorCode.UNSUPPORTED, "Unknown ToolBox method")
        // Cleanup remains callable after network access is revoked.
        val mediaCleanup = method.name == "network.closeMedia"
        val capability = try {
            lifecycle?.checkMethodAvailable(method.name)
            if (method.name == "files.read") {
                requireHandler(systemHandlers.files).capabilityFor(
                    request.params.requiredIdentifier("token"),
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
            if (!mediaCleanup && !authorization.isGranted(identity, capability)) {
                if (capability == ToolBoxCapabilityId.NETWORK) serviceHandlers.network?.cancelStreams()
                return failure(RuntimeRpcErrorCode.PERMISSION_DENIED, "Capability is disabled for this tool")
            }
            if (!mediaCleanup && !authorization.hasSystemPermissions(identity, descriptor.systemPermissions)) {
                if (capability == ToolBoxCapabilityId.NETWORK) serviceHandlers.network?.cancelStreams()
                return failure(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "Required Android permission is unavailable")
            }

        }
        when (val decision = authorization.admit(identity, method, request.encodedBytes)) {
            RuntimePolicyDecision.Allowed -> Unit
            is RuntimePolicyDecision.Denied -> return failure(decision.code, decision.message)
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
            // Read a fresh authorization stage after admission and the foreground dispatch.
            // The final version read also detects replacement during grant repository IO.
            if (!authorization.isCurrent(identity)) {
                serviceHandlers.network?.cancelStreams()
                return failure(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
            }
            if (capability != null && !mediaCleanup) {
                val descriptor = ToolBoxApiV1.capability(capability)
                if (!authorization.isGranted(identity, capability)) {
                    if (capability == ToolBoxCapabilityId.NETWORK) serviceHandlers.network?.cancelStreams()
                    return failure(RuntimeRpcErrorCode.PERMISSION_DENIED, "Capability was disabled before execution")
                }
                if (!authorization.hasSystemPermissions(identity, descriptor.systemPermissions)) {
                    if (capability == ToolBoxCapabilityId.NETWORK) serviceHandlers.network?.cancelStreams()
                    return failure(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "Android permission changed before execution")
                }
                if (!authorization.isCurrent(identity)) {
                    serviceHandlers.network?.cancelStreams()
                    return failure(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
                }
            }
            lifecycle?.checkMethodAvailable(method.name)
            currentCoroutineContext().ensureActive()
            beforeInvoke(method.name)
            var preparedEncoding: EncodedRuntimeResponse? = null
            val result = invoke(method.name, request.params, request.id, retained) { preparedEncoding = it }
            RuntimeRpcResponse.Success(request.id, result, release).also {
                it.preparedEncoding = preparedEncoding
                transferred = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeHandlerException) {
            failure(failure.errorCode, failure.message)
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
        onEncodedResponse: (EncodedRuntimeResponse) -> Unit,
    ): RpcValue = when (method.substringBefore('.')) {
        "ready", "runtime" -> invokeRuntime(method, params)
        "ui", "crypto", "device", "haptics" -> invokeCore(method, params)
        "storage" -> invokeStorage(method, params)
        "network" -> invokeNetwork(method, params, requestId, retained, onEncodedResponse)
        "notifications" -> invokeNotifications(method, params)
        "background" -> invokeBackground(method, params)
        "alarms" -> invokeAlarms(method, params)
        "clipboard", "share", "browser", "shortcuts", "camera" -> invokeSystem(method, params)
        "files" -> invokeFiles(method, params, requestId)
        "location" -> invokeLocation(method, params)
        else -> noNativeHandler()
    }

    private suspend fun invokeRuntime(method: String, params: RpcValue.ObjectValue): RpcValue = when (method) {
        "ready" -> RpcValue.ObjectValue(
            mapOf(
                "apiVersion" to RpcValue.StringValue(ToolBoxApiV1.API_VERSION),
                "hostVersion" to RpcValue.StringValue(identity.hostVersion),
                "toolId" to RpcValue.StringValue(identity.toolId),
                "generation" to RpcValue.StringValue(identity.generation),
                "runtimeState" to (lifecycle?.stateValue() ?: RpcValue.Null),
            ),
        )
        "runtime.getState" -> requireHandler(lifecycle).stateValue()
        "runtime.ackEvents" -> {
            params.requireOnly("sequence")
            requireHandler(lifecycle).acknowledgeEvents(params.requiredLong("sequence", 0, MAX_SAFE_INTEGER))
            RpcValue.Null
        }
        "runtime.flushComplete" -> {
            params.requireOnly("closeToken", "saved")
            requireHandler(lifecycle).completeFlush(
                params.requiredString("closeToken", 64),
                params.optionalBoolean("saved") ?: throw IllegalArgumentException("saved"),
            )
            RpcValue.Null
        }
        else -> noNativeHandler()
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

    private companion object {
        private val FOREGROUND_INTERACTION_CAPABILITIES = setOf(
            ToolBoxCapabilityId.CLIPBOARD_WRITE, ToolBoxCapabilityId.CLIPBOARD_READ,
            ToolBoxCapabilityId.SHARE, ToolBoxCapabilityId.BROWSER,
            ToolBoxCapabilityId.FILES_OPEN, ToolBoxCapabilityId.FILES_SAVE,
            ToolBoxCapabilityId.HAPTICS, ToolBoxCapabilityId.SHORTCUTS, ToolBoxCapabilityId.CAMERA,
        )
    }
}

/** Decodes a `{ base64 }` byte wrapper, or returns null when the value is not one. */
internal fun decodeRuntimeBase64Bytes(value: RpcValue.ObjectValue, maxBytes: Int): ByteArray? {
    if (value.value.keys != setOf("base64")) return null
    val text = (value.value["base64"] as? RpcValue.StringValue)?.value ?: return null
    // Check the decoded size before allocating it.
    if (text.length / 4L * 3L > maxBytes.toLong() + 2L) return null
    return try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun isSafeRuntimeRequestId(id: String): Boolean =
    id.isNotEmpty() && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
