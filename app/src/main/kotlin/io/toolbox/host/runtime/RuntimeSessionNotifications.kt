package io.toolbox.host.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import io.toolbox.host.MainActivity
import io.toolbox.host.R
import io.toolbox.host.background.AndroidNotificationGateway
import io.toolbox.host.background.LiveNotificationSupportState
import io.toolbox.host.background.setToolIcon
import io.toolbox.tool.runtime.RuntimeAndroidLiveStatus
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeHyperOsIslandStatus
import io.toolbox.tool.runtime.RuntimeLiveNotificationResult
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long-running reminders, undelivered alarm notices and the live-notification support check. */
internal class RuntimeSessionNotifications(
    private val appContext: Context,
    /** Loads the tool's icon, from disk if it isn't cached; an alarm often fires in a fresh process. */
    private val loadToolIcon: suspend (toolId: String) -> Bitmap? = { null },
) {
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    suspend fun postRuntimeReminder(toolId: String, sessionId: String) {
        createRuntimeNotificationChannel()
        val open = PendingIntent.getActivity(
            appContext,
            sessionId.hashCode(),
            MainActivity.openToolIntent(appContext, toolId)
                .setData(RuntimeForegroundService.actionUri(toolId, sessionId, "reminder")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = RuntimeForegroundService.stopSessionPendingIntent(appContext, toolId, sessionId)
        val notification = Notification.Builder(appContext, RUNTIME_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_toolbox_notification)
            .withToolIcon(toolId)
            .setContentTitle("ToolBox 后台运行提醒")
            .setContentText("此工具已连续运行 12 小时")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setAutoCancel(true)
            .build()
        notificationManager.notify("runtime-reminder", sessionId.hashCode(), notification)
    }

    suspend fun postAlarmNotification(toolId: String, alarm: StoredAlarm) {
        createRuntimeNotificationChannel()
        val open = PendingIntent.getActivity(
            appContext,
            alarm.alarmId.hashCode(),
            // Two tools may use the same alarm id; the tool id in the data keeps their intents apart.
            MainActivity.openToolIntent(appContext, toolId)
                .setData(RuntimeForegroundService.actionUri(toolId, alarm.alarmId, "alarm")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(appContext, RUNTIME_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_toolbox_notification)
            .withToolIcon(toolId)
            .setContentTitle("ToolBox 提醒")
            .setContentText("点击打开对应工具")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notificationManager.notify("alarm:$toolId", alarm.alarmId.hashCode(), notification)
    }

    /** Without a tool icon these stay plain ToolBox notices, with no launcher icon repeated at the right. */
    private suspend fun Notification.Builder.withToolIcon(toolId: String): Notification.Builder {
        val icon = runCatching { withContext(Dispatchers.IO) { loadToolIcon(toolId) } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return this
        return setToolIcon(appContext, icon)
    }

    fun cancelRuntimeNotifications(sessionIds: Collection<String>) {
        sessionIds.forEach { sessionId ->
            notificationManager.cancel("runtime-reminder", sessionId.hashCode())
        }
    }

    private fun createRuntimeNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(RUNTIME_CHANNEL_ID, "ToolBox 后台保障", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    suspend fun notificationSupport(): LiveNotificationSupportState {
        val liveChannelBlocked = notificationManager
            .getNotificationChannel(RuntimeLiveNotificationRenderer.CHANNEL_ID)
            ?.importance == NotificationManager.IMPORTANCE_NONE
        if (appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !notificationManager.areNotificationsEnabled() || liveChannelBlocked
        ) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "系统通知未开启")
        }
        AndroidNotificationGateway.invalidateMiuiSupportCache()
        return AndroidNotificationGateway(appContext).liveSupport()
    }

    private companion object {
        const val RUNTIME_CHANNEL_ID = "toolbox.runtime.v1"
    }
}

internal fun LiveNotificationSupportState.toRuntimeResult() = RuntimeLiveNotificationResult(
    androidLive = when {
        !androidLiveAvailable -> RuntimeAndroidLiveStatus.UNAVAILABLE
        androidLiveAllowed -> RuntimeAndroidLiveStatus.REQUESTED
        else -> RuntimeAndroidLiveStatus.NOT_ALLOWED
    },
    hyperOsIsland = if (hyperOsSupported) {
        RuntimeHyperOsIslandStatus.REQUESTED
    } else {
        RuntimeHyperOsIslandStatus.UNAVAILABLE
    },
    hyperOsProtocolVersion = hyperOsProtocolVersion,
    hyperOsPermissionReported = hyperOsPermissionReported,
)
