package io.toolbox.host.runtime

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.toolbox.host.MainActivity
import io.toolbox.host.ToolBoxApplication
import io.toolbox.host.background.AndroidNotificationGateway
import io.toolbox.host.background.LiveNotificationSupportState
import io.toolbox.host.icons.ToolIconLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RuntimeForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private var snapshots: Job? = null
    private var startupDeadline: Job? = null
    private var latestStartId = 0
    private val toolIcons = mutableMapOf<String, Bitmap?>()
    private val iconLoads = mutableMapOf<String, Job>()
    private var activeIconSessions = emptySet<String>()
    private lateinit var renderer: RuntimeLiveNotificationRenderer
    private lateinit var notificationManager: NotificationManager
    private var support = LiveNotificationSupportState(0, false, false, Build.VERSION.SDK_INT >= 36, false)
    private val notifications = RuntimeNotificationController(object : RuntimeNotificationSink {
        override fun promote(card: RuntimeNotificationCard, usesLocation: Boolean) {
            val notification = build(card)
            if (Build.VERSION.SDK_INT >= 34) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    if (usesLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
                startForeground(card.notificationId, notification, types)
            } else {
                startForeground(card.notificationId, notification)
            }
            startupDeadline?.cancel()
            notificationManager.cancel(RuntimeNotificationIds.LEGACY_RUNTIME_ID)
        }

        override fun post(card: RuntimeNotificationCard): Boolean = try {
            notificationManager.notify(card.notificationId, build(card))
            true
        } catch (_: RuntimeException) {
            Log.w(TAG, "System rejected a runtime notification update")
            false
        }

        override fun cancel(notificationId: Int) {
            notificationManager.cancel(notificationId)
        }

        override fun stopForeground() {
            this@RuntimeForegroundService.stopForeground(STOP_FOREGROUND_REMOVE)
        }
    })

    override fun onCreate() {
        super.onCreate()
        renderer = RuntimeLiveNotificationRenderer(this)
        renderer.createChannel()
        notificationManager = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (!notifications.hasForegroundCarrier) {
            bootstrapSession(intent)?.let { session ->
                notifications.render(RuntimeForegroundNotificationSnapshot(listOf(session), emptyList(), usesLocation = false))
            }
            if (!notifications.hasForegroundCarrier && startupDeadline == null) {
                startupDeadline = scope.launch {
                    delay(4_000)
                    if (!notifications.hasForegroundCarrier) {
                        Log.w(TAG, "Runtime notification bootstrap timed out")
                        stopSelfResult(latestStartId)
                    }
                }
            }
        }
        scope.launch {
            commands.withLock {
                val dependencies = withContext(Dispatchers.IO) {
                    (application as ToolBoxApplication).hostDependencies()
                }
                val sessions = dependencies.runtimeSessions
                sessions.recover(if (intent?.action == ACTION_RESTORE_REBOOT) RESTORE_REBOOT else RESTORE_PROCESS)
                if (intent?.action == ACTION_STOP_SESSION) {
                    val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                    val toolId = intent.getStringExtra(EXTRA_TOOL_ID)
                    sessions.sessions.value.firstOrNull { it.sessionId == sessionId && it.toolId == toolId }
                        ?.let { sessions.stopSession(it.sessionId) }
                }
                if (snapshots == null) {
                    val activeIds = sessions.foregroundNotificationSnapshot().sessions.mapTo(hashSetOf()) { it.notificationId }
                    notificationManager.activeNotifications.filter {
                        it.tag == null && it.notification.channelId == RuntimeLiveNotificationRenderer.CHANNEL_ID &&
                            it.id !in activeIds
                    }.forEach { notificationManager.cancel(it.id) }
                    snapshots = scope.launch {
                        sessions.notificationSnapshots.collect {
                            support = withContext(Dispatchers.IO) {
                                AndroidNotificationGateway(this@RuntimeForegroundService).liveSupport()
                            }
                            val current = sessions.foregroundNotificationSnapshot()
                            notifications.render(current)
                            synchronizeIcons(current, dependencies.toolIcons, sessions)
                            if (current.sessions.isEmpty()) stopSelfResult(latestStartId)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        iconLoads.clear()
        toolIcons.clear()
        notifications.clear()
        super.onDestroy()
    }

    private fun build(card: RuntimeNotificationCard) = renderer.build(
        card,
        support,
        openSessionPendingIntent(this, card.session.toolId, card.session.sessionId),
        stopSessionPendingIntent(this, card.session.toolId, card.session.sessionId),
        toolIcon = toolIcons[card.session.sessionId],
    )

    private fun synchronizeIcons(
        snapshot: RuntimeForegroundNotificationSnapshot,
        loader: ToolIconLoader,
        sessions: RuntimeSessionManager,
    ) {
        activeIconSessions = snapshot.sessions.mapTo(hashSetOf()) { it.sessionId }
        (iconLoads.keys - activeIconSessions).forEach { iconLoads.remove(it)?.cancel() }
        toolIcons.keys.retainAll(activeIconSessions)
        snapshot.sessions.forEach { session ->
            val id = session.sessionId
            if (!toolIcons.containsKey(id) && id !in iconLoads) {
                iconLoads[id] = scope.launch {
                    try {
                        val bitmap = loader.load(session.toolId)
                        if (id in activeIconSessions && sessions.sessions.value.any {
                            it.sessionId == id && it.toolId == session.toolId
                        }) {
                            toolIcons[id] = bitmap
                            if (bitmap != null) notifications.refreshArtwork(id)
                        }
                    } finally {
                        iconLoads.remove(id)
                    }
                }
            }
        }
    }

    private fun bootstrapSession(intent: Intent?): RuntimeBackgroundSessionUi? {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: return null
        val toolId = intent.getStringExtra(EXTRA_TOOL_ID) ?: return null
        val toolName = intent.getStringExtra(EXTRA_TOOL_NAME) ?: return null
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (notificationId < RuntimeNotificationIds.FIRST_ID) return null
        return RuntimeBackgroundSessionUi(sessionId, toolId, toolName, intent.getLongExtra(EXTRA_STARTED_AT, 0), notificationId)
    }

    companion object {
        private const val ACTION_REFRESH = "io.toolbox.host.runtime.REFRESH"
        private const val ACTION_RESTORE_REBOOT = "io.toolbox.host.runtime.RESTORE_REBOOT"
        private const val ACTION_STOP_SESSION = "io.toolbox.host.runtime.STOP_SESSION"
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val EXTRA_TOOL_ID = "toolId"
        private const val EXTRA_TOOL_NAME = "toolName"
        private const val EXTRA_NOTIFICATION_ID = "notificationId"
        private const val EXTRA_STARTED_AT = "startedAt"
        private const val TAG = "RuntimeNotifications"
        private const val RESTORE_PROCESS = "process"
        private const val RESTORE_REBOOT = "reboot"

        fun ensureRunning(context: Context, snapshot: RuntimeForegroundNotificationSnapshot): Boolean {
            val first = snapshot.cards().firstOrNull()?.session ?: return false
            return runCatching {
                context.startForegroundService(
                    Intent(context, RuntimeForegroundService::class.java).setAction(ACTION_REFRESH)
                        .putExtra(EXTRA_SESSION_ID, first.sessionId)
                        .putExtra(EXTRA_TOOL_ID, first.toolId)
                        .putExtra(EXTRA_TOOL_NAME, first.toolName)
                        .putExtra(EXTRA_STARTED_AT, first.startedAt)
                        .putExtra(EXTRA_NOTIFICATION_ID, first.notificationId),
                )
            }.isSuccess
        }

        fun restoreAfterBoot(context: Context): Boolean = runCatching {
            context.startForegroundService(
                Intent(context, RuntimeForegroundService::class.java).setAction(ACTION_RESTORE_REBOOT),
            )
        }.isSuccess

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RuntimeForegroundService::class.java)) }
        }

        fun openSessionPendingIntent(context: Context, toolId: String, sessionId: String): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            MainActivity.openToolIntent(context, toolId).setData(actionUri(toolId, sessionId, "open")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun stopSessionPendingIntent(context: Context, toolId: String, sessionId: String): PendingIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, RuntimeForegroundService::class.java)
                .setAction(ACTION_STOP_SESSION)
                .setData(actionUri(toolId, sessionId, "stop"))
                .putExtra(EXTRA_TOOL_ID, toolId)
                .putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun actionUri(toolId: String, sessionId: String, action: String): Uri = Uri.Builder()
            .scheme("toolbox").authority("runtime-notification")
            .appendPath(toolId).appendPath(sessionId).appendPath(action).build()
    }
}
