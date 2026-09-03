package io.toolbox.host.preview

import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.BackgroundOperation
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import io.toolbox.core.data.ThemeMode
import io.toolbox.host.background.BackgroundSystemState
import io.toolbox.host.background.BackgroundTasksPageModel
import io.toolbox.host.background.LiveNotificationSupportState
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.permissions.PermissionCenterUiState
import io.toolbox.host.permissions.PermissionItem
import io.toolbox.host.permissions.PermissionLoadState
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import io.toolbox.host.settings.SettingsUiState

internal object PreviewHostFixtures {
    private const val PreviewNow = 1_788_246_000_000L

    private val tools = listOf(
        tool("io.toolbox.positioncalculator", "仓位计算器", "1.0.0", 7_324L, PreviewNow - 120_000L),
        tool("io.toolbox.quicknotes", "快速笔记", "1.0.0", 6_128L, PreviewNow - 300_000L),
        tool("io.toolbox.backgroundtaskdemo", "后台任务演示", "1.0.0", 5_120L, null),
        tool("io.toolbox.notificationlab", "通知实验室", "1.0.0", 8_420L, PreviewNow),
    )

    val catalog = CatalogUiState(
        isLoaded = true,
        tools = tools,
        visibleTools = tools,
        recentTools = listOf(tools[3], tools[0], tools[1]),
    )

    val searchCatalog = catalog.copy(
        query = "通知",
        isSearching = true,
        visibleTools = listOf(tools[3]),
    )

    val permissionCenter = PermissionCenterUiState(
        toolName = "仓位计算器",
        loadState = PermissionLoadState.Ready,
        items = listOf(
            PermissionItem("storage", "工具存储", "保存计算输入与配置", true, emptyList()),
            PermissionItem("storage.secure", "安全存储", "保存敏感配置", true, emptyList()),
            PermissionItem("clipboard.write", "写入剪贴板", "复制计算结果", true, emptyList()),
            PermissionItem("haptics", "触感反馈", "计算完成时提供轻触反馈", true, emptyList()),
            PermissionItem("notifications", "通知", "显示结果与状态", false, emptyList()),
        ),
    )

    val settings = SettingsUiState(
        settings = HostSettings(theme = ThemeMode.SYSTEM, backgroundEnabled = true),
        loaded = true,
    )

    val runtimeSessions = listOf(
        RuntimeBackgroundSessionUi(
            sessionId = "preview-session",
            toolId = "io.toolbox.notificationlab",
            toolName = "通知实验室",
            startedAt = PreviewNow,
        ),
    )

    val backgroundTasks = BackgroundTasksPageModel(
        runtimeSessions = runtimeSessions,
        tasks = listOf(
            backgroundTask(
                id = "preview-http",
                key = "HTTPS 请求",
                operation = BackgroundOperation.HTTP_GET,
                state = TaskState.RUNNING,
            ),
            backgroundTask(
                id = "preview-notify",
                key = "通知样本",
                operation = BackgroundOperation.NOTIFY,
                state = TaskState.COMPLETED,
            ),
        ),
    )

    val taskResults = mapOf(
        "preview-notify" to TaskRunResult(
            taskId = "preview-notify",
            outcome = RunOutcome.SUCCEEDED,
            completedAt = PreviewNow + 60_000L,
            payloadJson = "{\"message\":\"通知已发布\"}",
            errorCode = null,
            attemptCount = 1,
        ),
    )

    val backgroundSystemState = BackgroundSystemState(
        notificationsAllowed = true,
        backgroundLocationAllowed = false,
        exactAlarmsAllowed = true,
        ignoresBatteryOptimizations = false,
    )

    val liveNotificationSupport = LiveNotificationSupportState(
        hyperOsProtocolVersion = 3,
        hyperOsSupported = true,
        hyperOsPermissionReported = true,
        androidLiveAvailable = true,
        androidLiveAllowed = true,
    )

    private fun tool(id: String, name: String, version: String, bytes: Long, lastOpenedAt: Long?) = CatalogTool(
        toolId = id,
        name = name,
        versionCode = 1,
        versionName = version,
        bundleBytes = bytes,
        lastOpenedAt = lastOpenedAt,
    )

    private fun backgroundTask(
        id: String,
        key: String,
        operation: BackgroundOperation,
        state: TaskState,
    ) = BackgroundTask(
        taskId = id,
        toolId = "io.toolbox.notificationlab",
        versionCode = 1,
        key = key,
        operation = operation,
        specJson = "{}",
        periodic = false,
        intervalMinutes = null,
        state = state,
        createdAt = PreviewNow,
        updatedAt = PreviewNow + 60_000L,
        nextRunAt = null,
        runAttempt = 1,
    )
}
