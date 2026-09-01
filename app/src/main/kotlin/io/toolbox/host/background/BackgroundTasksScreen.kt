package io.toolbox.host.background

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.BackgroundOperation
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.HostBackgroundOperations
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import io.toolbox.host.runtime.RuntimeSessionManager
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.SurfaceCard
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BackgroundTasksScreen(
    toolId: String,
    operations: HostBackgroundOperations,
    runtimeSessions: RuntimeSessionManager,
    onBack: () -> Unit,
) {
    val tasks by operations.observeTasks(toolId).collectAsStateWithLifecycle(emptyList())
    val sessions by runtimeSessions.sessions.collectAsStateWithLifecycle()
    val page = backgroundTasksPageModel(toolId, tasks, sessions)
    val scope = rememberCoroutineScope()
    var cancellingTaskId by remember { mutableStateOf<String?>(null) }
    var stoppingSessionId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    ToolBoxAppScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ToolBoxTopBar(
                title = "后台任务",
                navigationIcon = ToolBoxIconKey.Back,
                onNavigationClick = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ToolBoxThemeTokens.spacing.two,
                top = padding.calculateTopPadding() + ToolBoxThemeTokens.spacing.one,
                end = ToolBoxThemeTokens.spacing.two,
                bottom = padding.calculateBottomPadding() + ToolBoxThemeTokens.spacing.one,
            ),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            message?.let { text ->
                item("message") {
                    SurfaceCard {
                        AppText(text, color = ToolBoxThemeTokens.colors.textSecondary)
                    }
                }
            }
            if (page.isEmpty) {
                item("empty") {
                    SurfaceCard {
                        AppText("没有后台任务")
                        AppText(
                            "工具注册的任务会显示在这里。",
                            color = ToolBoxThemeTokens.colors.textSecondary,
                            textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        )
                    }
                }
            } else {
                if (page.runtimeSessions.isNotEmpty()) item("runtime-sessions") {
                    ToolBoxGroupedSurface {
                        page.runtimeSessions.forEachIndexed { index, session ->
                            RuntimeSessionCard(
                                session = session,
                                stopping = stoppingSessionId == session.sessionId,
                                onStop = {
                                    stoppingSessionId = session.sessionId
                                    message = null
                                    scope.launch {
                                        val stopped = runtimeSessions.stopSession(session.sessionId)
                                        stoppingSessionId = null
                                        message = if (stopped) "后台环境已停止。" else "后台环境已结束或不存在。"
                                    }
                                },
                            )
                            if (index != page.runtimeSessions.lastIndex) {
                                ToolBoxGroupDivider(startPadding = ToolBoxThemeTokens.spacing.oneHalf)
                            }
                        }
                    }
                }
                if (page.tasks.isNotEmpty()) item("tasks") {
                    ToolBoxGroupedSurface {
                        page.tasks.forEachIndexed { index, task ->
                            BackgroundTaskCard(
                                task = task,
                                operations = operations,
                                cancelling = cancellingTaskId == task.taskId,
                                onCancel = {
                                    cancellingTaskId = task.taskId
                                    message = null
                                    scope.launch {
                                        val cancelled = operations.cancel(toolId, task.taskId)
                                        cancellingTaskId = null
                                        if (!cancelled) {
                                            message = "任务已结束或不存在。"
                                        }
                                    }
                                }
                            )
                            if (index != page.tasks.lastIndex) ToolBoxGroupDivider(startPadding = ToolBoxThemeTokens.spacing.oneHalf)
                        }
                    }
                }
            }
        }
    }
}

internal data class BackgroundTasksPageModel(
    val tasks: List<BackgroundTask>,
    val runtimeSessions: List<RuntimeBackgroundSessionUi>,
) {
    val isEmpty: Boolean get() = tasks.isEmpty() && runtimeSessions.isEmpty()
}

internal fun backgroundTasksPageModel(
    toolId: String,
    tasks: List<BackgroundTask>,
    sessions: List<RuntimeBackgroundSessionUi>,
): BackgroundTasksPageModel = BackgroundTasksPageModel(
    tasks = tasks,
    runtimeSessions = sessions.filter { it.toolId == toolId },
)

@Composable
private fun RuntimeSessionCard(
    session: RuntimeBackgroundSessionUi,
    stopping: Boolean,
    onStop: () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ToolBoxThemeTokens.sizes.denseRow)
            .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf, vertical = ToolBoxThemeTokens.spacing.one),
    ) {
        AppText("持续运行环境", textStyle = ToolBoxThemeTokens.textStyles.title)
        AppText(
            "启动于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.startedAt))}",
            color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
        ToolBoxTextButton(
            label = if (stopping) "正在停止…" else "停止后台运行",
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            enabled = !stopping,
            contentColor = ToolBoxThemeTokens.colors.danger,
        )
    }
}

@Composable
private fun BackgroundTaskCard(
    task: BackgroundTask,
    operations: HostBackgroundOperations,
    cancelling: Boolean,
    onCancel: () -> Unit,
) {
    val result by operations.observeResult(task.taskId).collectAsStateWithLifecycle(null)
    val cancellable = task.state == TaskState.QUEUED || task.state == TaskState.RUNNING

    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ToolBoxThemeTokens.sizes.denseRow)
            .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf, vertical = ToolBoxThemeTokens.spacing.one),
    ) {
        AppText(task.key, textStyle = ToolBoxThemeTokens.textStyles.title)
        AppText(
            task.operation.displayName(),
            color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
        AppText(
            task.state.displayName(),
            color = task.state.displayColor(),
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
        if (task.periodic) {
            AppText(
                "周期任务 · 每 ${task.intervalMinutes ?: 15} 分钟",
                color = ToolBoxThemeTokens.colors.textSecondary,
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
            )
        }
        TaskResultSummary(result)
        if (cancellable) {
            ToolBoxTextButton(
                label = if (cancelling) "正在取消…" else "取消任务",
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                enabled = !cancelling,
                contentColor = ToolBoxThemeTokens.colors.danger,
            )
        }
    }
}

@Composable
private fun TaskResultSummary(result: TaskRunResult?) {
    if (result == null) {
        AppText(
            "尚无执行结果",
            color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
        return
    }

    AppText(
        "最近一次：${result.outcome.displayName()}",
        color = result.outcome.displayColor(),
        textStyle = ToolBoxThemeTokens.textStyles.metadata,
    )
    result.errorCode?.takeIf(String::isNotBlank)?.let { code ->
        AppText(
            "原因：$code",
            color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
    }
    result.payloadJson?.resultPreview()?.let { preview ->
        AppText(
            preview,
            color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
            maxLines = 3,
        )
    }
}

private fun BackgroundOperation.displayName(): String = when (this) {
    BackgroundOperation.HTTP_GET -> "网络请求"
    BackgroundOperation.NOTIFY -> "通知"
}

private fun TaskState.displayName(): String = when (this) {
    TaskState.QUEUED -> "等待运行"
    TaskState.RUNNING -> "正在运行"
    TaskState.COMPLETED -> "已完成"
    TaskState.CANCELLED -> "已取消"
}

@Composable
private fun TaskState.displayColor() = when (this) {
    TaskState.QUEUED, TaskState.RUNNING -> ToolBoxThemeTokens.colors.primary
    TaskState.COMPLETED -> ToolBoxThemeTokens.colors.success
    TaskState.CANCELLED -> ToolBoxThemeTokens.colors.textSecondary
}

private fun RunOutcome.displayName(): String = when (this) {
    RunOutcome.SUCCEEDED -> "成功"
    RunOutcome.FAILED -> "失败"
    RunOutcome.CANCELLED -> "已取消"
}

@Composable
private fun RunOutcome.displayColor() = when (this) {
    RunOutcome.SUCCEEDED -> ToolBoxThemeTokens.colors.success
    RunOutcome.FAILED -> ToolBoxThemeTokens.colors.danger
    RunOutcome.CANCELLED -> ToolBoxThemeTokens.colors.textSecondary
}

private fun String.resultPreview(): String? =
    replace(Regex("\\s+"), " ").trim().take(320).takeIf(String::isNotBlank)
