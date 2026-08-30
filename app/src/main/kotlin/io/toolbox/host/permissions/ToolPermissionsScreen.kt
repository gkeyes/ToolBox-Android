package io.toolbox.host.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
internal fun ToolPermissionsScreen(
    catalog: CatalogRepository,
    onBack: () -> Unit,
    onSelectTool: (String) -> Unit,
) {
    val tools by catalog.observeTools().collectAsStateWithLifecycle(initialValue = emptyList())

    ToolBoxAppScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ToolBoxTopBar(
                title = "工具权限",
                navigationIcon = ToolBoxIconKey.Back,
                onNavigationClick = onBack,
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ToolBoxThemeTokens.spacing.two,
                top = scaffoldPadding.calculateTopPadding() + ToolBoxThemeTokens.spacing.one,
                end = ToolBoxThemeTokens.spacing.two,
                bottom = scaffoldPadding.calculateBottomPadding() + ToolBoxThemeTokens.spacing.one,
            ),
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
                item("installed-tools") {
                    ToolBoxCard(contentPadding = PaddingValues(ToolBoxThemeTokens.spacing.half)) {
                        tools.forEach { tool ->
                            ToolPermissionSelectionRow(tool, onSelectTool)
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
        onClick = { onSelectTool(tool.metadata.id) },
    )
}
