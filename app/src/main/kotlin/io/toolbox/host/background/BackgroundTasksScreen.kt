package io.toolbox.host.background

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.BackgroundOperation
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import io.toolbox.core.ui.component.ToolBoxDestructiveButton
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.HostBackgroundOperations
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import io.toolbox.host.runtime.RuntimeSessionManager
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.CatalogLazyGroupItem
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.SectionHeader
import io.toolbox.host.ui.SurfaceCard
import io.toolbox.host.ui.mergePadding
import kotlinx.coroutines.flow.onEach
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BackgroundTasksScreen(
    toolId: String,
    operations: HostBackgroundOperations,
    runtimeSessions: RuntimeSessionManager,
    onBack: () -> Unit,
    onReady: () -> Unit = {},
) {
    var tasksLoaded by remember(toolId, operations) { mutableStateOf(false) }
    val tasksFlow = remember(toolId, operations) {
        operations.observeTasks(toolId).onEach { tasksLoaded = true }
    }
    val tasks by tasksFlow.collectAsStateWithLifecycle(emptyList())
    val sessions by runtimeSessions.sessions.collectAsStateWithLifecycle()
    val page = backgroundTasksPageModel(toolId, tasks, sessions)
    val scope = rememberCoroutineScope()
    val actions = remember(toolId, operations, runtimeSessions, scope) {
        BackgroundTaskActions(scope, { operations.cancel(toolId, it) }, runtimeSessions::stopSession)
    }
    val actionState by actions.state.collectAsStateWithLifecycle()

    LaunchedEffect(tasksLoaded) {
        if (tasksLoaded) onReady()
    }

    BackgroundTasksContent(
        page = page,
        message = actionState.message,
        cancellingTaskId = actionState.cancellingTaskId,
        stoppingSessionId = actionState.stoppingSessionId,
        onBack = onBack,
        resultFor = { task ->
            val resultFlow = remember(operations, task.taskId) { operations.observeResult(task.taskId) }
            val result by resultFlow.collectAsStateWithLifecycle(null)
            result
        },
        onStopSession = { actions.stop(it.sessionId) },
        onCancelTask = { actions.cancel(it.taskId) },
        onRetry = if (actionState.retry != null) actions::retry else null,
    )
}

@Composable
internal fun BackgroundTasksContent(
    page: BackgroundTasksPageModel,
    message: String?,
    cancellingTaskId: String?,
    stoppingSessionId: String?,
    onBack: () -> Unit,
    resultFor: @Composable (BackgroundTask) -> TaskRunResult?,
    onStopSession: (RuntimeBackgroundSessionUi) -> Unit,
    onCancelTask: (BackgroundTask) -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    DetailScreen(title = "后台任务", onBack = onBack) { chromePadding ->
        LazyColumn(
            modifier = Modifier
                .widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
                .fillMaxWidth()
                .fillMaxSize()
                .align(Alignment.TopCenter),
            contentPadding = mergePadding(
                chromePadding,
                PaddingValues(ToolBoxThemeTokens.spacing.two),
            ),
        ) {
            message?.let { text ->
                item("message") {
                    SurfaceCard(Modifier.padding(bottom = ToolBoxThemeTokens.spacing.one)) {
                        AppText(text, color = ToolBoxThemeTokens.colors.textSecondary,
                            modifier = Modifier.semantics {
                                if (cancellingTaskId == null && stoppingSessionId == null) liveRegion = LiveRegionMode.Polite
                            },
                        )
                        if (onRetry != null) {
                            ToolBoxTextButton("重试", onRetry, enabled = cancellingTaskId == null && stoppingSessionId == null)
                        }
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
                if (page.runtimeSessions.isNotEmpty()) {
                    item("runtime-title") {
                        Column(Modifier.padding(bottom = ToolBoxThemeTokens.spacing.one)) {
                            SectionHeader("持续运行 · ${page.runtimeSessions.size}")
                        }
                    }
                    itemsIndexed(
                        items = page.runtimeSessions,
                        key = { _, session -> "session:${session.sessionId}" },
                        contentType = { _, _ -> "runtime-session" },
                    ) { index, session ->
                        CatalogLazyGroupItem(index = index, count = page.runtimeSessions.size) {
                            RuntimeSessionCard(
                                session = session,
                                stopping = stoppingSessionId == session.sessionId,
                                canStop = stoppingSessionId == null,
                                onStop = { onStopSession(session) },
                            )
                        }
                    }
                }
                if (page.tasks.isNotEmpty()) {
                    item("tasks-title") {
                        Column(Modifier.padding(
                            top = if (page.runtimeSessions.isNotEmpty()) ToolBoxThemeTokens.spacing.two else 0.dp,
                            bottom = ToolBoxThemeTokens.spacing.one,
                        )) {
                            SectionHeader("已登记任务 · ${page.tasks.size}")
                        }
                    }
                    itemsIndexed(
                        items = page.tasks,
                        key = { _, task -> "task:${task.taskId}" },
                        contentType = { _, _ -> "background-task" },
                    ) { index, task ->
                        CatalogLazyGroupItem(index = index, count = page.tasks.size) {
                            BackgroundTaskCard(
                                task = task,
                                result = resultFor(task),
                                cancelling = cancellingTaskId == task.taskId,
                                canCancel = cancellingTaskId == null,
                                onCancel = { onCancelTask(task) },
                            )
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
    canStop: Boolean,
    onStop: () -> Unit,
) {
    BackgroundEntryLayout(
        modifier = Modifier.testTag("background_session:${session.sessionId}"),
        action = {
            ToolBoxDestructiveButton(
                label = if (stopping) "正在停止…" else "停止后台运行",
                onClick = onStop,
                modifier = Modifier.testTag("background_stop:${session.sessionId}").semantics {
                    if (stopping) stateDescription = "正在停止"
                },
                enabled = canStop,
            )
        },
    ) {
        AppText("持续运行环境", textStyle = ToolBoxThemeTokens.textStyles.title)
        AppText(
            "启动于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.startedAt))}",
            color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
    }
}

@Composable
private fun BackgroundTaskCard(
    task: BackgroundTask,
    result: TaskRunResult?,
    cancelling: Boolean,
    canCancel: Boolean,
    onCancel: () -> Unit,
) {
    val cancellable = task.state == TaskState.QUEUED || task.state == TaskState.RUNNING

    BackgroundEntryLayout(
        modifier = Modifier.testTag("background_task:${task.taskId}"),
        action = if (cancellable) ({
            ToolBoxDestructiveButton(
                label = if (cancelling) "正在取消…" else "取消任务",
                onClick = onCancel,
                modifier = Modifier.testTag("background_cancel:${task.taskId}").semantics {
                    if (cancelling) stateDescription = "正在取消"
                },
                enabled = canCancel,
            )
        }) else null,
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
    }
}

@Composable
private fun BackgroundEntryLayout(
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    BoxWithConstraints(
        modifier.fillMaxWidth()
            .heightIn(min = ToolBoxThemeTokens.sizes.denseRow)
            .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf, vertical = ToolBoxThemeTokens.spacing.one),
    ) {
        if (action == null) {
            Column(Modifier.fillMaxWidth(), content = content)
        } else if (maxWidth < 320.dp || largeText) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one)) {
                Column(Modifier.fillMaxWidth(), content = content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.oneHalf),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), content = content)
                action()
            }
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
