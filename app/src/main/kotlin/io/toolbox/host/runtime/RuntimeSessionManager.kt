package io.toolbox.host.runtime

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.webkit.WebView
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.host.MainActivity
import io.toolbox.host.R
import io.toolbox.host.background.AndroidNotificationGateway
import io.toolbox.host.background.LiveNotificationSupportState
import io.toolbox.tool.runtime.HardenedRuntimeWebView
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeAlarmHandler
import io.toolbox.tool.runtime.RuntimeAlarmSummary
import io.toolbox.tool.runtime.RuntimeBridgeProvider
import io.toolbox.tool.runtime.RuntimeContinuousBackgroundHandler
import io.toolbox.tool.runtime.RuntimeBackgroundSessionSummary
import io.toolbox.tool.runtime.RuntimeBackgroundStartOptions
import io.toolbox.tool.runtime.RuntimeCreationPermitResult
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeLocationWatchHandler
import io.toolbox.tool.runtime.RuntimeLocationWatchOptions
import io.toolbox.tool.runtime.RuntimeAndroidLiveStatus
import io.toolbox.tool.runtime.RuntimeHyperOsIslandStatus
import io.toolbox.tool.runtime.RuntimeLiveNotificationRequest
import io.toolbox.tool.runtime.RuntimeLiveNotificationResult
import io.toolbox.tool.runtime.RuntimePermitProvider
import io.toolbox.tool.runtime.RuntimePreparationResult
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import io.toolbox.tool.runtime.RuntimeWebViewCallbacks
import io.toolbox.tool.runtime.RuntimeWebViewCreationResult
import io.toolbox.tool.runtime.ToolRuntimePreparer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
)

internal object RuntimeReminderPolicy {
    const val INTERVAL_MILLIS = 12L * 60L * 60L * 1_000L

    fun nextReminderAt(startedAt: Long, lastReminderAt: Long?): Long =
        (lastReminderAt ?: startedAt) + INTERVAL_MILLIS
}

internal class RuntimeSessionManager(
    context: Context,
    private val repositories: CoreDataRepositories,
    private val preparer: ToolRuntimePreparer,
    private val permitProvider: RuntimePermitProvider,
    private val bridgeProvider: () -> RuntimeBridgeProvider,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateByTool = ConcurrentHashMap<String, MutableStateFlow<RuntimeUiState>>()
    private val hosts = mutableMapOf<String, RuntimeHost>()
    private val openingTools = mutableSetOf<String>()
    private val visibleTools = mutableSetOf<String>()
    private val sessionsByTool = mutableMapOf<String, MutableMap<String, StoredRuntimeSession>>()
    private val notificationIds = RuntimeNotificationIds()
    private val restoredToolNames = mutableMapOf<String, String>()
    private val timersByTool = mutableMapOf<String, MutableMap<String, Job>>()
    private val watchesByTool = mutableMapOf<String, MutableMap<String, ActiveLocationWatch>>()
    private val alarmsByTool = mutableMapOf<String, MutableMap<String, StoredAlarm>>()
    private val reminderJobs = mutableMapOf<String, Job>()
    private val mutableSessions = MutableStateFlow<List<RuntimeBackgroundSessionUi>>(emptyList())
    private val mutableNotificationSnapshots = MutableStateFlow(
        RuntimeForegroundNotificationSnapshot(emptyList(), emptyList(), usesLocation = false),
    )
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val liveNotifications = LiveNotificationCoordinator(scope, nowMillis) {
        refreshForegroundService()
    }
    private var recovered = false

    val sessions: StateFlow<List<RuntimeBackgroundSessionUi>> = mutableSessions.asStateFlow()
    val notificationSnapshots: StateFlow<RuntimeForegroundNotificationSnapshot> = mutableNotificationSnapshots.asStateFlow()

    fun state(toolId: String): StateFlow<RuntimeUiState> =
        stateByTool.getOrPut(toolId) { MutableStateFlow(RuntimeUiState.Loading) }.asStateFlow()

    fun openForeground(toolId: String) {
        scope.launch {
            visibleTools += toolId
            hosts[toolId]?.let { host ->
                host.state = RuntimeHostState.ATTACHED
                stateFlow(toolId).value = host.toUiState()
            }
            ensureRuntime(toolId, restoreReason = null)
        }
    }

    fun retry(toolId: String) {
        scope.launch {
            destroyHost(toolId)
            stateFlow(toolId).value = RuntimeUiState.Loading
            ensureRuntime(toolId, restoreReason = null)
        }
    }

    fun reload(toolId: String) {
        scope.launch { hosts[toolId]?.webView?.reload() ?: retry(toolId) }
    }

    fun detachForeground(toolId: String) {
        scope.launch {
            if (!visibleTools.remove(toolId)) return@launch
            clearForegroundOnlyWatches(toolId)
            val plan = runtimeForegroundDetachPlan(!sessionsByTool[toolId].isNullOrEmpty())
            if (plan.destroyHost) {
                destroyHost(toolId)
            } else {
                hosts[toolId]?.state = RuntimeHostState.BACKGROUND_DETACHED
            }
            if (plan.refreshNotification) refreshForegroundService()
        }
    }

    fun handlers(runtime: PreparedToolRuntime): HostRuntimeContinuityHandlers = HostRuntimeContinuityHandlers(
        background = ContinuousBackgroundHandler(runtime.toolId, runtime.versionCode),
        locationWatch = LocationWatchHandler(runtime.toolId, runtime.versionCode, runtime.declaredCapabilities),
        alarms = AlarmHandler(runtime.toolId, runtime.versionCode),
    )

    suspend fun recover(reason: String) {
        if (withContext(Dispatchers.Main.immediate) { recovered }) return
        val recovery = withContext(Dispatchers.IO) { readPersistedState() }
        withContext(Dispatchers.Main.immediate) {
            if (recovered) return@withContext
            recovered = true
            val restoreReason = if (reason == RESTORE_REASON_REBOOT) RESTORE_REASON_REBOOT else RESTORE_REASON_PROCESS
            recovery.flatMap(PersistedToolState::sessions).filter { it.notificationId > 0 }.forEach {
                notificationIds.claim(it.sessionId, it.notificationId)
            }
            recovery.forEach { persisted ->
                restoredToolNames[persisted.toolId] = persisted.toolName
                val restorable = persisted.sessions.filter { session ->
                    if (restoreReason == RESTORE_REASON_REBOOT) session.restoreAfterReboot else session.restoreAfterProcessDeath
                }.map { it.copy(notificationId = notificationIds.claim(it.sessionId, it.notificationId)) }
                (persisted.sessions.map(StoredRuntimeSession::sessionId) - restorable.map(StoredRuntimeSession::sessionId).toSet())
                    .forEach(notificationIds::release)
                if (restorable.isNotEmpty()) {
                    sessionsByTool.getOrPut(persisted.toolId, ::linkedMapOf).putAll(
                        restorable.associateBy(StoredRuntimeSession::sessionId),
                    )
                    updateSessionProjection()
                    restorable.forEach(::scheduleReminder)
                }
                alarmsByTool.getOrPut(persisted.toolId, ::linkedMapOf).putAll(
                    persisted.alarms.associateBy(StoredAlarm::alarmId),
                )
                persisted.alarms.forEach { scheduleAlarm(persisted.toolId, persisted.versionCode, it) }
                if (restorable != persisted.sessions) persistSessions(persisted.toolId)
            }
            refreshForegroundService()
            if (sessionsByTool.values.any(MutableMap<String, StoredRuntimeSession>::isNotEmpty)) {
                RuntimeForegroundService.ensureRunning(appContext, foregroundNotificationSnapshot())
                sessionsByTool.keys.toList().forEach { toolId ->
                    scope.launch { ensureRuntime(toolId, restoreReason) }
                }
            }
        }
    }

    suspend fun stopSession(sessionId: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val owner = sessionsByTool.entries.firstOrNull { sessionId in it.value }?.key ?: return@withContext false
        stopSession(owner, sessionId)
    }

    suspend fun stopTool(toolId: String, removeAlarms: Boolean = true) = withContext(Dispatchers.Main.immediate) {
        val removedSessionIds = sessionsByTool.remove(toolId)?.keys.orEmpty()
        removedSessionIds.forEach(::cancelReminder)
        removedSessionIds.forEach(notificationIds::release)
        liveNotifications.clearSessions(removedSessionIds)
        timersByTool.remove(toolId)?.values?.forEach(Job::cancel)
        clearAllWatches(toolId)
        cancelRuntimeNotifications(removedSessionIds)
        if (removeAlarms) {
            alarmsByTool.remove(toolId)?.values?.forEach { cancelScheduledAlarm(toolId, it.alarmId) }
            persistAlarms(toolId)
        }
        persistSessions(toolId)
        updateSessionProjection()
        if (toolId !in visibleTools) destroyHost(toolId)
        refreshForegroundService()
    }

    suspend fun releaseTool(toolId: String) = withContext(Dispatchers.Main.immediate) {
        stopTool(toolId)
        visibleTools -= toolId
        destroyHost(toolId)
        stateFlow(toolId).value = RuntimeUiState.Loading
    }

    suspend fun stopAll() = withContext(Dispatchers.Main.immediate) {
        sessionsByTool.keys.toList().forEach { stopTool(it, removeAlarms = false) }
        RuntimeForegroundService.stop(appContext)
    }

    suspend fun onCapabilityDisabled(toolId: String, capability: String) {
        when (capability) {
            "background.runtime" -> stopTool(toolId, removeAlarms = false)
            "notifications" -> withContext(Dispatchers.Main.immediate) {
                val sessionIds = sessionsByTool[toolId].orEmpty().keys
                liveNotifications.clearSessions(sessionIds)
            }
            "location" -> withContext(Dispatchers.Main.immediate) { clearAllWatches(toolId) }
            "location.background" -> withContext(Dispatchers.Main.immediate) { clearBackgroundWatches(toolId) }
            "alarms" -> withContext(Dispatchers.Main.immediate) {
                alarmsByTool.remove(toolId)?.values?.forEach { cancelScheduledAlarm(toolId, it.alarmId) }
                persistAlarms(toolId)
            }
        }
    }

    suspend fun handleAlarm(toolId: String, versionCode: Int, alarmId: String) {
        val needsLoad = withContext(Dispatchers.Main.immediate) { alarmsByTool[toolId] == null }
        val persisted = if (needsLoad) loadAlarms(toolId, versionCode) else emptyList()
        withContext(Dispatchers.Main.immediate) {
            if (persisted.isNotEmpty()) {
                alarmsByTool.getOrPut(toolId, ::linkedMapOf).putAll(persisted.associateBy(StoredAlarm::alarmId))
            }
            val alarm = alarmsByTool[toolId]?.remove(alarmId) ?: return@withContext
            persistAlarms(toolId)
            val payload = alarm.toEvent(nowMillis())
            val delivered = hosts[toolId]?.let { host ->
                host.versionCode == versionCode && HardenedRuntimeWebView.emitEvent(host.webView, EVENT_ALARM, payload)
            } == true
            if (!delivered) postAlarmNotification(toolId, alarm)
        }
    }

    suspend fun rescheduleAlarms() {
        val persisted = withContext(Dispatchers.IO) { readPersistedState() }
        withContext(Dispatchers.Main.immediate) {
            persisted.forEach { state ->
                alarmsByTool.getOrPut(state.toolId, ::linkedMapOf).putAll(
                    state.alarms.associateBy(StoredAlarm::alarmId),
                )
                state.alarms.forEach { alarm -> scheduleAlarm(state.toolId, state.versionCode, alarm) }
            }
        }
    }

    fun activeSessionCount(): Int = sessionsByTool.values.sumOf(Map<String, StoredRuntimeSession>::size)

    fun hasBackgroundLocationWatch(): Boolean = watchesByTool.values.any { watches ->
        watches.values.any(ActiveLocationWatch::allowBackground)
    }

    fun foregroundNotificationSnapshot(): RuntimeForegroundNotificationSnapshot {
        val activeSessions = sessionProjection()
        val activeIds = activeSessions.mapTo(hashSetOf(), RuntimeBackgroundSessionUi::sessionId)
        return RuntimeForegroundNotificationSnapshot(
            sessions = activeSessions,
            presentations = liveNotifications.snapshot().filter { it.request.sessionId in activeIds },
            usesLocation = hasBackgroundLocationWatch(),
        )
    }

    suspend fun startLiveNotification(
        toolId: String,
        versionCode: Int,
        request: RuntimeLiveNotificationRequest,
    ): RuntimeLiveNotificationResult {
        withContext(Dispatchers.Main.immediate) {
            requireLiveSessionOwner(toolId, versionCode, request.sessionId)
        }
        val support = notificationSupport()
        return withContext(Dispatchers.Main.immediate) {
            val host = requireLiveSessionOwner(toolId, versionCode, request.sessionId)
            liveNotifications.start(toolId, host.runtime.toolName, request)
            support.toRuntimeResult()
        }
    }

    suspend fun updateLiveNotification(
        toolId: String,
        versionCode: Int,
        request: RuntimeLiveNotificationRequest,
    ): RuntimeLiveNotificationResult {
        withContext(Dispatchers.Main.immediate) {
            requireLiveSessionOwner(toolId, versionCode, request.sessionId)
        }
        val support = notificationSupport()
        return withContext(Dispatchers.Main.immediate) {
            val host = requireLiveSessionOwner(toolId, versionCode, request.sessionId)
            if (!liveNotifications.update(toolId, host.runtime.toolName, request)) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "实时展示尚未开始")
            }
            support.toRuntimeResult()
        }
    }

    suspend fun endLiveNotification(toolId: String, versionCode: Int, sessionId: String) {
        withContext(Dispatchers.Main.immediate) {
            requireLiveSessionOwner(toolId, versionCode, sessionId)
            liveNotifications.end(toolId, sessionId)
        }
    }

    private suspend fun ensureRuntime(toolId: String, restoreReason: String?) {
        if (hosts[toolId] != null || !openingTools.add(toolId)) {
            restoreReason?.let { reason -> hosts[toolId]?.emitRestore(reason) }
            return
        }
        stateFlow(toolId).value = RuntimeUiState.Loading
        try {
            val installed = withContext(Dispatchers.IO) { repositories.catalog.observeTool(toolId).first() }
            val prepared = withContext(Dispatchers.IO) { preparer.prepare(toolId, installed) }
            val runtime = (prepared as? RuntimePreparationResult.Prepared)?.runtime
            if (runtime == null) {
                val failure = prepared as RuntimePreparationResult.Failed
                stateFlow(toolId).value = RuntimeUiState.Error(failure.code.name, failure.message)
                return
            }
            when (val permit = permitProvider.acquireRuntimePermit(toolId, awaitExistingRuntimeRelease = false)) {
                is RuntimeCreationPermitResult.Rejected -> {
                    stateFlow(toolId).value = RuntimeUiState.Error(
                        "RUNTIME_PROFILE_UNAVAILABLE",
                        "工具运行环境暂时不可用，请重试。",
                    )
                }
                is RuntimeCreationPermitResult.Ready -> {
                    val result = HardenedRuntimeWebView.create(
                        context = appContext,
                        runtime = runtime,
                        creationPermit = permit.permit,
                        callbacks = RuntimeWebViewCallbacks(
                            onMainEntryLoaded = { onMainEntryLoaded(toolId, restoreReason) },
                            onMainEntryFailed = { message -> onRuntimeFailed(toolId, "ENTRY_LOAD_FAILED", message) },
                            onRendererGone = { onRuntimeFailed(toolId, "RENDERER_GONE", "工具渲染进程已退出，点击重试可重新打开。") },
                        ),
                        bridgeProvider = bridgeProvider(),
                    )
                    when (result) {
                        is RuntimeWebViewCreationResult.Created -> {
                            if (toolId !in visibleTools && sessionsByTool[toolId].isNullOrEmpty()) {
                                HardenedRuntimeWebView.release(result.webView)
                                stateFlow(toolId).value = RuntimeUiState.Loading
                                return
                            }
                            val host = RuntimeHost(
                                runtime = runtime,
                                webView = result.webView,
                                mainEntryLoaded = false,
                                state = when {
                                    restoreReason != null -> RuntimeHostState.RESTORING
                                    toolId in visibleTools -> RuntimeHostState.ATTACHED
                                    else -> RuntimeHostState.BACKGROUND_DETACHED
                                },
                            )
                            hosts[toolId] = host
                            updateSessionProjection()
                            stateFlow(toolId).value = host.toUiState()
                        }
                        is RuntimeWebViewCreationResult.Failed -> stateFlow(toolId).value = RuntimeUiState.Error(
                            "RUNTIME_WEBVIEW_CREATION_FAILED",
                            result.message,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stateFlow(toolId).value = RuntimeUiState.Error(
                "RUNTIME_PREPARATION_FAILED",
                "工具运行环境准备失败，请重试。",
            )
        } finally {
            openingTools -= toolId
        }
    }

    private fun onMainEntryLoaded(toolId: String, restoreReason: String?) {
        scope.launch {
            val current = hosts[toolId] ?: return@launch
            current.mainEntryLoaded = true
            current.state = if (toolId in visibleTools) RuntimeHostState.ATTACHED else RuntimeHostState.BACKGROUND_DETACHED
            stateFlow(toolId).value = current.toUiState()
            restoreReason?.let { reason -> current.emitRestore(reason) }
        }
    }

    private fun RuntimeHost.emitRestore(reason: String) {
        HardenedRuntimeWebView.emitEvent(
            webView,
            EVENT_BACKGROUND_RESTORE,
            RpcValue.ObjectValue(
                mapOf(
                    "reason" to RpcValue.StringValue(reason),
                    "restoredAt" to RpcValue.Number(nowMillis().toDouble()),
                ),
            ),
        )
    }

    private fun onRuntimeFailed(toolId: String, code: String, message: String) {
        scope.launch {
            destroyHost(toolId)
            stateFlow(toolId).value = RuntimeUiState.Error(code, message)
        }
    }

    private fun destroyHost(toolId: String) {
        hosts.remove(toolId)?.let { host ->
            host.state = RuntimeHostState.STOPPED
            HardenedRuntimeWebView.release(host.webView)
        }
        stateFlow(toolId).value = RuntimeUiState.Loading
    }

    private fun stateFlow(toolId: String): MutableStateFlow<RuntimeUiState> =
        stateByTool.getOrPut(toolId) { MutableStateFlow(RuntimeUiState.Loading) }

    private inner class ContinuousBackgroundHandler(
        private val toolId: String,
        private val versionCode: Int,
    ) : RuntimeContinuousBackgroundHandler {
        override suspend fun start(options: RuntimeBackgroundStartOptions): RuntimeBackgroundSessionSummary =
            withContext(Dispatchers.Main.immediate) {
                ensureCurrentRuntime(toolId, versionCode)
                if (!repositories.settings.settings.first().backgroundEnabled) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "后台保障总开关已关闭")
                }
                sessionsByTool[toolId]?.values?.firstOrNull()?.let { existing ->
                    val updated = existing.copy(
                        restoreAfterProcessDeath = options.restoreAfterProcessDeath,
                        restoreAfterReboot = options.restoreAfterReboot,
                    )
                    sessionsByTool.getValue(toolId)[existing.sessionId] = updated
                    try {
                        persistSessions(toolId)
                    } catch (failure: Exception) {
                        sessionsByTool.getValue(toolId)[existing.sessionId] = existing
                        throw failure
                    }
                    updateSessionProjection()
                    if (!RuntimeForegroundService.ensureRunning(appContext, foregroundNotificationSnapshot())) {
                        throw RuntimeHandlerException(
                            RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED,
                            "系统未允许继续后台运行环境",
                        )
                    }
                    return@withContext updated.toRuntimeSummary()
                }
                val sessionId = UUID.randomUUID().toString()
                val record = StoredRuntimeSession(
                    sessionId = sessionId,
                    startedAt = nowMillis(),
                    restoreAfterProcessDeath = options.restoreAfterProcessDeath,
                    restoreAfterReboot = options.restoreAfterReboot,
                    lastReminderAt = null,
                    notificationId = notificationIds.claim(sessionId),
                )
                sessionsByTool.getOrPut(toolId, ::linkedMapOf)[record.sessionId] = record
                try {
                    persistSessions(toolId)
                } catch (failure: Exception) {
                    sessionsByTool[toolId]?.remove(record.sessionId)
                    notificationIds.release(record.sessionId)
                    throw failure
                }
                updateSessionProjection()
                if (!RuntimeForegroundService.ensureRunning(appContext, foregroundNotificationSnapshot())) {
                    sessionsByTool[toolId]?.remove(record.sessionId)
                    notificationIds.release(record.sessionId)
                    persistSessions(toolId)
                    updateSessionProjection()
                    refreshForegroundService()
                    throw RuntimeHandlerException(
                        RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED,
                        "系统未允许启动后台运行环境",
                    )
                }
                scheduleReminder(record)
                updateSessionProjection()
                refreshForegroundService()
                record.toRuntimeSummary()
            }

        override suspend fun stop(sessionId: String): Boolean = withContext(Dispatchers.Main.immediate) {
            ensureCurrentRuntime(toolId, versionCode)
            stopSession(toolId, sessionId)
        }

        override suspend fun status(sessionId: String): RuntimeBackgroundSessionSummary? =
            withContext(Dispatchers.Main.immediate) {
                ensureCurrentRuntime(toolId, versionCode)
                sessionsByTool[toolId]?.get(sessionId)?.toRuntimeSummary()
            }

        override suspend fun list(): List<RuntimeBackgroundSessionSummary> = withContext(Dispatchers.Main.immediate) {
            ensureCurrentRuntime(toolId, versionCode)
            sessionsByTool[toolId].orEmpty().values.map(StoredRuntimeSession::toRuntimeSummary)
        }

        override suspend fun setTimer(key: String, intervalMillis: Long) = withContext(Dispatchers.Main.immediate) {
            ensureCurrentRuntime(toolId, versionCode)
            if (sessionsByTool[toolId].isNullOrEmpty()) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "请先启动后台运行环境")
            }
            val jobs = timersByTool.getOrPut(toolId, ::linkedMapOf)
            jobs.remove(key)?.cancel()
            jobs[key] = scope.launch {
                while (isActive) {
                    delay(intervalMillis)
                    val host = hosts[toolId] ?: continue
                    HardenedRuntimeWebView.emitEvent(
                        host.webView,
                        EVENT_BACKGROUND_TIMER,
                        RpcValue.ObjectValue(
                            mapOf(
                                "key" to RpcValue.StringValue(key),
                                "firedAt" to RpcValue.Number(nowMillis().toDouble()),
                            ),
                        ),
                    )
                }
            }
        }

        override suspend fun cancelTimer(key: String): Boolean = withContext(Dispatchers.Main.immediate) {
            timersByTool[toolId]?.remove(key)?.also(Job::cancel) != null
        }
    }

    private inner class LocationWatchHandler(
        private val toolId: String,
        private val versionCode: Int,
        private val declaredCapabilities: Set<String>,
    ) : RuntimeLocationWatchHandler {
        override suspend fun watch(options: RuntimeLocationWatchOptions): String {
            if (options.allowBackground) {
                if ("location.background" !in declaredCapabilities || "background.runtime" !in declaredCapabilities) {
                    throw RuntimeHandlerException(
                        RuntimeRpcErrorCode.NOT_DECLARED,
                        "后台定位需要声明 location.background 与 background.runtime",
                    )
                }
                val grants = repositories.grants.observeGrants(toolId).first().associate { it.capability to it.granted }
                if (grants["location.background"] != true || grants["background.runtime"] != true) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "后台定位权限未开启")
                }
            }
            return withContext(Dispatchers.Main.immediate) {
            ensureCurrentRuntime(toolId, versionCode)
            val foregroundPermission = if (options.precise) {
                Manifest.permission.ACCESS_FINE_LOCATION
            } else {
                Manifest.permission.ACCESS_COARSE_LOCATION
            }
            if (appContext.checkSelfPermission(foregroundPermission) != PackageManager.PERMISSION_GRANTED) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "位置权限不可用")
            }
            if (options.allowBackground) {
                if (sessionsByTool[toolId].isNullOrEmpty()) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "后台定位需要正在运行的后台环境")
                }
                if (appContext.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "请在后台保障中允许始终访问位置")
                }
            }
            val manager = locationManager
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "位置服务不可用")
            val provider = chooseProvider(manager, options.precise)
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "没有可用的位置来源")
            val watchId = UUID.randomUUID().toString()
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    emitLocation(toolId, watchId, location)
                }
            }
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    options.intervalMillis,
                    options.minDistanceMeters,
                    appContext.mainExecutor,
                    listener,
                )
            }.getOrElse {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "位置监听未能启动")
            }
            watchesByTool.getOrPut(toolId, ::linkedMapOf)[watchId] = ActiveLocationWatch(
                listener = listener,
                allowBackground = options.allowBackground,
            )
            if (options.allowBackground) refreshForegroundService()
            watchId
            }
        }

        override suspend fun clearWatch(watchId: String): Boolean = withContext(Dispatchers.Main.immediate) {
            val watch = watchesByTool[toolId]?.remove(watchId) ?: return@withContext false
            locationManager?.removeUpdates(watch.listener)
            refreshForegroundService()
            true
        }
    }

    private inner class AlarmHandler(
        private val toolId: String,
        private val versionCode: Int,
    ) : RuntimeAlarmHandler {
        override suspend fun schedule(alarm: RuntimeAlarmSummary): RuntimeAlarmSummary =
            withContext(Dispatchers.Main.immediate) {
                ensureCurrentRuntime(toolId, versionCode)
                val manager = alarmManager
                    ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "闹钟服务不可用")
                if (!manager.canScheduleExactAlarms()) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "请在后台保障中允许闹钟和提醒")
                }
                if (alarm.triggerAt <= nowMillis()) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "闹钟时间必须晚于当前时间")
                }
                val stored = StoredAlarm(
                    alarmId = alarm.alarmId,
                    triggerAt = alarm.triggerAt,
                    scheduledAt = alarm.scheduledAt,
                )
                alarmsByTool.getOrPut(toolId, ::linkedMapOf)[stored.alarmId] = stored
                try {
                    persistAlarms(toolId)
                } catch (failure: Exception) {
                    alarmsByTool[toolId]?.remove(stored.alarmId)
                    throw failure
                }
                if (!scheduleAlarm(toolId, versionCode, stored)) {
                    alarmsByTool[toolId]?.remove(stored.alarmId)
                    persistAlarms(toolId)
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "精确闹钟未能登记")
                }
                stored.toRuntimeSummary()
            }

        override suspend fun list(): List<RuntimeAlarmSummary> = withContext(Dispatchers.Main.immediate) {
            ensureCurrentRuntime(toolId, versionCode)
            alarmsByTool[toolId].orEmpty().values.map(StoredAlarm::toRuntimeSummary)
        }

        override suspend fun cancel(alarmId: String): Boolean = withContext(Dispatchers.Main.immediate) {
            val removed = alarmsByTool[toolId]?.remove(alarmId) ?: return@withContext false
            cancelScheduledAlarm(toolId, removed.alarmId)
            persistAlarms(toolId)
            true
        }
    }

    private suspend fun stopSession(toolId: String, sessionId: String): Boolean {
        val removed = sessionsByTool[toolId]?.remove(sessionId) ?: return false
        notificationIds.release(sessionId)
        cancelReminder(removed.sessionId)
        liveNotifications.clearSessions(listOf(sessionId))
        if (sessionsByTool[toolId].isNullOrEmpty()) {
            timersByTool.remove(toolId)?.values?.forEach(Job::cancel)
            clearBackgroundWatches(toolId)
            cancelRuntimeNotifications(listOf(sessionId))
            if (toolId !in visibleTools) destroyHost(toolId)
        }
        persistSessions(toolId)
        updateSessionProjection()
        refreshForegroundService()
        return true
    }

    private fun ensureCurrentRuntime(toolId: String, versionCode: Int) {
        val host = hosts[toolId]
        if (host == null || host.versionCode != versionCode) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "工具运行环境已改变")
        }
    }

    private fun requireLiveSessionOwner(toolId: String, versionCode: Int, sessionId: String): RuntimeHost {
        ensureCurrentRuntime(toolId, versionCode)
        if (sessionsByTool[toolId]?.containsKey(sessionId) != true) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "后台运行会话不属于当前工具")
        }
        return checkNotNull(hosts[toolId])
    }

    private suspend fun notificationSupport(): LiveNotificationSupportState {
        val liveChannelBlocked = notificationManager
            .getNotificationChannel(RuntimeLiveNotificationRenderer.CHANNEL_ID)
            ?.importance == NotificationManager.IMPORTANCE_NONE
        if (appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !notificationManager.areNotificationsEnabled() || liveChannelBlocked
        ) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "系统通知未开启")
        }
        return AndroidNotificationGateway(appContext).liveSupport()
    }

    private fun LiveNotificationSupportState.toRuntimeResult() = RuntimeLiveNotificationResult(
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

    private fun emitLocation(toolId: String, watchId: String, location: Location) {
        val host = hosts[toolId] ?: return
        HardenedRuntimeWebView.emitEvent(
            host.webView,
            EVENT_LOCATION_CHANGED,
            RpcValue.ObjectValue(
                mapOf(
                    "watchId" to RpcValue.StringValue(watchId),
                    "latitude" to RpcValue.Number(location.latitude),
                    "longitude" to RpcValue.Number(location.longitude),
                    "accuracyMeters" to RpcValue.Number(location.accuracy.toDouble()),
                    "capturedAt" to RpcValue.Number(location.time.toDouble()),
                ),
            ),
        )
    }

    private fun chooseProvider(manager: LocationManager, precise: Boolean): String? {
        val candidates = if (precise) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        return candidates.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    private fun clearForegroundOnlyWatches(toolId: String) {
        val watches = watchesByTool[toolId] ?: return
        val foregroundOnly = watches.filterValues { !it.allowBackground }.keys
        foregroundOnly.forEach { watchId -> watches.remove(watchId)?.let { locationManager?.removeUpdates(it.listener) } }
    }

    private fun clearBackgroundWatches(toolId: String) {
        val watches = watchesByTool[toolId] ?: return
        val background = watches.filterValues(ActiveLocationWatch::allowBackground).keys
        background.forEach { watchId -> watches.remove(watchId)?.let { locationManager?.removeUpdates(it.listener) } }
        refreshForegroundService()
    }

    private fun clearAllWatches(toolId: String) {
        watchesByTool.remove(toolId)?.values?.forEach { locationManager?.removeUpdates(it.listener) }
        refreshForegroundService()
    }

    private fun updateSessionProjection() {
        mutableSessions.value = sessionProjection()
    }

    private fun sessionProjection(): List<RuntimeBackgroundSessionUi> {
        val hostNames = hosts.mapValues { it.value.runtime.toolName }
        return sessionsByTool.flatMap { (toolId, sessions) ->
            sessions.values.map { record ->
                RuntimeBackgroundSessionUi(
                    sessionId = record.sessionId,
                    toolId = toolId,
                    toolName = hostNames[toolId] ?: restoredToolNames[toolId] ?: toolId,
                    startedAt = record.startedAt,
                    notificationId = record.notificationId,
                )
            }
        }.sortedBy(RuntimeBackgroundSessionUi::startedAt)
    }

    private fun scheduleReminder(record: StoredRuntimeSession) {
        cancelReminder(record.sessionId)
        reminderJobs[record.sessionId] = scope.launch {
            var nextAt = RuntimeReminderPolicy.nextReminderAt(record.startedAt, record.lastReminderAt)
            while (isActive) {
                delay((nextAt - nowMillis()).coerceAtLeast(1))
                val owner = sessionsByTool.entries.firstOrNull { record.sessionId in it.value }?.key ?: return@launch
                postRuntimeReminder(owner, record.sessionId)
                val updated = sessionsByTool[owner]?.get(record.sessionId)?.copy(lastReminderAt = nowMillis())
                    ?: return@launch
                sessionsByTool[owner]?.set(record.sessionId, updated)
                persistSessions(owner)
                nextAt = RuntimeReminderPolicy.nextReminderAt(updated.startedAt, updated.lastReminderAt)
            }
        }
    }

    private fun cancelReminder(sessionId: String) {
        reminderJobs.remove(sessionId)?.cancel()
    }

    private fun postRuntimeReminder(toolId: String, sessionId: String) {
        createRuntimeNotificationChannel()
        val open = PendingIntent.getActivity(
            appContext,
            sessionId.hashCode(),
            MainActivity.openToolIntent(appContext, toolId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = RuntimeForegroundService.stopSessionPendingIntent(appContext, toolId, sessionId)
        val notification = Notification.Builder(appContext, RUNTIME_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_toolbox)
            .setContentTitle("ToolBox 后台运行提醒")
            .setContentText("此工具已连续运行 12 小时")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setAutoCancel(true)
            .build()
        notificationManager.notify("runtime-reminder", sessionId.hashCode(), notification)
    }

    private fun postAlarmNotification(toolId: String, alarm: StoredAlarm) {
        createRuntimeNotificationChannel()
        val open = PendingIntent.getActivity(
            appContext,
            alarm.alarmId.hashCode(),
            MainActivity.openToolIntent(appContext, toolId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(appContext, RUNTIME_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_toolbox)
            .setContentTitle("ToolBox 提醒")
            .setContentText("点击打开对应工具")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notificationManager.notify("alarm:$toolId", alarm.alarmId.hashCode(), notification)
    }

    private fun cancelRuntimeNotifications(sessionIds: Collection<String>) {
        sessionIds.forEach { sessionId ->
            notificationManager.cancel("runtime-reminder", sessionId.hashCode())
        }
    }

    private fun createRuntimeNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(RUNTIME_CHANNEL_ID, "ToolBox 后台保障", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun scheduleAlarm(toolId: String, versionCode: Int, alarm: StoredAlarm): Boolean {
        val manager = alarmManager ?: return false
        if (!manager.canScheduleExactAlarms()) return false
        return runCatching {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                alarm.triggerAt,
                RuntimeAlarmReceiver.pendingIntent(appContext, toolId, versionCode, alarm.alarmId),
            )
        }.isSuccess
    }

    private fun cancelScheduledAlarm(toolId: String, alarmId: String) {
        alarmManager?.cancel(RuntimeAlarmReceiver.pendingIntent(appContext, toolId, 0, alarmId))
    }

    private suspend fun persistSessions(toolId: String) {
        val snapshot = sessionsByTool[toolId].orEmpty().values.toList()
        withContext(Dispatchers.IO) {
            val result = if (snapshot.isEmpty()) {
                repositories.keyValues.remove(toolId, RUNTIME_SESSIONS_KEY)
            } else {
                repositories.keyValues.put(
                    toolId,
                    RUNTIME_SESSIONS_KEY,
                    JSONArray().also { array -> snapshot.forEach { array.put(it.toJson()) } }.toString(),
                    nowMillis(),
                )
            }
            requirePersistence(result, allowMissingTool = snapshot.isEmpty())
        }
    }

    private suspend fun persistAlarms(toolId: String) {
        val snapshot = alarmsByTool[toolId].orEmpty().values.toList()
        withContext(Dispatchers.IO) {
            val result = if (snapshot.isEmpty()) {
                repositories.keyValues.remove(toolId, RUNTIME_ALARMS_KEY)
            } else {
                repositories.keyValues.put(
                    toolId,
                    RUNTIME_ALARMS_KEY,
                    JSONArray().also { array -> snapshot.forEach { array.put(it.toJson()) } }.toString(),
                    nowMillis(),
                )
            }
            requirePersistence(result, allowMissingTool = snapshot.isEmpty())
        }
    }

    private fun requirePersistence(result: DataResult<Unit>, allowMissingTool: Boolean) {
        when (result) {
            is DataResult.Success -> Unit
            is DataResult.Failure.NotFound -> if (!allowMissingTool) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "工具已不存在")
            }
            is DataResult.Failure.QuotaExceeded -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.QUOTA_EXCEEDED,
                "工具存储空间不足，无法保存后台状态",
            )
            is DataResult.Failure -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.INTERNAL_ERROR,
                "后台状态未能保存",
            )
        }
    }

    private suspend fun readPersistedState(): List<PersistedToolState> {
        return repositories.catalog.observeTools().first().mapNotNull { tool ->
            val toolId = tool.metadata.id
            val sessions = repositories.keyValues.observe(toolId, RUNTIME_SESSIONS_KEY).first()?.valueJson
                ?.let(::parseSessions).orEmpty()
            val alarms = repositories.keyValues.observe(toolId, RUNTIME_ALARMS_KEY).first()?.valueJson
                ?.let(::parseAlarms).orEmpty()
            if (sessions.isEmpty() && alarms.isEmpty()) null else PersistedToolState(
                toolId = toolId,
                toolName = tool.metadata.name,
                versionCode = tool.currentVersion.versionCode,
                sessions = sessions,
                alarms = alarms,
            )
        }
    }

    private suspend fun loadAlarms(toolId: String, versionCode: Int): List<StoredAlarm> =
        withContext(Dispatchers.IO) {
            val installed = repositories.catalog.observeTool(toolId).first() ?: return@withContext emptyList()
            if (installed.currentVersion.versionCode != versionCode) return@withContext emptyList()
            repositories.keyValues.observe(toolId, RUNTIME_ALARMS_KEY).first()?.valueJson
                ?.let(::parseAlarms)
                .orEmpty()
        }

    private fun parseSessions(value: String): List<StoredRuntimeSession> = runCatching {
        val array = JSONArray(value)
        List(array.length()) { index -> StoredRuntimeSession.fromJson(array.getJSONObject(index)) }
    }.getOrDefault(emptyList())

    private fun parseAlarms(value: String): List<StoredAlarm> = runCatching {
        val array = JSONArray(value)
        List(array.length()) { index -> StoredAlarm.fromJson(array.getJSONObject(index)) }
    }.getOrDefault(emptyList())

    private fun refreshForegroundService() {
        mutableNotificationSnapshots.value = foregroundNotificationSnapshot()
    }

    private data class RuntimeHost(
        val runtime: PreparedToolRuntime,
        val webView: WebView,
        var mainEntryLoaded: Boolean,
        var state: RuntimeHostState,
    ) {
        val versionCode: Int get() = runtime.versionCode
        fun toUiState() = RuntimeUiState.Ready(runtime, webView, mainEntryLoaded)
    }

    private enum class RuntimeHostState { CREATING, ATTACHED, BACKGROUND_DETACHED, RESTORING, STOPPED, FAILED }

    private data class ActiveLocationWatch(
        val listener: LocationListener,
        val allowBackground: Boolean,
    )

    private data class StoredRuntimeSession(
        val sessionId: String,
        val startedAt: Long,
        val restoreAfterProcessDeath: Boolean,
        val restoreAfterReboot: Boolean,
        val lastReminderAt: Long?,
        val notificationId: Int,
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
            .apply { lastReminderAt?.let { put("lastReminderAt", it) } }

        companion object {
            fun fromJson(value: JSONObject) = StoredRuntimeSession(
                sessionId = value.getString("sessionId"),
                startedAt = value.getLong("startedAt"),
                restoreAfterProcessDeath = value.optBoolean("restoreAfterProcessDeath", true),
                restoreAfterReboot = value.optBoolean("restoreAfterReboot", false),
                lastReminderAt = value.optLong("lastReminderAt").takeIf { value.has("lastReminderAt") },
                notificationId = value.optInt("notificationId", 0),
            )
        }
    }

    private data class StoredAlarm(
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

    private data class PersistedToolState(
        val toolId: String,
        val toolName: String,
        val versionCode: Int,
        val sessions: List<StoredRuntimeSession>,
        val alarms: List<StoredAlarm>,
    )

    private companion object {
        const val RUNTIME_SESSIONS_KEY = "__toolbox.host.runtime-sessions.v1"
        const val RUNTIME_ALARMS_KEY = "__toolbox.host.alarms.v1"
        const val RUNTIME_CHANNEL_ID = "toolbox.runtime.v1"
        const val EVENT_BACKGROUND_RESTORE = "background.restore"
        const val EVENT_BACKGROUND_TIMER = "background.timer"
        const val EVENT_LOCATION_CHANGED = "location.onChanged"
        const val EVENT_ALARM = "alarm"
        const val RESTORE_REASON_PROCESS = "process"
        const val RESTORE_REASON_REBOOT = "reboot"
    }
}
