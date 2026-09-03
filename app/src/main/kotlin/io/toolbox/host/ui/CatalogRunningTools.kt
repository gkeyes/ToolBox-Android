package io.toolbox.host.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxRunningStatusButton
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.RunningToolsUiState
import io.toolbox.host.catalog.RunningToolsViewModel
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun CatalogRunningTools(
    viewModel: RunningToolsViewModel,
    tools: List<CatalogTool>,
    onOpen: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CatalogRunningToolsContent(
        state = state,
        tools = tools,
        onOpen = onOpen,
        onRequestStop = viewModel::requestStop,
        onCancelStop = viewModel::cancelStop,
        onConfirmStop = viewModel::confirmStop,
        onDismissFeedback = viewModel::dismissFeedback,
    )
}

@Composable
internal fun CatalogRunningToolsContent(
    state: RunningToolsUiState,
    tools: List<CatalogTool>,
    onOpen: (String) -> Unit,
    onRequestStop: (String) -> Unit,
    onCancelStop: () -> Unit,
    onConfirmStop: () -> Unit,
    onDismissFeedback: () -> Unit,
) {
    val toolsById = remember(tools) { tools.associateBy(CatalogTool::toolId) }
    Column {
        if (state.sessions.isNotEmpty()) {
            Column(Modifier.testTag("catalog-running-tools")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { SectionHeader("正在运行") }
                    AppText(
                        "${state.sessions.size} 个",
                        color = ToolBoxThemeTokens.colors.textSecondary,
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                    )
                }
                Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one))
                ToolBoxGroupedSurface {
                    state.sessions.forEachIndexed { index, session ->
                        key(session.sessionId) {
                            RunningToolRow(
                                session = session,
                                tool = toolsById[session.toolId],
                                stopping = state.stoppingSessionId == session.sessionId,
                                canStop = state.stoppingSessionId == null,
                                onOpen = { onOpen(session.toolId) },
                                onStop = { onRequestStop(session.sessionId) },
                            )
                            if (index != state.sessions.lastIndex) ToolBoxGroupDivider()
                        }
                    }
                }
                Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two))
            }
        }
        state.feedback?.let { feedback ->
            FeedbackSurface(
                message = feedback.message,
                tone = FeedbackTone.Error,
                dismissible = true,
                onDismiss = onDismissFeedback,
                modifier = Modifier.padding(bottom = ToolBoxThemeTokens.spacing.oneHalf),
            )
        }
    }
    OverlayDialog(
        show = state.confirmation != null,
        title = "停止后台运行？",
        summary = state.confirmation?.let {
            "将停止 ${it.toolName} 的后台运行，并移除它的实时通知。其他工具不受影响。"
        },
        onDismissRequest = onCancelStop,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            ToolBoxTextButton(
                label = "继续运行",
                onClick = onCancelStop,
                modifier = Modifier.weight(1f),
                contentColor = ToolBoxThemeTokens.colors.textPrimary,
            )
            ToolBoxPrimaryButton(
                label = "停止运行",
                onClick = onConfirmStop,
                modifier = Modifier.weight(1f),
                destructive = true,
            )
        }
    }
}

@Composable
private fun RunningToolRow(
    session: RuntimeBackgroundSessionUi,
    tool: CatalogTool?,
    stopping: Boolean,
    canStop: Boolean,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val visual = tool?.visual(colors.primary) ?: ToolVisual(ToolBoxIconKey.Tools, colors.primary)
    Row(
        modifier = Modifier.fillMaxWidth().testTag("catalog-running-${session.sessionId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ToolBoxThemeTokens.sizes.denseRow + ToolBoxThemeTokens.spacing.one)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = "打开${session.toolName}" }
                .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf, vertical = ToolBoxThemeTokens.spacing.one),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatalogToolGlyph(
                toolId = session.toolId,
                versionCode = tool?.versionCode,
                visual = visual,
                size = ToolBoxThemeTokens.sizes.compactToolGlyph,
            )
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
            AppText(
                text = session.toolName,
                modifier = Modifier.weight(1f),
                textStyle = ToolBoxThemeTokens.textStyles.body,
            )
        }
        ToolBoxRunningStatusButton(
            stopping = stopping,
            enabled = canStop,
            onClick = onStop,
            modifier = Modifier
                .padding(end = ToolBoxThemeTokens.spacing.oneHalf)
                .semantics {
                    contentDescription = "停止${session.toolName}后台运行"
                    stateDescription = if (stopping) "停止中" else "运行中"
                },
        )
    }
}
