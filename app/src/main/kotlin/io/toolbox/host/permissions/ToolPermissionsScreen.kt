package io.toolbox.host.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.SectionHeader
import kotlinx.coroutines.flow.onEach

@Composable
internal fun ToolPermissionsScreen(
    catalog: CatalogRepository,
    onBack: () -> Unit,
    onSelectTool: (String) -> Unit,
    onReady: () -> Unit = {},
) {
    var toolsLoaded by remember(catalog) { mutableStateOf(false) }
    val toolsFlow = remember(catalog) {
        catalog.observeTools().onEach { toolsLoaded = true }
    }
    val tools by toolsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(toolsLoaded) {
        if (toolsLoaded) onReady()
    }

    DetailScreen(title = "工具权限", onBack = onBack) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
                .fillMaxWidth()
                .fillMaxSize()
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(ToolBoxThemeTokens.spacing.two),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            if (tools.isEmpty()) {
                item("empty") {
                    ToolBoxCard {
                        ToolBoxText(
                            text = "尚未安装工具",
                            style = ToolBoxThemeTokens.textStyles.body.copy(
                                color = ToolBoxThemeTokens.colors.textSecondary,
                            ),
                        )
                    }
                }
            } else {
                item("installed-title") { SectionHeader("已安装工具 · ${tools.size}") }
                item("installed-tools") {
                    ToolBoxGroupedSurface {
                        tools.forEachIndexed { index, tool ->
                            ToolPermissionSelectionRow(tool, onSelectTool)
                            if (index != tools.lastIndex) ToolBoxGroupDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolPermissionSelectionRow(
    tool: InstalledTool,
    onSelectTool: (String) -> Unit,
) {
    ToolBoxSettingRow(
        title = tool.metadata.name,
        summary = "版本 ${tool.currentVersion.version}",
        icon = ToolBoxIconKey.Tools,
        onClick = { onSelectTool(tool.metadata.id) },
    )
}
