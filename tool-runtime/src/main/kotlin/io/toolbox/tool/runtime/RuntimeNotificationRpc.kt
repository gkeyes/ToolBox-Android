package io.toolbox.tool.runtime

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

internal suspend fun RuntimeRpcDispatcher.invokeNotifications(
    method: String,
    params: RpcValue.ObjectValue,
): RpcValue = when (method) {
    "notifications.post" -> {
        params.requireOnly("id", "title", "body")
        requireHandler(serviceHandlers.notifications).post(
            notificationId = params.requiredNotificationId(),
            title = params.requiredString("title", rpcMaxResponseBytes),
            body = params.requiredText("body", rpcMaxResponseBytes),
        )
        RpcValue.Null
    }
    "notifications.update" -> {
        params.requireOnly("id", "title", "body")
        requireHandler(serviceHandlers.notifications).update(
            notificationId = params.requiredNotificationId(),
            title = params.requiredString("title", rpcMaxResponseBytes),
            body = params.requiredText("body", rpcMaxResponseBytes),
        )
        RpcValue.Null
    }
    "notifications.cancel" -> {
        params.requireOnly("id")
        requireHandler(serviceHandlers.notifications).cancel(
            params.requiredNotificationId(),
        )
        RpcValue.Null
    }
    "notifications.live.start" -> {
        requireHandler(serviceHandlers.notifications)
            .startLive(params.toLiveNotificationRequest())
            .toRpcValue()
    }
    "notifications.live.update" -> {
        requireHandler(serviceHandlers.notifications)
            .updateLive(params.toLiveNotificationRequest())
            .toRpcValue()
    }
    "notifications.live.end" -> {
        params.requireOnly("sessionId")
        requireHandler(serviceHandlers.notifications).endLive(params.requiredSessionId())
        RpcValue.Null
    }
    else -> noNativeHandler()
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

private val LIVE_NOTIFICATION_COLOR = Regex("^#[0-9A-Fa-f]{6}$")

/** Longest notification id a tool may use; the host drops taps on anything longer. */
const val MAX_NOTIFICATION_ID_LENGTH = 256

private fun RpcValue.ObjectValue.requiredNotificationId(): String =
    requiredIdentifier("id").also { require(it.length <= MAX_NOTIFICATION_ID_LENGTH) }
