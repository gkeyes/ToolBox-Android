package io.toolbox.host.ui

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.toolbox.core.ui.component.toolBoxShape
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
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.RunningToolsUiState
import io.toolbox.host.catalog.RunningToolsViewModel
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import io.toolbox.core.ui.component.ToolBoxModalDialog
import io.toolbox.core.ui.component.toolBoxPressFeedback
import androidx.compose.animation.animateContentSize
import java.text.DateFormat
import java.util.Date
import io.toolbox.core.ui.component.toolBoxClearGlass
import io.toolbox.core.ui.component.toolBoxGlassPress
import io.toolbox.core.ui.component.toolBoxClearGlassFill
import androidx.compose.ui.graphics.compositeOver

@Composable
internal fun CatalogRunningTools(
    viewModel: RunningToolsViewModel,
    tools: List<CatalogTool>,
    onOpen: (String) -> Unit,
    uiVisible: Boolean = true,
) {
    // Never freeze a window-level stop confirmation while its session can disappear.
    val state by viewModel.state.collectAsStateWhileVisible(uiVisible) {
        it.confirmation != null
    }
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
            Column(Modifier.testTag("catalog-running-tools").padding(bottom = 20.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(
                        "正在运行",
                        modifier = Modifier.weight(1f).semantics { heading() },
                        textStyle = ToolBoxThemeTokens.textStyles.title.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
                    )
                    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "${state.sessions.size} 个" }) {
                        io.toolbox.core.ui.component.ToolBoxRollingNumber(
                            state.sessions.size,
                            ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 15.sp, lineHeight = 20.sp, color = ToolBoxThemeTokens.colors.textSecondary),
                        )
                        AppText(" 个", color = ToolBoxThemeTokens.colors.textSecondary,
                            textStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 15.sp, lineHeight = 20.sp))
                    }
                }
                // Compact capsules scroll sideways, so many sessions never push the home content down.
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .animateContentSize(io.toolbox.core.ui.component.ToolBoxMotion.spatial()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.sessions.forEach { session ->
                        key(session.sessionId) {
                            RunningToolCapsule(
                                session = session,
                                tool = toolsById[session.toolId],
                                stopping = state.stoppingSessionId == session.sessionId,
                                canStop = state.stoppingSessionId == null,
                                onOpen = { onOpen(session.toolId) },
                                onStop = { onRequestStop(session.sessionId) },
                            )
                        }
                    }
                }
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
    RunningToolsStopDialog(state, onCancelStop, onConfirmStop)
}

@Composable
internal fun RunningToolsStopDialog(state: RunningToolsUiState, onCancelStop: () -> Unit, onConfirmStop: () -> Unit) {
    if (state.confirmation == null && state.batchConfirmation.isEmpty()) return
    val title = if (state.batchConfirmation.isNotEmpty()) "停止全部后台运行？" else "停止后台运行？"
    ToolBoxModalDialog(onDismissRequest = onCancelStop, title = title) {
        HostConfirmationContent(
            title = title,
            summary = if (state.batchConfirmation.isNotEmpty()) {
                "将停止当前选择的 ${state.batchConfirmation.size} 个后台任务，并移除对应通知。"
            } else {
                state.confirmation?.let { "将停止 ${it.toolName} 的后台运行，并移除它的实时通知。其他工具不受影响。" }.orEmpty()
            },
            confirmLabel = "停止运行",
            cancelLabel = "继续运行",
            onConfirm = onConfirmStop,
            onCancel = onCancelStop,
            destructive = true,
        )
    }
}

private data class RunningToolStatusLines(
    val primary: String,
    val secondary: String? = null,
)

private fun runningToolStatusLines(
    session: RuntimeBackgroundSessionUi,
    stopping: Boolean,
): RunningToolStatusLines {
    if (stopping) return RunningToolStatusLines("正在停止后台会话")

    session.statusText?.takeIf(String::isNotBlank)?.let { text ->
        val parts = text.split(" · ")
            .map(String::trim)
            .filter(String::isNotEmpty)
        return if (parts.size > 2) {
            RunningToolStatusLines(
                primary = parts.take(2).joinToString(" · "),
                secondary = parts.drop(2).joinToString(" · "),
            )
        } else {
            RunningToolStatusLines(text.trim())
        }
    }

    session.activityPrimaryText?.takeIf(String::isNotBlank)?.let { primary ->
        return RunningToolStatusLines(
            primary = primary,
            secondary = session.activitySecondaryText?.takeIf(String::isNotBlank),
        )
    }

    val detailedLiveProgress = session.liveProgress != null
    val refreshedAt = session.liveUpdatedAt?.let { value ->
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(value))
    }
    if (detailedLiveProgress && refreshedAt != null) {
        return RunningToolStatusLines("$refreshedAt 已刷新")
    }

    session.livePrimaryText?.takeIf(String::isNotBlank)?.let { primary ->
        return RunningToolStatusLines(
            primary = primary,
            secondary = session.liveSecondaryText?.takeIf {
                !detailedLiveProgress && it.isNotBlank()
            },
        )
    }

    return RunningToolStatusLines("运行中")
}

/**
 * Grouped-list row for a running session (iOS 27): the tool tile with a live dot, name and status
 * lines, and a round stop button. Hairline separators are drawn by the surrounding list.
 * [dangerStop] tints the stop button red, as on the safeguards page.
 */
@Composable
internal fun RunningToolRow(
    session: RuntimeBackgroundSessionUi,
    tool: CatalogTool?,
    stopping: Boolean,
    canStop: Boolean,
    onOpen: (() -> Unit)?,
    onStop: () -> Unit,
    dangerStop: Boolean = false,
) {
    val colors = ToolBoxThemeTokens.colors
    val visual = tool?.visual(colors.primary) ?: toolVisual(session.toolId, session.toolName, colors.primary)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().testTag("catalog-running-${session.sessionId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ToolBoxThemeTokens.sizes.denseRow)
                .then(if (onOpen != null) Modifier
                    .toolBoxPressFeedback(interaction, scale = io.toolbox.core.ui.component.ToolBoxMotion.RowPressScale)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = "打开${session.toolName}" } else Modifier)
                .padding(start = ToolBoxThemeTokens.spacing.md, end = ToolBoxThemeTokens.spacing.xs, top = ToolBoxThemeTokens.spacing.sm, bottom = ToolBoxThemeTokens.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RunningGlyphWithDot(session, tool, visual, stopping, size = 46.dp, ring = colors.surface)
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.sm))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AppText(
                    text = session.toolName,
                    textStyle = ToolBoxThemeTokens.textStyles.headline,
                    maxLines = 1,
                )
                val status = runningToolStatusLines(session, stopping)
                AppText(
                    text = status.primary,
                    color = if (stopping) colors.textSecondary else colors.onSoftSuccess,
                    textStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    maxLines = 1,
                )
                status.secondary?.let { secondary ->
                    AppText(
                        text = secondary,
                        color = colors.textSecondary,
                        textStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 13.sp, lineHeight = 17.sp),
                        maxLines = 1,
                    )
                }
            }
        }
        RunningToolStopButton(
            toolName = session.toolName,
            stopping = stopping,
            canStop = canStop,
            onStop = onStop,
            discSize = 38.dp,
            modifier = Modifier.padding(end = 6.dp),
            danger = dangerStop,
        )
    }
}

/** Tool tile with a green live dot (grey while stopping), ringed in the surface colour. */
@Composable
private fun RunningGlyphWithDot(
    session: RuntimeBackgroundSessionUi,
    tool: CatalogTool?,
    visual: ToolVisual,
    stopping: Boolean,
    size: androidx.compose.ui.unit.Dp,
    ring: Color,
) {
    val colors = ToolBoxThemeTokens.colors
    Box {
        CatalogToolGlyph(
            toolId = session.toolId,
            versionCode = tool?.versionCode,
            visual = visual,
            size = size,
        )
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(12.dp)
                .clip(CircleShape).background(ring).padding(2.dp)
                .clip(CircleShape).background(if (stopping) colors.textTertiary else Color(0xFF34C759)),
        )
    }
}

/** Round neutral stop button (48dp touch target) with a rounded-square stop glyph. */
@Composable
private fun RunningToolStopButton(
    toolName: String,
    stopping: Boolean,
    canStop: Boolean,
    onStop: () -> Unit,
    discSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val colors = ToolBoxThemeTokens.colors
    val dark = colors.surface.luminance() < 0.5f
    val stopInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier.size(48.dp)
            .clickable(enabled = canStop, interactionSource = stopInteraction, indication = null, role = Role.Button, onClick = onStop)
            .semantics {
                contentDescription = "停止${toolName}后台运行"
                stateDescription = if (stopping) "停止中" else "运行中"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(discSize)
                .toolBoxGlassPress(stopInteraction, enabled = canStop)
                .toolBoxClearGlass(
                    CircleShape,
                    tint = if (stopping || danger) colors.danger.copy(alpha = if (dark) 0.18f else 0.10f).compositeOver(ToolBoxThemeTokens.materials.glassTint)
                    else toolBoxClearGlassFill(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            val glyph = when {
                !canStop -> colors.textTertiary
                danger -> colors.danger
                else -> colors.onSoftSuccess
            }
            if (stopping) io.toolbox.core.ui.component.ToolBoxBusyIndicator()
            else Box(Modifier.size(12.dp).clip(toolBoxShape(3.dp)).background(glyph))
        }
    }
}

/** Home "正在运行" capsule: icon with a live dot, name and status, and a separate stop button. */
@Composable
internal fun RunningToolCapsule(
    session: RuntimeBackgroundSessionUi,
    tool: CatalogTool?,
    stopping: Boolean,
    canStop: Boolean,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val visual = tool?.visual(colors.primary) ?: ToolVisual(ToolBoxIconKey.Tools, colors.primary)
    val status = runningToolStatusLines(session, stopping)
    val openInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val stopInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val capsule = toolBoxShape(ToolBoxThemeTokens.radii.full)
    Row(
        Modifier.testTag("catalog-running-${session.sessionId}")
            // Grows with large text so the two-line name and status are never cut off.
            .heightIn(min = 52.dp)
            .height(IntrinsicSize.Min)
            .toolBoxClearGlass(capsule, tint = ToolBoxThemeTokens.materials.glassTint.compositeOver(colors.surface.copy(alpha = 0.7f)))
            .padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxHeight()
                .toolBoxGlassPress(openInteraction, shape = capsule)
                .clickable(interactionSource = openInteraction, indication = null, role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = "打开${session.toolName}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                CatalogToolGlyph(
                    toolId = session.toolId,
                    versionCode = tool?.versionCode,
                    visual = visual,
                    size = 40.dp,
                )
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(12.dp)
                        .clip(CircleShape).background(colors.surface).padding(2.dp)
                        .clip(CircleShape).background(if (stopping) colors.textTertiary else Color(0xFF34C759)),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.widthIn(max = 132.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                AppText(
                    text = session.toolName,
                    maxLines = 1,
                    textStyle = ToolBoxThemeTokens.textStyles.title.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
                )
                AppText(
                    text = status.primary,
                    maxLines = 1,
                    color = colors.textSecondary,
                    textStyle = ToolBoxThemeTokens.textStyles.label.copy(fontSize = 12.sp, lineHeight = 15.sp),
                )
            }
        }
        Box(
            Modifier.size(48.dp)
                .clickable(enabled = canStop, interactionSource = stopInteraction, indication = null, role = Role.Button, onClick = onStop)
                .semantics {
                    contentDescription = "停止${session.toolName}后台运行"
                    stateDescription = if (stopping) "停止中" else "运行中"
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(32.dp)
                    .toolBoxGlassPress(stopInteraction, enabled = canStop)
                    .toolBoxClearGlass(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (stopping) io.toolbox.core.ui.component.ToolBoxBusyIndicator()
                else Box(Modifier.size(11.dp).clip(toolBoxShape(3.dp)).background(if (canStop) colors.textSecondary else colors.textTertiary))
            }
        }
    }
}

