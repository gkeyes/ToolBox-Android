package io.toolbox.host.runtime

import android.app.Notification
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.toolbox.host.MainActivity
import io.toolbox.host.R
import io.toolbox.host.ToolBoxApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "RuntimeReceivers"

internal class RuntimeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val toolId = intent.getStringExtra(EXTRA_TOOL_ID) ?: return
        val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0).takeIf { it > 0 } ?: return
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Host start-up can fail on broken storage; that must not kill the process from a broadcast.
                runCatching {
                    val dependencies = (context.applicationContext as ToolBoxApplication).hostDependencies()
                    dependencies.runtimeSessions.handleAlarm(toolId, versionCode, alarmId)
                }.onFailure { Log.w(TAG, "Runtime alarm could not be delivered", it) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_ALARM = "io.toolbox.host.runtime.ALARM"
        private const val EXTRA_TOOL_ID = "toolId"
        private const val EXTRA_VERSION_CODE = "versionCode"
        private const val EXTRA_ALARM_ID = "alarmId"

        fun pendingIntent(
            context: Context,
            toolId: String,
            versionCode: Int,
            alarmId: String,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            31 * toolId.hashCode() + alarmId.hashCode(),
            Intent(context, RuntimeAlarmReceiver::class.java)
                .setAction(ACTION_ALARM)
                .setData(Uri.Builder().scheme("toolbox").authority("alarm").appendPath(toolId).appendPath(alarmId).build())
                .putExtra(EXTRA_TOOL_ID, toolId)
                .putExtra(EXTRA_VERSION_CODE, versionCode)
                .putExtra(EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

internal class RuntimeRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTORE_ACTIONS) return
        if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    runCatching {
                        (context.applicationContext as ToolBoxApplication).hostDependencies()
                            .runtimeSessions
                            .rescheduleAlarms()
                    }.onFailure { Log.w(TAG, "Runtime alarms could not be rescheduled", it) }
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    (context.applicationContext as ToolBoxApplication).hostDependencies()
                        .runtimeSessions
                        .recover(recoveryReason(intent.action))
                }.onFailure {
                    runCatching { showRestoreNotice(context) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showRestoreNotice(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ToolBox 后台恢复", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_toolbox_notification)
                .setContentTitle("ToolBox 后台环境等待恢复")
                .setContentText("系统暂未允许自动启动，点击打开 ToolBox 后继续。")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        /**
         * An app update replaces the package without a reboot: the old process simply died, so sessions follow
         * their process-death opt-in instead of being dropped as non-restorable after a reboot.
         */
        internal fun recoveryReason(action: String?): String =
            if (action == Intent.ACTION_MY_PACKAGE_REPLACED) "process" else "reboot"

        private const val CHANNEL_ID = "toolbox.runtime.restore.v1"
        private const val NOTIFICATION_ID = 0x544259
        private val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
