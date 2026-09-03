package io.toolbox.host.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.content.Context
import android.content.pm.PackageManager
import io.toolbox.host.R
import io.toolbox.host.ToolBoxApplication
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val toolIcon = withContext(Dispatchers.IO) {
            (appContext as? ToolBoxApplication)?.hostDependencies()?.toolIcons?.load(toolId)
        }
        val notification = Notification.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_toolbox)
            .apply { toolIcon?.let { setLargeIcon(it) } }
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        manager.notify(toolId, notificationId.hashCode(), notification)
        NotificationResult.Posted
    }

    suspend fun liveSupport(): LiveNotificationSupportState = withContext(Dispatchers.IO) {
        readLiveNotificationSupport(appContext)
    }

    private fun readLiveNotificationSupport(context: Context): LiveNotificationSupportState {
        val protocol = runCatching {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        }.getOrDefault(0)
        val permission = runCatching {
            val extras = Bundle().apply { putString("package", context.packageName) }
            context.contentResolver.call(
                Uri.parse("content://miui.statusbar.notification.public"),
                "canShowFocus",
                null,
                extras,
            )?.getBoolean("canShowFocus", false) == true
        }.getOrDefault(false)
        val androidLiveAvailable = Build.VERSION.SDK_INT >= 36
        val androidLiveAllowed = androidLiveAvailable && runCatching {
            manager.canPostPromotedNotifications()
        }.getOrDefault(false)
        return LiveNotificationSupportState(
            hyperOsProtocolVersion = protocol,
            hyperOsSupported = protocol > 0,
            hyperOsPermissionReported = permission,
            androidLiveAvailable = androidLiveAvailable,
            androidLiveAllowed = androidLiveAllowed,
        )
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

}

data class LiveNotificationSupportState(
    val hyperOsProtocolVersion: Int,
    val hyperOsSupported: Boolean,
    val hyperOsPermissionReported: Boolean,
    val androidLiveAvailable: Boolean,
    val androidLiveAllowed: Boolean,
)

internal fun isValidNotification(notificationId: String, title: String, body: String): Boolean =
    notificationId.isNotBlank() && title.isNotBlank() && title.length <= 64 && body.length <= 256
