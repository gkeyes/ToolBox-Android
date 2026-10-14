package io.toolbox.host.runtime

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.ToolVersion
import io.toolbox.core.data.ThemeMode
import io.toolbox.tool.runtime.RuntimeWebViewTheme
import io.toolbox.host.HostTrace
import io.toolbox.host.background.AndroidNotificationGateway
import io.toolbox.tool.runtime.HardenedRuntimeWebView
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeBridgeProvider
import io.toolbox.tool.runtime.RuntimeContinuousBackgroundHandler
import io.toolbox.tool.runtime.RuntimeBackgroundActivityRequest
import io.toolbox.tool.runtime.RuntimeBackgroundSessionSummary
import io.toolbox.tool.runtime.RuntimeBackgroundStartOptions
import io.toolbox.tool.runtime.RuntimeCreationPermitResult
import io.toolbox.tool.runtime.RuntimeHandlerException
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Read-only copy of [hosts] for timer coroutines that run off the main thread.
    private val publishedHosts = MutableStateFlow<Map<String, RuntimeHost>>(emptyMap())
    private val openingTools = mutableMapOf<String, Job>()
    private val visibleTools = mutableSetOf<String>()
    private val recoveryStatus = mutableMapOf<String, String>()
    private val recovery = BackgroundRuntimeRecovery(scope, android.os.SystemClock::elapsedRealtime)
    private val sessionsByTool = mutableMapOf<String, MutableMap<String, StoredRuntimeSession>>()
    private val notificationIds = RuntimeNotificationIds()
    private val restoredToolNames = mutableMapOf<String, String>()
    private val mutableSessions = MutableStateFlow<List<RuntimeBackgroundSessionUi>>(emptyList())
    private val mutableNotificationSnapshots = MutableStateFlow(
        RuntimeForegroundNotificationSnapshot(emptyList(), emptyList(), usesLocation = false),
    )
    private var hostActivityResumed = false
    private val liveNotifications = LiveNotificationCoordinator(scope, nowMillis) {
        refreshForegroundService()
    }
    private val notifications = RuntimeSessionNotifications(appContext)
    private val store = RuntimeSessionStore(repositories, nowMillis)
    private val reminders = RuntimeSessionReminders(
        scope = scope,
        nowMillis = nowMillis,
        sessionsByTool = sessionsByTool,
        notifications = notifications,
        persistSessions = { toolId -> persistSessions(toolId) },
    )
    private val backgroundTimers = RuntimeBackgroundTimers(
        scope = scope,
        publishedHosts = publishedHosts,
        nowMillis = nowMillis,
        requestBackgroundRecovery = ::requestBackgroundRecovery,
        onRuntimeFailed = ::onRuntimeFailed,
    )
    private val locationWatches = RuntimeLocationWatchService(
        appContext = appContext,
        grantsFor = { toolId -> repositories.grants.observeGrants(toolId).first().associate { it.capability to it.granted } },
        hostFor = { toolId -> hosts[toolId] },
        isForeground = { toolId ->
            val host = hosts[toolId]
            hostActivityResumed && toolId in visibleTools && host != null && host.state == RuntimeHostState.ATTACHED &&
                host.webView.isAttachedToWindow && host.webView.isShown && host.webView.hasWindowFocus()
        },
        ensureCurrentRuntime = ::ensureCurrentRuntime,
        hasBackgroundSession = { toolId -> !sessionsByTool[toolId].isNullOrEmpty() },
        setRecoveryStatus = { toolId, status -> recoveryStatus[toolId] = status },
        refreshForegroundService = ::refreshForegroundService,
    )
    private val alarmService = RuntimeAlarmService(
        appContext = appContext,
        store = store,
        notifications = notifications,
        nowMillis = nowMillis,
        hostFor = { toolId -> hosts[toolId] },
        ensureCurrentRuntime = ::ensureCurrentRuntime,
    )
    private var recovered = false
    private var currentTheme = ThemeMode.SYSTEM
    private var configuration = Configuration(appContext.resources.configuration)

    init {
        scope.launch {
            repositories.settings.settings.map { it.theme }.distinctUntilChanged().collect { mode ->
                currentTheme = mode
                applyRuntimeTheme()
            }
        }
    }

    fun onSystemConfigurationChanged(updated: Configuration) {
        val snapshot = Configuration(updated)
        scope.launch {
            configuration = snapshot
            applyRuntimeTheme()
        }
    }

    fun setHostActivityResumed(resumed: Boolean) {
        if (hostActivityResumed == resumed) return
        hostActivityResumed = resumed
        if (resumed) AndroidNotificationGateway.invalidateMiuiSupportCache()
        hosts.values.forEach(::updateWebViewPlayback)
    }

    private fun applyRuntimeTheme() {
        val dark = currentTheme.runtimeDarkTheme(RuntimeWebViewTheme.isSystemDark(configuration))
        hosts.values.forEach { RuntimeWebViewTheme.apply(it.webView, dark, configuration) }
    }

    val sessions: StateFlow<List<RuntimeBackgroundSessionUi>> = mutableSessions.asStateFlow()
    val notificationSnapshots: StateFlow<RuntimeForegroundNotificationSnapshot> = mutableNotificationSnapshots.asStateFlow()

    fun state(toolId: String): StateFlow<RuntimeUiState> =
        stateByTool.getOrPut(toolId) { MutableStateFlow(RuntimeUiState.Loading) }.asStateFlow()

    fun openForeground(toolId: String) {
        scope.launch {
            if (io.toolbox.host.backup.BackupRuntimeGate.paused) {
                stateFlow(toolId).value = RuntimeUiState.Error("RESTORE_IN_PROGRESS", "正在恢复数据，请完成后重新打开工具。")
                return@launch
            }
            HostTrace.bestEffortAsyncSection("runtime.attach") {
                visibleTools += toolId
                hosts[toolId]?.let { host ->
                    HardenedRuntimeWebView.cancelClose(host.webView)
                    host.state = RuntimeHostState.ATTACHED
                    updateWebViewPlayback(host)
                    stateFlow(toolId).value = host.toUiState()
                }
                ensureRuntime(toolId, restoreReason = null)
            }
        }
    }

    fun retry(toolId: String) {
        scope.launch {
            recovery.cancel(toolId)?.join()
            recoveryStatus.remove(toolId)
            openingTools[toolId]?.cancelAndJoin()
            destroyHost(toolId)
            stateFlow(toolId).value = RuntimeUiState.Loading
            ensureRuntime(toolId, restoreReason = null)
        }
    }

    suspend fun prepareForegroundClose(toolId: String, reloading: Boolean): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!reloading && !sessionsByTool[toolId].isNullOrEmpty()) return@withContext true
        val host = hosts[toolId] ?: return@withContext true
        val saved = HardenedRuntimeWebView.flushBeforeClose(host.webView)
        if (!saved && hosts[toolId] === host) HardenedRuntimeWebView.cancelClose(host.webView)
        saved && hosts[toolId] === host
    }

    fun cancelForegroundClose(toolId: String) { hosts[toolId]?.webView?.let(HardenedRuntimeWebView::cancelClose) }

    fun discardForegroundRuntime(toolId: String) { destroyHost(toolId) }

    suspend fun reloadSaved(toolId: String) = withContext(Dispatchers.Main.immediate) {
        backgroundTimers.cancelAll(toolId)
        locationWatches.clearAllWatches(toolId)
        destroyHost(toolId)
        ensureRuntime(toolId, if (sessionsByTool[toolId].isNullOrEmpty()) null else RESTORE_REASON_PROCESS)
    }

    fun detachForeground(toolId: String) {
        scope.launch {
            if (!visibleTools.remove(toolId)) return@launch
            HostTrace.bestEffortSection("runtime.detach") { }
            locationWatches.clearForegroundOnlyWatches(toolId)
            val plan = runtimeForegroundDetachPlan(!sessionsByTool[toolId].isNullOrEmpty())
            if (plan.destroyHost) {
                // Normally the route was retained until saving finished. A forced route removal
                // still gets one bounded drain, with no extra deadline layered onto an old ticket.
                val host = hosts[toolId]
                if (host != null) HardenedRuntimeWebView.flushBeforeClose(host.webView, 2_000)
                if (hosts[toolId] === host && toolId !in visibleTools) destroyHost(toolId)
            } else {
                hosts[toolId]?.let { host ->
                    host.state = RuntimeHostState.BACKGROUND_DETACHED
                    updateWebViewPlayback(host)
                }
            }
            if (plan.refreshNotification) refreshForegroundService()
        }
    }

    fun handlers(runtime: PreparedToolRuntime): HostRuntimeContinuityHandlers = HostRuntimeContinuityHandlers(
        background = ContinuousBackgroundHandler(runtime.toolId, runtime.versionCode),
        locationWatch = locationWatches.handler(runtime.toolId, runtime.versionCode, runtime.declaredCapabilities),
        alarms = alarmService.handler(runtime.toolId, runtime.versionCode),
        requireForegroundRuntime = {
            val host = hosts[runtime.toolId]
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The tool runtime ended")
            if (host.runtime !== runtime) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The tool runtime instance changed")
            }
            if (visibleTools.singleOrNull() != runtime.toolId || host.state != RuntimeHostState.ATTACHED ||
                !host.webView.isAttachedToWindow || !host.webView.isShown || !host.webView.hasWindowFocus()
            ) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "Open this tool in the foreground before using this capability")
            }
        },
    )

    suspend fun recover(reason: String) {
        if (io.toolbox.host.backup.BackupRuntimeGate.paused) return
        if (withContext(Dispatchers.Main.immediate) { recovered }) return
        val recovery = withContext(Dispatchers.IO) { store.readPersistedState() }
        withContext(Dispatchers.Main.immediate) {
            if (recovered || io.toolbox.host.backup.BackupRuntimeGate.paused) return@withContext
            recovered = true
            val restoreReason = if (reason == RESTORE_REASON_REBOOT) RESTORE_REASON_REBOOT else RESTORE_REASON_PROCESS
            recovery.flatMap(PersistedToolState::sessions).filter { it.notificationId > 0 }.forEach {
                notificationIds.claim(it.sessionId, it.notificationId)
            }
            recovery.forEach { persisted ->
                restoredToolNames[persisted.toolId] = persisted.toolName
                val restorable = persisted.sessions.filter { session ->
                    backgroundRestoreOptedIn(restoreReason, session.restoreAfterProcessDeath, session.restoreAfterReboot)
                }.map { it.copy(notificationId = notificationIds.claim(it.sessionId, it.notificationId)) }
                (persisted.sessions.map(StoredRuntimeSession::sessionId) - restorable.map(StoredRuntimeSession::sessionId).toSet())
                    .forEach(notificationIds::release)
                if (restorable.isNotEmpty()) {
                    sessionsByTool.getOrPut(persisted.toolId, ::linkedMapOf).putAll(
                        restorable.associateBy(StoredRuntimeSession::sessionId),
                    )
                    updateSessionProjection()
                    restorable.forEach(reminders::schedule)
                }
                alarmService.restore(persisted.toolId, persisted.versionCode, persisted.alarms)
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
        recovery.cancel(toolId)?.join()
        recoveryStatus.remove(toolId)
        val removedSessionIds = sessionsByTool.remove(toolId)?.keys.orEmpty()
        removedSessionIds.forEach(reminders::cancel)
        removedSessionIds.forEach(notificationIds::release)
        liveNotifications.clearSessions(removedSessionIds)
        backgroundTimers.cancelAll(toolId)
        locationWatches.clearAllWatches(toolId)
        notifications.cancelRuntimeNotifications(removedSessionIds)
        if (removeAlarms) alarmService.removeAll(toolId)
        persistSessions(toolId)
        updateSessionProjection()
        if (toolId !in visibleTools) destroyHost(toolId)
        refreshForegroundService()
    }

    suspend fun releaseTool(toolId: String) = withContext(Dispatchers.Main.immediate) {
        recovery.cancel(toolId)?.join()
        recoveryStatus.remove(toolId)
        // An IO preparation may not own a WebView yet. Drain it before a same-version
        // replacement can publish new files or an old opener can acquire the new profile.
        openingTools[toolId]?.cancelAndJoin()
        stopTool(toolId)
        visibleTools -= toolId
        destroyHost(toolId)
        stateFlow(toolId).value = RuntimeUiState.Loading
    }

    suspend fun backupRuntimeIds(): Set<String> = withContext(Dispatchers.Main.immediate) {
        (hosts.keys + openingTools.keys + sessionsByTool.keys + alarmService.toolIds).toSet()
    }

    internal suspend fun backgroundCancellationToolIds(): List<String> = withContext(Dispatchers.Main.immediate) {
        // An empty owner still needs persistence cleanup after stopSession removed its last session.
        sessionsByTool.keys.toList()
    }

    suspend fun onCapabilityDisabled(toolId: String, capability: String) {
        when (capability) {
            "background.runtime" -> stopTool(toolId, removeAlarms = false)
            "notifications" -> withContext(Dispatchers.Main.immediate) {
                val sessionIds = sessionsByTool[toolId].orEmpty().keys
                liveNotifications.clearSessions(sessionIds)
            }
            "location" -> withContext(Dispatchers.Main.immediate) { locationWatches.clearAllWatches(toolId) }
            "location.background" -> withContext(Dispatchers.Main.immediate) { locationWatches.clearBackgroundWatches(toolId) }
            "alarms" -> withContext(Dispatchers.Main.immediate) { alarmService.removeAll(toolId) }
        }
    }

    suspend fun handleAlarm(toolId: String, versionCode: Int, alarmId: String) {
        if (io.toolbox.host.backup.BackupRuntimeGate.paused) return
        alarmService.handleAlarm(toolId, versionCode, alarmId)
    }

    suspend fun rescheduleAlarms() {
        if (io.toolbox.host.backup.BackupRuntimeGate.paused) return
        val persisted = withContext(Dispatchers.IO) { store.readPersistedState() }
        withContext(Dispatchers.Main.immediate) {
            persisted.forEach { state ->
                alarmService.restore(state.toolId, state.versionCode, state.alarms)
            }
        }
    }

    fun foregroundNotificationSnapshot(): RuntimeForegroundNotificationSnapshot {
        val presentations = liveNotifications.snapshot()
        val activeSessions = sessionProjection(presentations)
        val activeIds = activeSessions.mapTo(hashSetOf(), RuntimeBackgroundSessionUi::sessionId)
        return RuntimeForegroundNotificationSnapshot(
            sessions = activeSessions,
            presentations = presentations.filter { it.request.sessionId in activeIds },
            usesLocation = locationWatches.hasBackgroundLocationWatch(),
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
        val support = notifications.notificationSupport()
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
        val support = notifications.notificationSupport()
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
        if (io.toolbox.host.backup.BackupRuntimeGate.paused) return
        if (hosts[toolId] != null || toolId in openingTools) {
            restoreReason?.let { reason -> hosts[toolId]?.emitRestore(reason) }
            return
        }
        val openingJob = checkNotNull(currentCoroutineContext()[Job])
        openingTools[toolId] = openingJob
        stateFlow(toolId).value = RuntimeUiState.Loading
        try {
            // Await persisted appearance before constructing WebView, avoiding a light first document.
            currentTheme = withContext(Dispatchers.IO) { repositories.settings.settings.first().theme }
            val installed = withContext(Dispatchers.IO) { repositories.catalog.observeTool(toolId).first() }
            val prepared = HostTrace.bestEffortAsyncSection("tool.prepare") {
                withContext(Dispatchers.IO) { preparer.prepare(toolId, installed) }
            }
            if (io.toolbox.host.backup.BackupRuntimeGate.paused) return
            val runtime = (prepared as? RuntimePreparationResult.Prepared)?.runtime
            if (runtime == null) {
                val failure = prepared as RuntimePreparationResult.Failed
                stateFlow(toolId).value = RuntimeUiState.Error(failure.code.name, failure.message)
                return
            }
            if (restoreReason != null && (
                "background.runtime" !in runtime.declaredCapabilities ||
                !backgroundRecoveryAllowed(toolId, checkNotNull(installed).currentVersion, restoreReason)
            )) {
                stateFlow(toolId).value = RuntimeUiState.Error("BACKGROUND_RESTORE_BLOCKED", "后台恢复已停止，请检查授权后重新打开工具。")
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
                        darkTheme = currentTheme.runtimeDarkTheme(RuntimeWebViewTheme.isSystemDark(configuration)),
                        runtime = runtime,
                        creationPermit = permit.permit,
                        callbacks = RuntimeWebViewCallbacks(
                            onMainEntryLoaded = { onMainEntryLoaded(toolId, runtime, restoreReason) },
                            onMainEntryFailed = { message -> onRuntimeFailed(toolId, runtime, "ENTRY_LOAD_FAILED", message) },
                            onRendererGone = { onRuntimeFailed(toolId, runtime, "RENDERER_GONE", "工具渲染进程已退出。") },
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
                                installedVersion = checkNotNull(installed).currentVersion,
                                webView = result.webView,
                                mainEntryLoaded = false,
                                state = when {
                                    restoreReason != null -> RuntimeHostState.RESTORING
                                    toolId in visibleTools -> RuntimeHostState.ATTACHED
                                    else -> RuntimeHostState.BACKGROUND_DETACHED
                                },
                                playback = RuntimeWebViewPlayback(
                                    pause = {
                                        HostTrace.bestEffortSection("runtime.webview.pause") {
                                            result.webView.onPause()
                                        }
                                    },
                                    resume = {
                                        HostTrace.bestEffortSection("runtime.webview.resume") {
                                            result.webView.onResume()
                                        }
                                    },
                                ),
                            )
                            hosts[toolId] = host
                            publishedHosts.value = hosts.toMap()
                            updateWebViewPlayback(host)
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
            if (openingTools[toolId] === openingJob) openingTools.remove(toolId)
        }
    }

    private fun onMainEntryLoaded(toolId: String, runtime: PreparedToolRuntime, restoreReason: String?) {
        scope.launch {
            val current = hosts[toolId]?.takeIf { it.runtime === runtime } ?: return@launch
            recoveryStatus.remove(toolId)
            current.mainEntryLoaded = true
            current.state = if (toolId in visibleTools) RuntimeHostState.ATTACHED else RuntimeHostState.BACKGROUND_DETACHED
            updateWebViewPlayback(current)
            stateFlow(toolId).value = current.toUiState()
            restoreReason?.let { reason -> current.emitRestore(reason) }
            refreshForegroundService()
            // The page can receive events now; replay alarms that fired while the tool was not running.
            try {
                alarmService.deliverFired(toolId, current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Undelivered alarms stay stored and are retried on the next load.
            }
        }
    }

    private fun RuntimeHost.emitRestore(reason: String) {
        val emitted = HardenedRuntimeWebView.emitEvent(
            webView,
            EVENT_BACKGROUND_RESTORE,
            RpcValue.ObjectValue(
                mapOf(
                    "reason" to RpcValue.StringValue(reason),
                    "restoredAt" to RpcValue.Number(nowMillis().toDouble()),
                ),
            ),
        )
        if (!emitted) onRuntimeFailed(runtime.toolId, runtime, "EVENT_BACKLOG_OVERFLOW", "后台恢复事件无法交付，请重试。")
    }

    private fun onRuntimeFailed(toolId: String, runtime: PreparedToolRuntime, code: String, message: String) {
        scope.launch {
            // A delayed callback from a released WebView must not destroy its replacement.
            val failed = hosts[toolId]?.takeIf { it.runtime === runtime } ?: return@launch
            restoredToolNames[toolId] = runtime.toolName
            destroyHost(toolId)
            backgroundTimers.cancelAll(toolId)
            locationWatches.clearAllWatches(toolId)
            liveNotifications.clearSessions(sessionsByTool[toolId].orEmpty().keys)
            stateFlow(toolId).value = RuntimeUiState.Error(code, message)
            requestBackgroundRecovery(toolId, failed.installedVersion)
        }
    }

    private suspend fun backgroundRecoveryAllowed(
        toolId: String,
        expectedVersion: ToolVersion,
        reason: String = RESTORE_REASON_PROCESS,
    ): Boolean {
        fun optedIn() = sessionsByTool[toolId].orEmpty().values.any {
            backgroundRestoreOptedIn(reason, it.restoreAfterProcessDeath, it.restoreAfterReboot)
        }
        if (io.toolbox.host.backup.BackupRuntimeGate.paused || !optedIn()) return false
        val valid = withContext(Dispatchers.IO) {
            repositories.settings.settings.first().backgroundEnabled &&
                repositories.catalog.observeTool(toolId).first()?.currentVersion == expectedVersion &&
                repositories.grants.observeGrants(toolId).first().any { it.capability == "background.runtime" && it.granted }
        }
        return valid && !io.toolbox.host.backup.BackupRuntimeGate.paused &&
            optedIn()
    }

    private fun requestBackgroundRecovery(toolId: String, expectedVersion: ToolVersion) {
        if (sessionsByTool[toolId].isNullOrEmpty()) return
        recovery.request(
            toolId = toolId,
            allowed = { backgroundRecoveryAllowed(toolId, expectedVersion) },
            restart = {
                destroyHost(toolId)
                ensureRuntime(toolId, RESTORE_REASON_PROCESS)
                val result = stateFlow(toolId).first { state ->
                    state is RuntimeUiState.Error || state is RuntimeUiState.Ready && state.mainEntryLoaded
                }
                result is RuntimeUiState.Ready && backgroundRecoveryAllowed(toolId, expectedVersion)
            },
            onRecovering = { attempt ->
                recoveryStatus[toolId] = "后台环境中断，正在恢复（$attempt/3）"
                liveNotifications.clearSessions(sessionsByTool[toolId].orEmpty().keys)
                refreshForegroundService()
            },
            onInterrupted = {
                destroyHost(toolId)
                backgroundTimers.cancelAll(toolId)
                locationWatches.clearAllWatches(toolId)
                recoveryStatus[toolId] = "后台已中断，请打开工具重试或停止会话"
                stateFlow(toolId).value = RuntimeUiState.Error("BACKGROUND_INTERRUPTED", recoveryStatus.getValue(toolId))
                liveNotifications.clearSessions(sessionsByTool[toolId].orEmpty().keys)
                refreshForegroundService()
            },
        )
    }

    private fun destroyHost(toolId: String) {
        // Watches belong to the runtime being destroyed; its replacement starts its own.
        locationWatches.clearAllWatches(toolId)
        hosts.remove(toolId)?.let { host ->
            publishedHosts.value = hosts.toMap()
            host.state = RuntimeHostState.STOPPED
            HostTrace.bestEffortSection("runtime.release") { HardenedRuntimeWebView.release(host.webView) }
        }
        stateFlow(toolId).value = RuntimeUiState.Loading
    }

    private fun updateWebViewPlayback(host: RuntimeHost) {
        val foreground = host.state == RuntimeHostState.ATTACHED && hostActivityResumed
        HardenedRuntimeWebView.setRuntimeForeground(host.webView, foreground)
        host.playback.setResumed(foreground)
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
                    refreshForegroundService()
                    throw RuntimeHandlerException(
                        RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED,
                        "系统未允许启动后台运行环境",
                    )
                }
                reminders.schedule(record)
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

        override suspend fun updateActivity(request: RuntimeBackgroundActivityRequest) =
            withContext(Dispatchers.Main.immediate) {
                ensureCurrentRuntime(toolId, versionCode)
                val current = sessionsByTool[toolId]?.get(request.sessionId)
                    ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "后台运行会话不存在")
                val updated = current.copy(
                    activityPrimaryText = request.text,
                    activitySecondaryText = request.detail?.takeIf(String::isNotBlank),
                    activityUpdatedAt = request.updatedAt ?: nowMillis(),
                )
                if (updated == current) return@withContext
                sessionsByTool.getValue(toolId)[request.sessionId] = updated
                try {
                    persistSessions(toolId)
                } catch (failure: Exception) {
                    sessionsByTool.getValue(toolId)[request.sessionId] = current
                    throw failure
                }
                refreshForegroundService()
            }

        override suspend fun setTimer(key: String, intervalMillis: Long) = withContext(Dispatchers.Main.immediate) {
            ensureCurrentRuntime(toolId, versionCode)
            if (sessionsByTool[toolId].isNullOrEmpty()) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "请先启动后台运行环境")
            }
            val expectedVersion = checkNotNull(hosts[toolId]).installedVersion
            backgroundTimers.start(toolId, key, intervalMillis, expectedVersion)
        }

        override suspend fun cancelTimer(key: String): Boolean = withContext(Dispatchers.Main.immediate) {
            backgroundTimers.cancel(toolId, key)
        }
    }

    private suspend fun stopSession(toolId: String, sessionId: String): Boolean {
        val removed = sessionsByTool[toolId]?.remove(sessionId) ?: return false
        notificationIds.release(sessionId)
        reminders.cancel(removed.sessionId)
        liveNotifications.clearSessions(listOf(sessionId))
        if (sessionsByTool[toolId].isNullOrEmpty()) {
            val cancelledRecovery = recovery.cancel(toolId)
            recoveryStatus.remove(toolId)
            backgroundTimers.cancelAll(toolId)
            locationWatches.clearBackgroundWatches(toolId)
            notifications.cancelRuntimeNotifications(listOf(sessionId))
            if (toolId !in visibleTools) {
                val closingHost = hosts[toolId]
                if (closingHost != null) scope.launch {
                    val saved = HardenedRuntimeWebView.flushBeforeClose(closingHost.webView, 2_000)
                    if (hosts[toolId] === closingHost && toolId !in visibleTools && sessionsByTool[toolId].isNullOrEmpty()) {
                        if (!saved) android.util.Log.w("RuntimeSave", "Background tool stopped without a completed save: $toolId")
                        destroyHost(toolId)
                    } else if (hosts[toolId] === closingHost) HardenedRuntimeWebView.cancelClose(closingHost.webView)
                }
            }
            // Start the close deadline before waiting for a cancelled recovery job to finish.
            cancelledRecovery?.join()
        }
        persistSessions(toolId)
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

    private fun updateSessionProjection() {
        mutableSessions.value = sessionProjection()
    }

    private fun sessionProjection(presentations: List<RuntimeLiveNotificationUi> = liveNotifications.snapshot()): List<RuntimeBackgroundSessionUi> {
        val hostNames = hosts.mapValues { it.value.runtime.toolName }
        val liveBySession = presentations.associateBy { it.request.sessionId }
        return sessionsByTool.flatMap { (toolId, sessions) ->
            sessions.values.map { record ->
                val live = liveBySession[record.sessionId]
                RuntimeBackgroundSessionUi(
                    sessionId = record.sessionId,
                    toolId = toolId,
                    toolName = hostNames[toolId] ?: restoredToolNames[toolId] ?: toolId,
                    startedAt = record.startedAt,
                    notificationId = record.notificationId,
                    statusText = recoveryStatus[toolId],
                    livePrimaryText = live?.request?.primaryText,
                    liveSecondaryText = live?.request?.secondaryText,
                    liveUpdatedAt = live?.request?.updatedAt ?: live?.receivedAt,
                    liveProgress = live?.request?.progress,
                    activityPrimaryText = record.activityPrimaryText,
                    activitySecondaryText = record.activitySecondaryText,
                    activityUpdatedAt = record.activityUpdatedAt,
                )
            }
        }.sortedBy(RuntimeBackgroundSessionUi::startedAt)
    }

    private suspend fun persistSessions(toolId: String) {
        store.persistSessions(toolId, sessionsByTool[toolId].orEmpty().values.toList())
    }

    private fun refreshForegroundService() {
        val snapshot = foregroundNotificationSnapshot()
        mutableSessions.value = snapshot.sessions
        mutableNotificationSnapshots.value = snapshot
    }

    private companion object {
        const val EVENT_BACKGROUND_RESTORE = "background.restore"
        const val RESTORE_REASON_PROCESS = "process"
        const val RESTORE_REASON_REBOOT = "reboot"
    }
}
