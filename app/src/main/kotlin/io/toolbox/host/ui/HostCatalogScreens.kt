package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
fun HomeScreen(
    model: HostCatalogScreenModel,
    onDestination: (MainDestination) -> Unit,
    onImport: () -> Unit,
    onLaunchTool: (String) -> Unit,
) {
    PrimaryScreen(
        selected = MainDestination.Home,
        onDestination = onDestination,
        title = "ToolBox",
        onImport = onImport,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(HostTestTags.CatalogList),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { HomeCatalogSummary(model.installedToolCount) }
            catalogItems(model.state, onImport, onLaunchTool, showSectionHeader = true)
        }
    }
}

@Composable
fun ToolManagerScreen(
    model: HostCatalogScreenModel,
    onDestination: (MainDestination) -> Unit,
    onImport: () -> Unit,
    onLaunchTool: (String) -> Unit,
) {
    PrimaryScreen(
        selected = MainDestination.Tools,
        onDestination = onDestination,
        title = "工具管理",
        onImport = onImport,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(HostTestTags.CatalogList),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { ToolManagerSummary(model.installedToolCount) }
            catalogItems(model.state, onImport, onLaunchTool, showSectionHeader = false)
        }
    }
}

@Composable
fun SettingsScreen(onDestination: (MainDestination) -> Unit) {
    PrimaryScreen(
        selected = MainDestination.Settings,
        onDestination = onDestination,
        title = "设置",
        onImport = null,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { CatalogStatusState("设置将在持久化配置接入后提供；当前没有可更改的宿主选项。") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.catalogItems(
    state: UiState<List<ToolCardModel>>,
    onImport: () -> Unit,
    onLaunchTool: (String) -> Unit,
    showSectionHeader: Boolean,
) {
    when (state) {
        UiState.Empty -> item { EmptyCatalogState(onImport) }
        UiState.Loading -> item { CatalogStatusState("正在读取已安装工具") }
        is UiState.Error -> item { CatalogStatusState(state.message) }
        is UiState.Content -> {
            if (state.value.isEmpty()) {
                item { EmptyCatalogState(onImport) }
            } else {
                if (showSectionHeader) item { SectionHeader("已安装工具", "") }
                items(state.value, key = ToolCardModel::toolId) { tool ->
                    ToolCard(tool, onLaunchTool)
                }
            }
        }
    }
}

@Composable
private fun HomeCatalogSummary(installedToolCount: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(ToolBoxThemeTokens.colors.primary)
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("我的工具箱", color = ToolBoxThemeTokens.colors.onPrimary, size = 14)
            AppText("$installedToolCount 个已安装工具", color = ToolBoxThemeTokens.colors.onPrimary, size = 28, weight = FontWeight.Bold)
            AppText("导入 .tbx 后会在这里显示", color = ToolBoxThemeTokens.colors.onPrimary, size = 12)
        }
    }
}

@Composable
private fun ToolManagerSummary(installedToolCount: Int) {
    SurfaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AppText(installedToolCount.toString(), size = 28, weight = FontWeight.Bold)
                AppText("已安装工具", size = 13, color = ToolBoxThemeTokens.colors.textSecondary)
            }
        }
    }
}
