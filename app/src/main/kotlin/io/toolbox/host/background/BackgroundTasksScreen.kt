package io.toolbox.host.background

import io.toolbox.host.settings.SettingsIconTile
import io.toolbox.host.settings.SettingsIconTint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import io.toolbox.core.ui.component.toolBoxEntrance
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
import androidx.compose.runtime.remember
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
import io.toolbox.core.data.TaskState
import io.toolbox.core.ui.component.ToolBoxDestructiveButton
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.CatalogLazyGroupItem
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.ContentSectionHeader
import io.toolbox.host.ui.SurfaceCard
import io.toolbox.host.ui.mergePadding
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BackgroundTasksScreen(
    viewModel: BackgroundTasksViewModel,
    onBack: () -> Unit,
    onReady: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actionState by viewModel.actions.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.loaded) {
        if (state.loaded) onReady()
    }

    BackgroundTasksContent(
        page = state.page,
        message = actionState.message,
        cancellingTaskId = actionState.cancellingTaskId,
        stoppingSessionId = actionState.stoppingSessionId,
        onBack = onBack,
        resultFor = { task ->
            val resultFlow = remember(viewModel, task.taskId) { viewModel.resultSummary(task.taskId) }
            val result by resultFlow.collectAsStateWithLifecycle(null)
            result
        },
        onStopSession = { viewModel.actions.stop(it.sessionId) },
        onCancelTask = { viewModel.actions.cancel(it.taskId) },
        onRetry = if (actionState.retry != null) viewModel.actions::retry else null,
        canLoadMoreHistory = state.canLoadMoreHistory,
        loadingHistory = state.loadingHistory,
        historyError = state.historyError,
        onLoadMoreHistory = viewModel::loadMoreHistory,
    )
}

@Composable
internal fun BackgroundTasksContent(
    page: BackgroundTasksPageModel,
    message: String?,
    cancellingTaskId: String?,
    stoppingSessionId: String?,
    onBack: () -> Unit,
    resultFor: @Composable (BackgroundTask) -> TaskResultSummaryUi?,
    onStopSession: (RuntimeBackgroundSessionUi) -> Unit,
    onCancelTask: (BackgroundTask) -> Unit,
    onRetry: (() -> Unit)? = null,
    canLoadMoreHistory: Boolean = false,
    loadingHistory: Boolean = false,
    historyError: String? = null,
    onLoadMoreHistory: () -> Unit = {},
) {
    val entrance = io.toolbox.core.ui.component.rememberToolBoxEntrance("background-tasks")
    DetailScreen(title = "后台任务", onBack = onBack) { chromePadding ->
        LazyColumn(
            state = io.toolbox.host.ui.rememberTitleBoundListState(),
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
                    io.toolbox.host.ui.CatalogStatusState(
                        "没有后台任务",
                        icon = io.toolbox.core.ui.component.ToolBoxIconKey.Clock,
                        detail = "工具注册的任务会显示在这里。",
                    )
                }
            } else {
                if (page.runtimeSessions.isNotEmpty()) {
                    item("runtime-title") {
                        Column(Modifier.padding(bottom = ToolBoxThemeTokens.spacing.one)) {
                            ContentSectionHeader("持续运行 · ${page.runtimeSessions.size}")
                        }
                    }
                    itemsIndexed(
                        items = page.runtimeSessions,
                        key = { _, session -> "session:${session.sessionId}" },
                        contentType = { _, _ -> "runtime-session" },
                    ) { index, session ->
                        CatalogLazyGroupItem(index = index, count = page.runtimeSessions.size,
                            modifier = Modifier.toolBoxEntrance(entrance, index), dividerStart = EntryTextInset) {
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
                            top = if (page.runtimeSessions.isNotEmpty()) ToolBoxThemeTokens.spacing.section else 0.dp,
                            bottom = ToolBoxThemeTokens.spacing.one,
                        )) {
                            ContentSectionHeader("已登记任务 · ${page.tasks.size}")
                        }
                    }
                    itemsIndexed(
                        items = page.tasks,
                        key = { _, task -> "task:${task.taskId}" },
                        contentType = { _, _ -> "background-task" },
                    ) { index, task ->
                        CatalogLazyGroupItem(index = index, count = page.tasks.size,
                            modifier = Modifier.animateItem(
                                fadeInSpec = io.toolbox.core.ui.component.ToolBoxMotion.effects(),
                                placementSpec = io.toolbox.core.ui.component.ToolBoxMotion.ItemPlacement,
                                fadeOutSpec = io.toolbox.core.ui.component.ToolBoxMotion.effects(),
                            ).toolBoxEntrance(entrance, page.runtimeSessions.size + index + 1), dividerStart = EntryTextInset) {
                            BackgroundTaskCard(
                                task = task,
                                result = resultFor(task),
                                cancelling = cancellingTaskId == task.taskId,
                                canCancel = cancellingTaskId == null,
                                onCancel = { onCancelTask(task) },
                            )
                        }
                    }
                    if (canLoadMoreHistory) {
                        item("more-history") {
                            ToolBoxTextButton(
                                label = if (loadingHistory) "正在加载…" else "加载更多历史任务",
                                onClick = onLoadMoreHistory,
                                enabled = !loadingHistory,
                            )
                            historyError?.let { AppText(it, color = ToolBoxThemeTokens.colors.textSecondary) }
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
        icon = io.toolbox.core.ui.component.ToolBoxIconKey.Refresh,
        tint = SettingsIconTint.Aqua,
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
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.xxs))
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
    result: TaskResultSummaryUi?,
    cancelling: Boolean,
    canCancel: Boolean,
    onCancel: () -> Unit,
) {
    val cancellable = task.state == TaskState.QUEUED || task.state == TaskState.RUNNING

    BackgroundEntryLayout(
        icon = io.toolbox.core.ui.component.ToolBoxIconKey.Clock,
        tint = if (task.periodic) SettingsIconTint.Orange else SettingsIconTint.Blue,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText(task.key, Modifier.weight(1f, fill = false), textStyle = ToolBoxThemeTokens.textStyles.title)
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.xs))
            io.toolbox.core.ui.component.ToolBoxStatusChip(task.state.displayName(), task.state.tone())
        }
        AppText(
            task.operation.displayName(),
            color = ToolBoxThemeTokens.colors.textSecondary,
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

/** Separators start under the text: 16dp padding + 30dp tile + 12dp gap. */
private val EntryTextInset = 58.dp

@Composable
private fun BackgroundEntryLayout(
    icon: io.toolbox.core.ui.component.ToolBoxIconKey,
    tint: SettingsIconTint,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = ToolBoxThemeTokens.sizes.denseRow)
            .padding(horizontal = ToolBoxThemeTokens.spacing.md, vertical = ToolBoxThemeTokens.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        SettingsIconTile(icon, tint, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(ToolBoxThemeTokens.spacing.sm))
        BoxWithConstraints(Modifier.weight(1f)) {
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
}

@Composable
private fun TaskResultSummary(result: TaskResultSummaryUi?) {
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
    result.preview?.let { preview ->
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

private fun TaskState.tone() = when (this) {
    TaskState.QUEUED -> io.toolbox.core.ui.component.ToolBoxTone.Neutral
    TaskState.RUNNING -> io.toolbox.core.ui.component.ToolBoxTone.Info
    TaskState.COMPLETED -> io.toolbox.core.ui.component.ToolBoxTone.Success
    TaskState.CANCELLED -> io.toolbox.core.ui.component.ToolBoxTone.Neutral
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
