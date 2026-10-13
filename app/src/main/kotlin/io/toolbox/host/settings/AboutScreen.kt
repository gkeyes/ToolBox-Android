package io.toolbox.host.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.BuildConfig
import io.toolbox.host.ui.*

@Composable
internal fun AboutScreen(onBack: () -> Unit, onReady: () -> Unit) {
    LaunchedEffect(Unit) { onReady() }
    val entrance = rememberToolBoxEntrance("about")
    val colors = ToolBoxThemeTokens.colors
    DetailScreen("关于 ToolBox", onBack) { padding ->
        LazyColumn(
            state = rememberTitleBoundListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = mergePadding(padding, PaddingValues(ToolBoxThemeTokens.spacing.md)),
        ) {
            item("hero") {
                Column(
                    Modifier.fillMaxWidth().toolBoxEntrance(entrance, 0).padding(top = 4.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SettingsIconTile(ToolBoxIconKey.Tools, SettingsIconTint.Graphite, size = 96.dp)
                    Spacer(Modifier.height(10.dp))
                    AppText("ToolBox", textStyle = ToolBoxThemeTokens.textStyles.detailTitle)
                    AppText(
                        "版本 ${BuildConfig.VERSION_NAME} · API 1.0",
                        color = colors.textSecondary,
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                    )
                    AppText(
                        "一个安静、轻巧的个人小工具架。",
                        modifier = Modifier.padding(horizontal = ToolBoxThemeTokens.spacing.md, vertical = 6.dp),
                        color = colors.textSecondary,
                        align = TextAlign.Center,
                    )
                }
            }
            item("app") {
                Column(Modifier.toolBoxEntrance(entrance, 1)) {
                    Box(Modifier.padding(top = 18.dp, bottom = 7.dp)) { SectionHeader("应用") }
                    ToolBoxGroupedSurface {
                        SettingsValueRow(
                            "ToolBox", BuildConfig.VERSION_NAME, ToolBoxIconKey.Tools, SettingsIconTint.Graphite,
                            summary = ".tbx 小工具宿主",
                        )
                        SettingsRowSeparator()
                        // One look since the iOS 27 redesign; the legacy style preference no longer changes it.
                        SettingsValueRow("界面", "Liquid Glass", ToolBoxIconKey.Palette, SettingsIconTint.Pink)
                        SettingsRowSeparator()
                        SettingsValueRow("ToolBox API", "1.0", ToolBoxIconKey.Code, SettingsIconTint.Indigo)
                    }
                }
            }
            item("source") {
                Column(Modifier.toolBoxEntrance(entrance, 2)) {
                    Box(Modifier.padding(top = 26.dp, bottom = 7.dp)) { SectionHeader("来源") }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(toolBoxShape(ToolBoxThemeTokens.radii.card))
                            .background(colors.surface),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SettingsIconTile(ToolBoxIconKey.Code, SettingsIconTint.Gray, size = 36.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                AppText("基于 ToolBox Android", textStyle = ToolBoxThemeTokens.textStyles.headline)
                                AppText(
                                    "github.com/gkeyes/ToolBox-Android",
                                    textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
                                    color = colors.primary,
                                )
                            }
                        }
                        SettingsRowSeparator(start = 16.dp)
                        AppText(
                            "界面来自你提供的 ToolBox iOS 重设计项目；原生 Compose 承载真实工具、权限与后台任务。",
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                            textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
            item("footer") {
                SettingsFooter(
                    ".tbx 小工具宿主 · 本地运行，无需账号",
                    Modifier.toolBoxEntrance(entrance, 3),
                )
            }
        }
    }
}
