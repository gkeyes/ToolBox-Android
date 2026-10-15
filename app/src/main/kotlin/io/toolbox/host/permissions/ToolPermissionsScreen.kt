package io.toolbox.host.permissions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.core.ui.component.toolBoxEntrance
import io.toolbox.core.ui.component.toolBoxPressFeedback
import io.toolbox.host.ui.CatalogLazyGroupItem
import io.toolbox.host.ui.CatalogToolGlyph
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.SectionHeader
import io.toolbox.host.ui.ToolVisual
import io.toolbox.host.ui.mergePadding
import kotlinx.coroutines.flow.Flow

@Composable
internal fun ToolPermissionsScreen(
    viewModel: ToolPermissionsViewModel,
    onBack: () -> Unit,
    onSelectTool: (String) -> Unit,
    onReady: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.toolsLoaded) { if (state.toolsLoaded) onReady() }
    ToolPermissionsContent(
        tools = state.tools,
        toolsLoaded = state.toolsLoaded,
        summaryFor = viewModel::summary,
        onBack = onBack,
        onSelectTool = onSelectTool,
    )
}

@Composable
internal fun ToolPermissionsContent(
    tools: List<InstalledTool>,
    toolsLoaded: Boolean,
    summaryFor: (InstalledTool) -> Flow<ToolPermissionSummary>,
    onBack: () -> Unit,
    onSelectTool: (String) -> Unit,
) {
    val entrance = io.toolbox.core.ui.component.rememberToolBoxEntrance("tool-permissions")
    val context = androidx.compose.ui.platform.LocalContext.current
    // Re-read Android's permission state on every return, e.g. after revoking one in system settings.
    var systemGranted by remember { mutableStateOf<(String) -> Boolean>({ true }) }
    var exactAlarms by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(context) {
        systemGranted = { permission -> context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        exactAlarms = runCatching {
            context.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() != false
        }.getOrDefault(true)
        onPauseOrDispose { }
    }
    DetailScreen(title = "工具权限", onBack = onBack) { chromePadding ->
        LazyColumn(
            state = io.toolbox.host.ui.rememberTitleBoundListState(),
            modifier = Modifier.widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
                .fillMaxWidth().fillMaxSize().align(Alignment.TopCenter),
            contentPadding = mergePadding(chromePadding, PaddingValues(ToolBoxThemeTokens.spacing.two)),
        ) {
            if (tools.isEmpty()) {
                item("empty") {
                    io.toolbox.host.ui.CatalogStatusState(
                        if (toolsLoaded) "尚未安装工具" else "正在读取工具",
                        icon = ToolBoxIconKey.Shield.takeIf { toolsLoaded },
                        detail = "安装的工具及其授权会显示在这里。".takeIf { toolsLoaded },
                        loading = !toolsLoaded,
                    )
                }
            } else {
                item("installed-title") {
                    Column(Modifier.toolBoxEntrance(entrance, 0).padding(bottom = ToolBoxThemeTokens.spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.xxs)) {
                        SectionHeader("已安装工具 · ${tools.size}")
                        ToolBoxText("摘要已计入系统设置里关闭的权限；点工具可查看和处理。",
                            modifier = Modifier.padding(horizontal = ToolBoxThemeTokens.spacing.md),
                            style = ToolBoxThemeTokens.textStyles.label.copy(color = ToolBoxThemeTokens.colors.textTertiary))
                    }
                }
                itemsIndexed(
                    items = tools,
                    key = { _, tool -> "permission:${tool.metadata.id}" },
                    contentType = { _, _ -> "permission-tool" },
                ) { index, tool ->
                    CatalogLazyGroupItem(index = index, count = tools.size, modifier = Modifier.toolBoxEntrance(entrance, index + 1)) {
                        // Reset collected state, not just the flow, when an installed identity changes.
                        key(tool.currentVersion) {
                            val summaryFlow = remember(tool.currentVersion) { summaryFor(tool) }
                            val summary by summaryFlow.collectAsStateWithLifecycle(initialValue = ToolPermissionSummary.Loading)
                            ToolPermissionSelectionRow(tool, summary.withSystemState(systemGranted, exactAlarms), onSelectTool)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolPermissionSelectionRow(tool: InstalledTool, summary: ToolPermissionSummary, onSelectTool: (String) -> Unit) {
    val colors = ToolBoxThemeTokens.colors
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .toolBoxPressFeedback(interaction, scale = io.toolbox.core.ui.component.ToolBoxMotion.RowPressScale)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = "管理${tool.metadata.name}的权限", onClick = { onSelectTool(tool.metadata.id) })
            .padding(horizontal = ToolBoxThemeTokens.spacing.md, vertical = ToolBoxThemeTokens.spacing.sm)
            .testTag("permission_summary:${tool.metadata.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CatalogToolGlyph(tool.metadata.id, tool.currentVersion.versionCode, ToolVisual(ToolBoxIconKey.Tools, colors.primary), size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolBoxText(tool.metadata.name, style = ToolBoxThemeTokens.textStyles.title.copy(color = colors.textPrimary))
            ToolBoxText(summary.label, style = ToolBoxThemeTokens.textStyles.metadata.copy(color = colors.textSecondary))
        }
        ToolBoxIcon(ToolBoxIconKey.ChevronRight, null, Modifier.size(18.dp), tint = colors.textSecondary)
    }
}
