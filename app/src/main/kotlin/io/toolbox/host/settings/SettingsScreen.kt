package io.toolbox.host.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.ui.component.ToolBoxChoiceSettingRow
import io.toolbox.core.ui.component.ToolBoxSettingChoice
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.component.ToolBoxSwitchSettingRow
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.SectionHeader
import io.toolbox.host.ui.SurfaceCard

@Composable
internal fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
    onToolPermissions: () -> Unit,
    onDeveloperHelp: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
    ) {
        state.error?.let { item("error") { SurfaceCard { AppText(it, color = ToolBoxThemeTokens.colors.danger) } } }
        item("appearance-title") { SectionHeader("外观") }
        item("appearance") {
            SurfaceCard(contentPadding = ToolBoxThemeTokens.spacing.half) {
                ToolBoxChoiceSettingRow(
                    title = "主题",
                    selectedValue = state.settings.theme.name,
                    choices = ThemeMode.entries.map { ToolBoxSettingChoice(it.name, it.label) },
                    onSelected = { viewModel.selectTheme(ThemeMode.valueOf(it)) },
                    summary = "只影响 ToolBox 宿主界面",
                    enabled = state.loaded,
                )
            }
        }
        item("operation-title") { SectionHeader("功能") }
        item("operation") {
            SurfaceCard(contentPadding = ToolBoxThemeTokens.spacing.half) {
                ToolBoxSwitchSettingRow(
                    title = "后台运行",
                    summary = "关闭会取消全部工具后台任务和对应通知",
                    checked = state.settings.backgroundEnabled,
                    onCheckedChange = viewModel::setBackgroundEnabled,
                    enabled = state.loaded,
                )
                ToolBoxSettingRow(
                    title = "工具权限",
                    summary = "按工具管理已声明能力",
                    onClick = onToolPermissions,
                    enabled = state.loaded,
                )
                ToolBoxSettingRow(
                    title = "开发帮助",
                    summary = "离线查看 .tbx 与 ToolBox API 1.0",
                    onClick = onDeveloperHelp,
                    enabled = state.loaded,
                )
            }
        }
    }
}
