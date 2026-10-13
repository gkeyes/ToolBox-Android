package io.toolbox.tool.runtime

import java.util.Locale

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
)

data class RuntimeBackgroundTaskRunResult(
    val taskId: String,
    val outcome: RuntimeBackgroundRunOutcome,
    val completedAt: Long,
    val status: Int?,
    val body: String?,
    val error: RuntimeBackgroundTaskError?,
    /** How [body] is encoded; null for results stored before the host recorded it. */
    val bodyEncoding: RuntimeNetworkBodyEncoding? = null,
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

data class RuntimeBackgroundActivityRequest(
    val sessionId: String,
    val text: String,
    val detail: String?,
    val updatedAt: Long?,
)

interface RuntimeContinuousBackgroundHandler {
    suspend fun start(options: RuntimeBackgroundStartOptions): RuntimeBackgroundSessionSummary
    suspend fun stop(sessionId: String): Boolean
    suspend fun status(sessionId: String): RuntimeBackgroundSessionSummary?
    suspend fun list(): List<RuntimeBackgroundSessionSummary>
    suspend fun updateActivity(request: RuntimeBackgroundActivityRequest)
    suspend fun setTimer(key: String, intervalMillis: Long)
    suspend fun cancelTimer(key: String): Boolean
}

internal suspend fun RuntimeRpcDispatcher.invokeBackground(
    method: String,
    params: RpcValue.ObjectValue,
): RpcValue = when (method) {
    "background.enqueue" -> RpcValue.StringValue(
        requireHandler(serviceHandlers.background).enqueue(params.toBackgroundTaskSpec(periodic = false))
            .also { requireTaskId(it) },
    )
    "background.schedulePeriodic" -> {
        val spec = params.toBackgroundTaskSpec(periodic = true)
        val intervalMinutes = params.requiredLong("intervalMinutes", MIN_PERIODIC_INTERVAL_MINUTES, MAX_SAFE_INTEGER)
        RpcValue.StringValue(
            requireHandler(serviceHandlers.background).schedulePeriodic(spec, intervalMinutes)
                .also { requireTaskId(it) },
        )
    }
    "background.start" -> {
        params.requireOnly("restoreAfterProcessDeath", "restoreAfterReboot")
        requireHandler(serviceHandlers.continuousBackground).start(
            RuntimeBackgroundStartOptions(
                restoreAfterProcessDeath = params.optionalBoolean("restoreAfterProcessDeath") ?: true,
                restoreAfterReboot = params.optionalBoolean("restoreAfterReboot") ?: false,
            ),
        ).toRpcValue()
    }
    "background.stop" -> {
        params.requireOnly("sessionId")
        if (!requireHandler(serviceHandlers.continuousBackground).stop(params.requiredSessionId())) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Background session was not found")
        }
        RpcValue.Null
    }
    "background.status" -> {
        params.requireOnly("sessionId")
        requireHandler(serviceHandlers.continuousBackground)
            .status(params.requiredSessionId())
            ?.toRpcValue()
            ?: RpcValue.Null
    }
    "background.list" -> {
        params.requireOnly()
        RpcValue.ArrayValue(requireHandler(serviceHandlers.background).list().map { task -> task.toRpcValue() })
    }
    "background.listSessions" -> {
        params.requireOnly()
        RpcValue.ArrayValue(
            requireHandler(serviceHandlers.continuousBackground).list().map { session -> session.toRpcValue() },
        )
    }
    "background.updateActivity" -> {
        requireHandler(serviceHandlers.continuousBackground).updateActivity(params.toBackgroundActivityRequest())
        RpcValue.Null
    }
    "background.getResult" -> {
        params.requireOnly("taskId")
        requireHandler(serviceHandlers.background)
            .getResult(params.requiredTaskId())
            ?.toRpcValue()
            ?: RpcValue.Null
    }
    "background.cancel" -> {
        params.requireOnly("taskId")
        val taskId = params.requiredTaskId()
        val cancelled = requireHandler(serviceHandlers.background).cancel(taskId)
        if (!cancelled) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Background task was not found")
        }
        RpcValue.Null
    }
    "background.setTimer" -> {
        params.requireOnly("key", "intervalMs")
        requireHandler(serviceHandlers.continuousBackground).setTimer(
            key = params.requiredIdentifier("key"),
            intervalMillis = params.requiredLong("intervalMs", 1, MAX_SAFE_INTEGER),
        )
        RpcValue.Null
    }
    "background.cancelTimer" -> {
        params.requireOnly("key")
        val cancelled = requireHandler(serviceHandlers.continuousBackground).cancelTimer(
            params.requiredIdentifier("key"),
        )
        if (!cancelled) throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Timer was not found")
        RpcValue.Null
    }
    else -> noNativeHandler()
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
        if (body != null && bodyEncoding != null) {
            put("bodyEncoding", RpcValue.StringValue(bodyEncoding.name.lowercase(Locale.ROOT)))
        }
        error?.let {
            put(
                "error",
                RuntimeRpcError(it.code, it.message).toRpcValue(),
            )
        }
    })
}

private fun RpcValue.ObjectValue.toBackgroundActivityRequest(): RuntimeBackgroundActivityRequest {
    requireOnly("sessionId", "text", "detail", "updatedAt")
    return RuntimeBackgroundActivityRequest(
        sessionId = requiredSessionId(),
        text = requiredDisplayText("text"),
        detail = optionalDisplayText("detail"),
        updatedAt = optionalLong("updatedAt", 0, MAX_SAFE_INTEGER),
    )
}

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
            val url = requiredString("url", rpcMaxResponseBytes)
            require(url == url.trim() && url.none(Char::isISOControl))
            RuntimeBackgroundTaskOperation.HttpGet(url)
        }
        "notify" -> {
            requireOnly("type", "title", "body")
            RuntimeBackgroundTaskOperation.Notify(
                title = requiredString("title", rpcMaxResponseBytes),
                body = requiredText("body", rpcMaxResponseBytes),
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

private const val MIN_PERIODIC_INTERVAL_MINUTES = 15L
