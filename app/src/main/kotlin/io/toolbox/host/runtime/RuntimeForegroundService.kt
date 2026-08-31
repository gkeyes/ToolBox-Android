package io.toolbox.host.runtime

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.toolbox.host.MainActivity
import io.toolbox.host.ToolBoxApplication
import io.toolbox.host.background.AndroidNotificationGateway
import io.toolbox.host.background.LiveNotificationSupportState
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
        promote(
            RuntimeForegroundNotificationSnapshot(emptyList(), emptyList(), usesLocation = false),
            LiveNotificationSupportState(
                hyperOsProtocolVersion = 0,
                hyperOsSupported = false,
                hyperOsPermissionReported = false,
                androidLiveAvailable = Build.VERSION.SDK_INT >= 36,
                androidLiveAllowed = false,
            ),
        )
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
            val snapshot = dependencies.runtimeSessions.foregroundNotificationSnapshot()
            if (snapshot.sessions.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                promote(snapshot, AndroidNotificationGateway(this@RuntimeForegroundService).liveSupport())
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun promote(
        snapshot: RuntimeForegroundNotificationSnapshot,
        support: LiveNotificationSupportState,
    ) {
        val renderer = RuntimeLiveNotificationRenderer(this)
        renderer.createChannel()
        val openIntent = snapshot.primarySession?.toolId?.let { toolId ->
            MainActivity.openToolIntent(this, toolId)
        } ?: Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAll = PendingIntent.getService(
            this,
            1,
            Intent(this, RuntimeForegroundService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopCurrent = snapshot.primarySession?.sessionId?.let { stopSessionPendingIntent(this, it) }
        val notification = renderer.build(snapshot, support, open, stopCurrent, stopAll)
        if (Build.VERSION.SDK_INT >= 34) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (snapshot.usesLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            startForeground(RuntimeLiveNotificationRenderer.NOTIFICATION_ID, notification, types)
        } else {
            startForeground(RuntimeLiveNotificationRenderer.NOTIFICATION_ID, notification)
        }
    }

    companion object {
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
