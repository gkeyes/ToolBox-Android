package io.toolbox.host.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
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

    suspend fun postOrUpdate(
        toolId: String,
        notificationId: String,
        title: String,
        body: String,
    ): NotificationResult = prepare(toolId, notificationId, title, body).post()

    override suspend fun prepare(
        toolId: String,
        notificationId: String,
        title: String,
        body: String,
    ): BackgroundPreparedNotification = withContext(Dispatchers.Default) {
        if (appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext BackgroundPreparedNotification { NotificationResult.Rejected("SYSTEM_PERMISSION_DENIED") }
        }
        if (!isValidNotification(notificationId, title)) {
            return@withContext BackgroundPreparedNotification { NotificationResult.Rejected("INVALID_NOTIFICATION") }
        }
        val channelId = channelId(toolId)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "ToolBox · $toolId", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val toolIcon = withContext(Dispatchers.IO) {
            (appContext as? ToolBoxApplication)?.hostDependencies()?.toolIcons?.load(toolId)
        }
        val notification = Notification.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_toolbox_notification)
            .setLargeIcon(toolIcon?.let(Icon::createWithBitmap) ?: Icon.createWithResource(appContext, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        BackgroundPreparedNotification {
            if (appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                NotificationResult.Rejected("SYSTEM_PERMISSION_DENIED")
            } else {
                manager.notify(toolId, notificationId.hashCode(), notification)
                NotificationResult.Posted
            }
        }
    }

    suspend fun liveSupport(): LiveNotificationSupportState = withContext(Dispatchers.IO) {
        readLiveNotificationSupport(appContext)
    }

    private fun readLiveNotificationSupport(context: Context): LiveNotificationSupportState {
        val protocol = runCatching {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        }.getOrDefault(0)
        val permission = if (protocol > 0) cachedMiuiPermission(context, protocol) else false
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

    companion object {
        private const val MIUI_SUPPORT_CACHE_MILLIS = 30_000L
        private val MIUI_STATUS_URI = Uri.parse("content://miui.statusbar.notification.public")
        private data class MiuiPermission(val protocol: Int, val granted: Boolean, val checkedAt: Long)
        private val miuiCacheLock = Any()
        private var miuiPermission: MiuiPermission? = null

        fun invalidateMiuiSupportCache() {
            synchronized(miuiCacheLock) { miuiPermission = null }
        }

        private fun cachedMiuiPermission(context: Context, protocol: Int): Boolean = synchronized(miuiCacheLock) {
            val now = SystemClock.elapsedRealtime()
            miuiPermission?.takeIf {
                it.protocol == protocol && now >= it.checkedAt && now - it.checkedAt < MIUI_SUPPORT_CACHE_MILLIS
            }?.let { return@synchronized it.granted }
            val granted = runCatching {
                val extras = Bundle().apply { putString("package", context.packageName) }
                context.contentResolver.call(MIUI_STATUS_URI, "canShowFocus", null, extras)
                    ?.getBoolean("canShowFocus", false) == true
            }.getOrDefault(false)
            miuiPermission = MiuiPermission(protocol, granted, now)
            granted
        }
    }
}

data class LiveNotificationSupportState(
    val hyperOsProtocolVersion: Int,
    val hyperOsSupported: Boolean,
    val hyperOsPermissionReported: Boolean,
    val androidLiveAvailable: Boolean,
    val androidLiveAllowed: Boolean,
)

internal fun isValidNotification(notificationId: String, title: String): Boolean =
    notificationId.isNotBlank() && title.isNotBlank()
