package io.toolbox.host.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.toolbox.host.MainActivity
import io.toolbox.host.R
import io.toolbox.host.ToolBoxApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RuntimeForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        promote(sessionCount = 0, usesLocation = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val dependencies = withContext(Dispatchers.IO) {
                (application as ToolBoxApplication).hostDependencies()
            }
            when (intent?.action) {
                ACTION_STOP_SESSION -> intent.getStringExtra(EXTRA_SESSION_ID)?.let {
                    dependencies.runtimeSessions.stopSession(it)
                }
                ACTION_STOP_ALL -> dependencies.runtimeSessions.stopAll()
                ACTION_RESTORE_REBOOT -> dependencies.runtimeSessions.recover(RESTORE_REBOOT)
                else -> dependencies.runtimeSessions.recover(RESTORE_PROCESS)
            }
            val count = dependencies.runtimeSessions.activeSessionCount()
            if (count == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                promote(count, dependencies.runtimeSessions.hasBackgroundLocationWatch())
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun promote(sessionCount: Int, usesLocation: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ToolBox 后台运行", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RuntimeForegroundService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_toolbox)
            .setContentTitle(if (sessionCount == 0) "正在准备后台工具" else "$sessionCount 个工具正在后台运行")
            .setContentText("点击可返回 ToolBox，或随时停止后台环境")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "打开", open).build())
            .addAction(Notification.Action.Builder(null, "全部停止", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (usesLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "toolbox.runtime.service.v1"
        private const val NOTIFICATION_ID = 0x544258
        private const val ACTION_REFRESH = "io.toolbox.host.runtime.REFRESH"
        private const val ACTION_RESTORE_REBOOT = "io.toolbox.host.runtime.RESTORE_REBOOT"
        private const val ACTION_STOP_SESSION = "io.toolbox.host.runtime.STOP_SESSION"
        private const val ACTION_STOP_ALL = "io.toolbox.host.runtime.STOP_ALL"
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val RESTORE_PROCESS = "process"
        private const val RESTORE_REBOOT = "reboot"

        fun ensureRunning(context: Context): Boolean = runCatching {
                context.startForegroundService(
                    Intent(context, RuntimeForegroundService::class.java).setAction(ACTION_REFRESH),
                )
            }.isSuccess

        fun refresh(context: Context) {
            ensureRunning(context)
        }

        fun restoreAfterBoot(context: Context): Boolean = runCatching {
            context.startForegroundService(
                Intent(context, RuntimeForegroundService::class.java).setAction(ACTION_RESTORE_REBOOT),
            )
        }.isSuccess

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RuntimeForegroundService::class.java)) }
        }

        fun stopSessionPendingIntent(context: Context, sessionId: String): PendingIntent = PendingIntent.getService(
            context,
            sessionId.hashCode(),
            Intent(context, RuntimeForegroundService::class.java)
                .setAction(ACTION_STOP_SESSION)
                .putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
