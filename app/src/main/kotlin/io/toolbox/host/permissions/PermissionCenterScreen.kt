package io.toolbox.host.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.GrantState
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxRiskBadge
import io.toolbox.core.ui.component.ToolBoxRiskLevel
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
fun PermissionCenterScreen(
    viewModel: PermissionCenterViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PermissionCenterScreen(
        state = state,
        onBack = onBack,
        onRevoke = viewModel::revoke,
        onExplainRuntimeGranting = viewModel::explainRuntimeGranting,
        onDismissFeedback = viewModel::dismissFeedback,
        modifier = modifier,
    )
}

@Composable
fun PermissionCenterScreen(
    state: PermissionCenterUiState,
    onBack: () -> Unit,
    onRevoke: (String) -> Unit,
    onExplainRuntimeGranting: () -> Unit,
    onDismissFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolBoxAppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ToolBoxTopBar(
                title = "权限中心",
                subtitle = state.toolId,
                navigationIcon = ToolBoxIconKey.Back,
                onNavigationClick = onBack,
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(
                horizontal = ToolBoxThemeTokens.spacing.two,
                vertical = ToolBoxThemeTokens.spacing.oneHalf,
            ),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.row),
        ) {
            state.feedback?.let { feedback ->
                item(key = "feedback") {
                    FeedbackCard(feedback = feedback, onDismiss = onDismissFeedback)
                }
            }
            item(key = "scope") {
                FactCard(
                    title = "已安装的权限记录",
                    message = "这里只显示安装审核已写入的记录。记录不是运行时放行证明；实际调用仍需通过声明、授权、系统权限和安全策略。",
                )
            }
            if (!state.isLoaded) {
                item(key = "loading") {
                    FactCard(title = "正在读取", message = "正在读取该工具的已安装权限记录。")
                }
            } else if (state.grants.isEmpty()) {
                item(key = "empty") {
                    FactCard(title = "没有可管理的权限", message = "此工具没有已安装的权限记录。新增授权只能在运行时按已声明能力确认。")
                }
            } else {
                items(state.grants.size, key = { index -> state.grants[index].permission }) { index ->
                    PermissionGrantCard(item = state.grants[index], onRevoke = onRevoke)
                }
            }
            item(key = "runtime") {
                RuntimeGrantCard(onClick = onExplainRuntimeGranting)
            }
        }
    }
}

@Composable
private fun PermissionGrantCard(
    item: PermissionGrantItem,
    onRevoke: (String) -> Unit,
) {
    ToolBoxCard(
        contentPadding = PaddingValues(
            horizontal = ToolBoxThemeTokens.spacing.card,
            vertical = ToolBoxThemeTokens.spacing.oneHalf,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolBoxIcon(
                icon = item.icon,
                contentDescription = null,
                modifier = Modifier.padding(ToolBoxThemeTokens.spacing.one),
                tint = ToolBoxThemeTokens.colors.primary,
            )
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.micro),
            ) {
                ToolBoxText(
                    text = item.title,
                    style = ToolBoxThemeTokens.textStyles.body.copy(
                        color = ToolBoxThemeTokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                ToolBoxText(
                    text = item.details,
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
                )
            }
            ToolBoxRiskBadge(level = item.riskLevel, label = item.stateLabel)
        }
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one))
        ToolBoxPrimaryButton(
            label = "撤销此权限",
            onClick = { onRevoke(item.permission) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RuntimeGrantCard(onClick: () -> Unit) {
    ToolBoxCard(contentPadding = PaddingValues(ToolBoxThemeTokens.spacing.card)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolBoxIcon(ToolBoxIconKey.Shield, contentDescription = null, tint = ToolBoxThemeTokens.colors.primary)
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.row))
            Column(modifier = Modifier.weight(1f)) {
                ToolBoxText(
                    text = "新增授权",
                    style = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textPrimary),
                )
                ToolBoxText(
                    text = "运行时请求时确认，当前未接入。此页不能新增或授权权限。",
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
                )
            }
        }
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.row))
        ToolBoxPrimaryButton(label = "了解授权方式", onClick = onClick, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FactCard(title: String, message: String) {
    ToolBoxCard(contentPadding = PaddingValues(ToolBoxThemeTokens.spacing.card)) {
        ToolBoxText(
            text = title,
            style = ToolBoxThemeTokens.textStyles.body.copy(
                color = ToolBoxThemeTokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.half))
        ToolBoxText(
            text = message,
            style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
        )
    }
}

@Composable
private fun FeedbackCard(feedback: PermissionCenterFeedback, onDismiss: () -> Unit) {
    ToolBoxCard(contentPadding = PaddingValues(ToolBoxThemeTokens.spacing.card)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolBoxIcon(
                icon = if (feedback is PermissionCenterFeedback.Revoked) ToolBoxIconKey.Shield else ToolBoxIconKey.Lock,
                contentDescription = null,
                tint = feedback.color,
            )
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
            ToolBoxText(
                text = feedback.message,
                modifier = Modifier.weight(1f),
                style = ToolBoxThemeTokens.textStyles.metadata.copy(color = feedback.color),
            )
        }
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one))
        ToolBoxPrimaryButton(label = "知道了", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

private val PermissionGrantItem.icon: ToolBoxIconKey
    get() = when (permission) {
        "storage", "storage.secure", "files.open", "files.save" -> ToolBoxIconKey.Folder
        "clipboard.read", "clipboard.write" -> ToolBoxIconKey.Clipboard
        "network" -> ToolBoxIconKey.Globe
        else -> ToolBoxIconKey.Shield
    }

private val PermissionGrantItem.riskLevel: ToolBoxRiskLevel
    get() = when (state) {
        GrantState.GRANTED -> ToolBoxRiskLevel.Low
        GrantState.DENIED -> ToolBoxRiskLevel.Medium
        GrantState.BLOCKED -> ToolBoxRiskLevel.Blocked
    }

private val PermissionGrantItem.stateLabel: String
    get() = when (state) {
        GrantState.GRANTED -> "已允许"
        GrantState.DENIED -> "已拒绝"
        GrantState.BLOCKED -> "已阻止"
    }

private val PermissionCenterFeedback.color
    @Composable get() = when (this) {
        is PermissionCenterFeedback.Revoked -> ToolBoxThemeTokens.colors.success
        is PermissionCenterFeedback.NotDeclared,
        is PermissionCenterFeedback.RevokeFailed -> ToolBoxThemeTokens.colors.danger
        PermissionCenterFeedback.RuntimeConfirmationRequired -> ToolBoxThemeTokens.colors.warning
    }
