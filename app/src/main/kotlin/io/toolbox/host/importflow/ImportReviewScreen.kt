package io.toolbox.host.importflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxRiskLevel
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
fun ImportReviewScreen(
    viewModel: ImportReviewViewModel,
    onBack: () -> Unit,
    onPickerRequest: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    state.pickerRequestToken?.let { token ->
        LaunchedEffect(token) {
            onPickerRequest(token)
            viewModel.markPickerLaunched(token)
        }
    }
    state.exitRequestToken?.let { token ->
        LaunchedEffect(token) {
            onBack()
            viewModel.markExitHandled(token)
        }
    }
    ImportReviewScreen(
        state = state,
        onBack = viewModel::cancelAndExit,
        onPick = viewModel::requestPicker,
        onRecover = viewModel::recoverColdStart,
        onResume = viewModel::resume,
        onGrantChanged = viewModel::setPermissionGrant,
        onConfirmReview = viewModel::confirmReview,
        onInstall = viewModel::install,
        onDismissError = viewModel::dismissError,
        modifier = modifier,
    )
}

@Composable
fun ImportReviewScreen(
    state: ImportReviewUiState,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onRecover: () -> Unit,
    onResume: (String) -> Unit,
    onGrantChanged: (String, ImportGrantChoice) -> Unit,
    onConfirmReview: () -> Unit,
    onInstall: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolBoxAppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ToolBoxTopBar(
                title = "导入工具",
                subtitle = state.selectedName.orEmpty(),
                navigationIcon = ToolBoxIconKey.Back,
                onNavigationClick = onBack,
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentPadding = PaddingValues(
                horizontal = ToolBoxThemeTokens.spacing.two,
                vertical = ToolBoxThemeTokens.spacing.row,
            ),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            state.error?.let { error ->
                item(key = "error") {
                    MessageCard(
                        title = if (state.cancelRetryAvailable) "清理未完成，可重试取消" else "操作未完成",
                        message = error.userMessage,
                        level = ToolBoxRiskLevel.Blocked,
                        actionLabel = "知道了",
                        onAction = onDismissError,
                    )
                }
            }
            state.message?.let { message ->
                item(key = "message") {
                    MessageCard(title = "恢复状态", message = message, level = ToolBoxRiskLevel.Medium)
                }
            }
            when (state.phase) {
                ImportReviewPhase.IDLE -> idleContent(state, onPick, onRecover, onResume)
                ImportReviewPhase.PICKING -> progressCard("等待选择工具包", "请在系统文件选择器中选择 .tbx，或取消返回。")
                ImportReviewPhase.RECOVERING -> progressCard("正在恢复安装目录", "只有目录事务收敛后才会读取待审核会话。")
                ImportReviewPhase.INSPECTING -> progressCard("正在检查工具包", "正在私有目录中校验结构、清单、完整性与签名。")
                ImportReviewPhase.REVIEW -> state.review?.let { review ->
                    reviewContent(state, review, onGrantChanged, onConfirmReview, onInstall, onBack)
                }
                ImportReviewPhase.CANCELLING -> progressCard("正在安全取消", "清理完成后才会离开此页面。")
                ImportReviewPhase.INSTALLING -> progressCard("正在原子安装", "正在提交版本目录与本机工具目录。")
                ImportReviewPhase.INSTALLED -> installedContent(state, onBack)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.idleContent(
    state: ImportReviewUiState,
    onPick: () -> Unit,
    onRecover: () -> Unit,
    onResume: (String) -> Unit,
) {
    item(key = "select") {
        MessageCard(
            title = "选择 .tbx 工具包",
            message = "文件只读取一次并复制到应用私有会话；不会保存外部文件 URI。",
            level = ToolBoxRiskLevel.Low,
            actionLabel = "选择工具包",
            onAction = onPick,
        )
    }
    if (state.recoverySessions.isEmpty()) {
        item(key = "recover") {
            CompactActionCard(
                title = "继续中断的审核",
                summary = "先恢复安装事务，再扫描私有审核会话。",
                actionLabel = "检查恢复状态",
                onClick = onRecover,
            )
        }
    } else {
        items(state.recoverySessions, key = RecoveredInspection::sessionId) { session ->
            CompactActionCard(
                title = session.toolName,
                summary = session.sourceName,
                actionLabel = "继续审核",
                onClick = { onResume(session.sessionId) },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.progressCard(title: String, message: String) {
    item(key = title) { MessageCard(title, message, ToolBoxRiskLevel.Low) }
}
