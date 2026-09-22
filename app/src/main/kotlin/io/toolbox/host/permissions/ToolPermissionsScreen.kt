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
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.HostPackageOperations
import io.toolbox.host.ui.CatalogLazyGroupItem
import io.toolbox.host.ui.CatalogToolGlyph
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.SectionHeader
import io.toolbox.host.ui.ToolVisual
import io.toolbox.host.ui.mergePadding
import kotlinx.coroutines.flow.onEach

@Composable
internal fun ToolPermissionsScreen(
    catalog: CatalogRepository,
    onBack: () -> Unit,
    onSelectTool: (String) -> Unit,
    onReady: () -> Unit = {},
    packages: HostPackageOperations? = null,
    grants: PermissionGrantRepository? = null,
) {
    var toolsLoaded by remember(catalog) { mutableStateOf(false) }
    val toolsFlow = remember(catalog) { catalog.observeTools().onEach { toolsLoaded = true } }
    val tools by toolsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val summaryReader = remember(catalog, packages, grants) {
        if (packages == null || grants == null) null else ToolPermissionSummaryReader(catalog, packages, grants)
    }
    LaunchedEffect(toolsLoaded) { if (toolsLoaded) onReady() }

    DetailScreen(title = "工具权限", onBack = onBack) { chromePadding ->
        LazyColumn(
            modifier = Modifier.widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
                .fillMaxWidth().fillMaxSize().align(Alignment.TopCenter),
            contentPadding = mergePadding(chromePadding, PaddingValues(ToolBoxThemeTokens.spacing.two)),
        ) {
            if (tools.isEmpty()) {
                item("empty") {
                    ToolBoxCard {
                        ToolBoxText(
                            text = if (toolsLoaded) "尚未安装工具" else "正在读取工具",
                            style = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textSecondary),
                        )
                    }
                }
            } else {
                item("installed-title") {
                    Column(Modifier.padding(bottom = ToolBoxThemeTokens.spacing.one), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionHeader("已安装工具 · ${tools.size}")
                        ToolBoxText("摘要为 ToolBox 内部授权；系统权限请进入详情核对。",
                            style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary))
                    }
                }
                itemsIndexed(
                    items = tools,
                    key = { _, tool -> "permission:${tool.metadata.id}" },
                    contentType = { _, _ -> "permission-tool" },
                ) { index, tool ->
                    CatalogLazyGroupItem(index = index, count = tools.size) {
                        // Reset collected state, not just the flow, when an installed identity changes.
                        key(tool.currentVersion, summaryReader) {
                            val summary = if (summaryReader == null) {
                                ToolPermissionSummary.Unavailable
                            } else {
                                val summaryFlow = remember(tool.currentVersion, summaryReader) { summaryReader.observe(tool) }
                                val value by summaryFlow.collectAsStateWithLifecycle(initialValue = ToolPermissionSummary.Loading)
                                value
                            }
                            ToolPermissionSelectionRow(tool, summary, onSelectTool)
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
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClickLabel = "管理${tool.metadata.name}的权限", onClick = { onSelectTool(tool.metadata.id) })
            .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf, vertical = ToolBoxThemeTokens.spacing.one)
            .testTag("permission_summary:${tool.metadata.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CatalogToolGlyph(tool.metadata.id, tool.currentVersion.versionCode, ToolVisual(ToolBoxIconKey.Tools, colors.primary), size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolBoxText(tool.metadata.name, style = ToolBoxThemeTokens.textStyles.title.copy(color = colors.textPrimary))
            ToolBoxText(summary.label, style = ToolBoxThemeTokens.textStyles.metadata.copy(color = colors.textSecondary))
        }
        ToolBoxIcon(ToolBoxIconKey.ChevronRight, null, Modifier.size(18.dp), tint = colors.textSecondary)
    }
}
