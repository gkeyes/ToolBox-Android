package io.toolbox.host.importflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxRiskLevel
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.tool.packagekit.SignatureState

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
    val fixedActionReview = state.review?.takeIf { state.phase == ImportReviewPhase.REVIEW }
    val bottomBar: (@Composable () -> Unit)? = fixedActionReview?.let { review ->
        {
            ImportReviewActionBar(
                state = state,
                review = review,
                onConfirmReview = onConfirmReview,
                onInstall = onInstall,
                onCancel = onBack,
            )
        }
    }
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
        bottomBar = bottomBar,
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
                    reviewContent(state, review, onGrantChanged)
                }
                ImportReviewPhase.CANCELLING -> progressCard("正在安全取消", "清理完成后才会离开此页面。")
                ImportReviewPhase.INSTALLING -> progressCard("正在原子安装", "正在提交版本目录与本机工具目录。")
                ImportReviewPhase.INSTALLED -> installedContent(state, onBack)
            }
        }
    }
}

@Composable
private fun ImportReviewActionBar(
    state: ImportReviewUiState,
    review: ImportReviewFacts,
    onConfirmReview: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val awaitingConfirmation = !state.reviewConfirmed
    val primaryLabel = when {
        awaitingConfirmation -> "确认审核"
        !review.installable -> "禁止安装"
        review.signature.state == SignatureState.UNSIGNED -> "继续安装未签名工具"
        else -> "安装并授权"
    }
    val summary = when {
        !state.hasValidGrantPlan -> "请先允许所有必需权限。"
        awaitingConfirmation -> "确认身份、风险与逐项权限后再安装。"
        !review.installable -> "此工具存在阻断项，不能安装。"
        else -> "审核已确认，可以提交原子安装。"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolBoxThemeTokens.colors.surface)
            .padding(horizontal = ToolBoxThemeTokens.spacing.two, vertical = ToolBoxThemeTokens.spacing.one),
    ) {
        ToolBoxText(
            text = summary,
            style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
            maxLines = 1,
        )
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.half))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            ToolBoxCard(
                modifier = Modifier.weight(1f),
                onClick = onCancel,
                contentPadding = PaddingValues(
                    horizontal = ToolBoxThemeTokens.spacing.one,
                    vertical = ToolBoxThemeTokens.spacing.oneHalf,
                ),
            ) {
                ToolBoxText(
                    text = if (state.cancelRetryAvailable) "重试取消" else "取消导入",
                    modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally),
                    style = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textPrimary),
                    maxLines = 1,
                )
            }
            ToolBoxPrimaryButton(
                label = primaryLabel,
                onClick = if (awaitingConfirmation) onConfirmReview else onInstall,
                modifier = Modifier.weight(2f),
                enabled = if (awaitingConfirmation) state.hasValidGrantPlan else state.canInstall,
            )
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
