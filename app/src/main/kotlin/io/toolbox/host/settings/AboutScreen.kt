package io.toolbox.host.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.BuildConfig
import io.toolbox.host.ui.*

@Composable
internal fun AboutScreen(onBack: () -> Unit, onReady: () -> Unit) {
    LaunchedEffect(Unit) { onReady() }
    DetailScreen("关于 ToolBox", onBack) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = mergePadding(padding, PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { AppText("一个安静、轻巧的个人小工具架。", color = ToolBoxThemeTokens.colors.textSecondary) }
            item { SectionHeader("应用") }
            item { ToolBoxGroupedSurface {
                ToolBoxValueRow("ToolBox", BuildConfig.VERSION_NAME, summary = ".tbx 小工具宿主")
                ToolBoxGroupDivider()
                ToolBoxValueRow("界面", "OpenDesign", summary = "Liquid Glass · 唯一主题")
                ToolBoxGroupDivider()
                ToolBoxValueRow("ToolBox API", "1.0")
            } }
            item { SectionHeader("来源") }
            item { ToolBoxCard {
                AppText("基于 ToolBox Android", textStyle = ToolBoxThemeTokens.textStyles.title)
                AppText("github.com/gkeyes/ToolBox-Android", textStyle = ToolBoxThemeTokens.textStyles.metadata)
                Spacer(Modifier.height(8.dp))
                AppText("界面来自你提供的 ToolBox iOS 重设计项目；原生 Compose 承载真实工具、权限与后台任务。",
                    textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
            } }
        }
    }
}
