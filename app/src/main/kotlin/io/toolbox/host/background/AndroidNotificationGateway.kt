package io.toolbox.host.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.content.Context
import android.content.pm.PackageManager
import io.toolbox.host.R
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AndroidNotificationGateway(
    context: Context,
) : BackgroundNotificationGateway {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    override suspend fun post(
        toolId: String,
        notificationId: String,
        title: String,
        body: String,
    ): NotificationResult = postOrUpdate(toolId, notificationId, title, body)

    suspend fun postOrUpdate(
        toolId: String,
        notificationId: String,
        title: String,
        body: String,
    ): NotificationResult = withContext(Dispatchers.Default) {
        if (appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext NotificationResult.Rejected("SYSTEM_PERMISSION_DENIED")
        }
        if (!isValidNotification(notificationId, title, body)) {
            return@withContext NotificationResult.Rejected("INVALID_NOTIFICATION")
        }
        val channelId = channelId(toolId)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "ToolBox · $toolId", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = Notification.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_toolbox)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        manager.notify(toolId, notificationId.hashCode(), notification)
        NotificationResult.Posted
    }

    suspend fun postFocus(
        toolId: String,
        notificationId: String,
        title: String,
        body: String,
        progress: Int?,
    ): FocusDeliveryResult = withContext(Dispatchers.IO) {
        val support = focusSupport()
        if (appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext FocusDeliveryResult.Rejected("SYSTEM_PERMISSION_DENIED")
        }
        if (!isValidNotification(notificationId, title, body) || progress?.let { it !in 0..100 } == true) {
            return@withContext FocusDeliveryResult.Rejected("INVALID_NOTIFICATION")
        }
        val channelId = channelId(toolId)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "ToolBox · $toolId", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = Notification.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_toolbox)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        if (support.supported && support.permissionGranted) {
            notification.extras.putString(
                FOCUS_PARAMETER_KEY,
                focusPayload(title, body, progress).toString(),
            )
        }
        manager.notify(toolId, notificationId.hashCode(), notification)
        FocusDeliveryResult.Posted(
            enhancementRequested = support.supported && support.permissionGranted,
            protocolVersion = support.protocolVersion,
        )
    }

    suspend fun focusSupport(): FocusSupportState = withContext(Dispatchers.IO) {
        val protocol = runCatching {
            Settings.System.getInt(appContext.contentResolver, "notification_focus_protocol", 0)
        }.getOrDefault(0)
        val permission = runCatching {
            val extras = Bundle().apply { putString("package", appContext.packageName) }
            appContext.contentResolver.call(
                Uri.parse("content://miui.statusbar.notification.public"),
                "canShowFocus",
                null,
                extras,
            )?.getBoolean("canShowFocus", false) == true
        }.getOrDefault(false)
        FocusSupportState(protocol, protocol > 0, permission)
    }

    override suspend fun cancel(toolId: String, notificationId: String) {
        manager.cancel(toolId, notificationId.hashCode())
    }

    override suspend fun cancelTool(toolId: String) {
        manager.activeNotifications
            .filter { it.tag == toolId }
            .forEach { manager.cancel(it.tag, it.id) }
        manager.deleteNotificationChannel(channelId(toolId))
    }

    private fun channelId(toolId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toolId.toByteArray(Charsets.UTF_8))
        return "toolbox.${digest.take(8).joinToString("") { "%02x".format(it) }}"
    }

    private fun focusPayload(title: String, body: String, progress: Int?): JSONObject = JSONObject().put(
        "param_v2",
        JSONObject()
            .put("protocol", 1)
            .put("business", "toolbox")
            .put("updatable", true)
            .put("filterWhenNoPermission", false)
            .put("ticker", title.take(8))
            .put(
                "baseInfo",
                JSONObject()
                    .put("title", title)
                    .put("content", body)
                    .put("type", 2)
                    .apply { progress?.let { put("progress", it) } },
            )
            .put(
                "param_island",
                JSONObject()
                    .put("islandProperty", 1)
                    .put("bigIslandArea", JSONObject())
                    .put("smallIslandArea", JSONObject()),
            ),
    )

    private companion object {
        const val FOCUS_PARAMETER_KEY = "miui.focus.param"
    }
}

data class FocusSupportState(
    val protocolVersion: Int,
    val supported: Boolean,
    val permissionGranted: Boolean,
)

sealed interface FocusDeliveryResult {
    data class Posted(val enhancementRequested: Boolean, val protocolVersion: Int) : FocusDeliveryResult
    data class Rejected(val errorCode: String) : FocusDeliveryResult
}

internal fun isValidNotification(notificationId: String, title: String, body: String): Boolean =
    notificationId.isNotBlank() && title.isNotBlank() && title.length <= 64 && body.length <= 256
