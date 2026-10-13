package io.toolbox.host.runtime

import android.webkit.WebView
import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeAlarmHandler
import io.toolbox.tool.runtime.RuntimeAlarmSummary
import io.toolbox.tool.runtime.RuntimeBackgroundSessionSummary
import io.toolbox.tool.runtime.RuntimeContinuousBackgroundHandler
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeLocationWatchHandler
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import org.json.JSONObject

internal sealed interface RuntimeUiState {
    data object Loading : RuntimeUiState

    data class Ready(
        val runtime: PreparedToolRuntime,
        val webView: WebView,
        val mainEntryLoaded: Boolean,
    ) : RuntimeUiState

    data class Error(
        val code: String,
        val message: String,
    ) : RuntimeUiState
}

internal data class RuntimeBackgroundSessionUi(
    val sessionId: String,
    val toolId: String,
    val toolName: String,
    val startedAt: Long,
    val notificationId: Int,
    val statusText: String? = null,
    val livePrimaryText: String? = null,
    val liveSecondaryText: String? = null,
    val liveUpdatedAt: Long? = null,
    val liveProgress: Int? = null,
    val activityPrimaryText: String? = null,
    val activitySecondaryText: String? = null,
    val activityUpdatedAt: Long? = null,
)

internal data class RuntimeForegroundDetachPlan(
    val destroyHost: Boolean,
    val refreshNotification: Boolean,
)

internal fun runtimeForegroundDetachPlan(hasBackgroundSession: Boolean): RuntimeForegroundDetachPlan =
    if (hasBackgroundSession) {
        RuntimeForegroundDetachPlan(destroyHost = false, refreshNotification = true)
    } else {
        RuntimeForegroundDetachPlan(destroyHost = true, refreshNotification = false)
    }

internal data class HostRuntimeContinuityHandlers(
    val background: RuntimeContinuousBackgroundHandler,
    val locationWatch: RuntimeLocationWatchHandler,
    val alarms: RuntimeAlarmHandler,
    val requireForegroundRuntime: () -> Unit = {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "No foreground tool is available")
    },
)

internal object RuntimeReminderPolicy {
    const val INTERVAL_MILLIS = 12L * 60L * 60L * 1_000L

    fun nextReminderAt(startedAt: Long, lastReminderAt: Long?): Long =
        (lastReminderAt ?: startedAt) + INTERVAL_MILLIS
}

// Host, session and alarm records shared by RuntimeSessionManager and its services.

internal data class RuntimeHost(
    val runtime: PreparedToolRuntime,
    val installedVersion: ToolVersion,
    val webView: WebView,
    var mainEntryLoaded: Boolean,
    var state: RuntimeHostState,
    val playback: RuntimeWebViewPlayback,
) {
    val versionCode: Int get() = runtime.versionCode
    fun toUiState() = RuntimeUiState.Ready(runtime, webView, mainEntryLoaded)
}

internal enum class RuntimeHostState { ATTACHED, BACKGROUND_DETACHED, RESTORING, STOPPED }

internal data class StoredRuntimeSession(
    val sessionId: String,
    val startedAt: Long,
    val restoreAfterProcessDeath: Boolean,
    val restoreAfterReboot: Boolean,
    val lastReminderAt: Long?,
    val notificationId: Int,
    val activityPrimaryText: String? = null,
    val activitySecondaryText: String? = null,
    val activityUpdatedAt: Long? = null,
) {
    fun toRuntimeSummary() = RuntimeBackgroundSessionSummary(
        sessionId,
        startedAt,
        restoreAfterProcessDeath,
        restoreAfterReboot,
    )

    fun toJson() = JSONObject()
        .put("sessionId", sessionId)
        .put("startedAt", startedAt)
        .put("restoreAfterProcessDeath", restoreAfterProcessDeath)
        .put("restoreAfterReboot", restoreAfterReboot)
        .put("notificationId", notificationId)
        .apply {
            lastReminderAt?.let { put("lastReminderAt", it) }
            activityPrimaryText?.let { put("activityPrimaryText", it) }
            activitySecondaryText?.let { put("activitySecondaryText", it) }
            activityUpdatedAt?.let { put("activityUpdatedAt", it) }
        }

    companion object {
        fun fromJson(value: JSONObject) = StoredRuntimeSession(
            sessionId = value.getString("sessionId"),
            startedAt = value.getLong("startedAt"),
            restoreAfterProcessDeath = value.optBoolean("restoreAfterProcessDeath", true),
            restoreAfterReboot = value.optBoolean("restoreAfterReboot", false),
            lastReminderAt = value.optLong("lastReminderAt").takeIf { value.has("lastReminderAt") },
            notificationId = value.optInt("notificationId", 0),
            activityPrimaryText = value.optString("activityPrimaryText").takeIf { value.has("activityPrimaryText") && it.isNotBlank() },
            activitySecondaryText = value.optString("activitySecondaryText").takeIf { value.has("activitySecondaryText") && it.isNotBlank() },
            activityUpdatedAt = value.optLong("activityUpdatedAt").takeIf { value.has("activityUpdatedAt") },
        )
    }
}

internal data class StoredAlarm(
    val alarmId: String,
    val triggerAt: Long,
    val scheduledAt: Long,
) {
    fun toRuntimeSummary() = RuntimeAlarmSummary(alarmId, triggerAt, scheduledAt)

    fun toEvent(firedAt: Long) = RpcValue.ObjectValue(
        mapOf(
            "id" to RpcValue.StringValue(alarmId),
            "triggerAt" to RpcValue.Number(triggerAt.toDouble()),
            "scheduledAt" to RpcValue.Number(scheduledAt.toDouble()),
            "firedAt" to RpcValue.Number(firedAt.toDouble()),
        ),
    )

    fun toJson() = JSONObject()
        .put("id", alarmId)
        .put("triggerAt", triggerAt)
        .put("scheduledAt", scheduledAt)

    companion object {
        fun fromJson(value: JSONObject) = StoredAlarm(
            alarmId = value.getString("id"),
            triggerAt = value.getLong("triggerAt"),
            scheduledAt = value.getLong("scheduledAt"),
        )
    }
}

internal data class PersistedToolState(
    val toolId: String,
    val toolName: String,
    val versionCode: Int,
    val sessions: List<StoredRuntimeSession>,
    val alarms: List<StoredAlarm>,
)
