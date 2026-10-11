package io.toolbox.host.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.host.BuildConfig
import io.toolbox.host.ui.*

@Composable
internal fun AboutScreen(onBack: () -> Unit, onReady: () -> Unit) {
    LaunchedEffect(Unit) { onReady() }
    val entrance = rememberToolBoxEntrance("about")
    DetailScreen("关于 ToolBox", onBack) { padding ->
        LazyColumn(
            state = rememberTitleBoundListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = mergePadding(padding, PaddingValues(ToolBoxThemeTokens.spacing.md)),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.lg),
        ) {
            item {
                AppText(
                    "一个安静、轻巧的个人小工具架。",
                    modifier = Modifier.toolBoxEntrance(entrance, 0).padding(horizontal = ToolBoxThemeTokens.spacing.md),
                    color = ToolBoxThemeTokens.colors.textSecondary,
                )
            }
            item { Column(Modifier.toolBoxEntrance(entrance, 1), verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.xs)) {
                SectionHeader("应用")
                ToolBoxGroupedSurface {
                    ToolBoxValueRow("ToolBox", BuildConfig.VERSION_NAME, summary = ".tbx 小工具宿主", icon = ToolBoxIconKey.Tools)
                    ToolBoxValueRow("界面", if (ToolBoxThemeTokens.style == ToolBoxThemeStyle.Miuix) "Miuix" else "Liquid Glass", icon = ToolBoxIconKey.Palette)
                    ToolBoxValueRow("ToolBox API", "1.0", icon = ToolBoxIconKey.Code)
                }
            } }
            item { Column(Modifier.toolBoxEntrance(entrance, 2), verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.xs)) {
                SectionHeader("来源")
                ToolBoxCard {
                    AppText("基于 ToolBox Android", textStyle = ToolBoxThemeTokens.textStyles.headline)
                    Spacer(Modifier.height(ToolBoxThemeTokens.spacing.xxs))
                    AppText("github.com/gkeyes/ToolBox-Android", textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        color = ToolBoxThemeTokens.colors.primary)
                    Spacer(Modifier.height(ToolBoxThemeTokens.spacing.sm))
                    AppText("界面来自你提供的 ToolBox iOS 重设计项目；原生 Compose 承载真实工具、权限与后台任务。",
                        textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
                }
            } }
        }
    }
}
