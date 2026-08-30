package io.toolbox.host.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.ui.component.ToolBoxChoiceSettingRow
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
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
    SettingsContent(
        state = state,
        contentPadding = contentPadding,
        onThemeSelected = { viewModel.selectTheme(ThemeMode.valueOf(it)) },
        onBackgroundEnabled = viewModel::setBackgroundEnabled,
        onToolPermissions = onToolPermissions,
        onDeveloperHelp = onDeveloperHelp,
    )
}

@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onThemeSelected: (String) -> Unit,
    onBackgroundEnabled: (Boolean) -> Unit,
    onToolPermissions: () -> Unit,
    onDeveloperHelp: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        state.error?.let {
            item("error") { SurfaceCard { AppText(it, color = ToolBoxThemeTokens.colors.danger) } }
            item("after-error") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }
        }
        item("appearance-title") { SectionHeader("外观") }
        item("before-appearance") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("appearance") {
            ToolBoxGroupedSurface {
                ToolBoxChoiceSettingRow(
                    title = "主题",
                    selectedValue = state.settings.theme.name,
                    choices = ThemeMode.entries.map { ToolBoxSettingChoice(it.name, it.label) },
                    onSelected = onThemeSelected,
                    summary = "只影响 ToolBox 宿主界面",
                    icon = ToolBoxIconKey.Palette,
                    enabled = state.loaded,
                )
            }
        }
        item("between-groups") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.twoHalf)) }
        item("operation-title") { SectionHeader("功能") }
        item("before-operation") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("operation") {
            ToolBoxGroupedSurface {
                ToolBoxSwitchSettingRow(
                    title = "后台运行",
                    summary = "关闭会取消全部后台任务和通知",
                    checked = state.settings.backgroundEnabled,
                    onCheckedChange = onBackgroundEnabled,
                    icon = ToolBoxIconKey.Clock,
                    enabled = state.loaded,
                )
                ToolBoxGroupDivider()
                ToolBoxSettingRow(
                    title = "工具权限",
                    summary = "按工具管理已声明能力",
                    icon = ToolBoxIconKey.Shield,
                    onClick = onToolPermissions,
                    enabled = state.loaded,
                )
                ToolBoxGroupDivider()
                ToolBoxSettingRow(
                    title = "开发帮助",
                    summary = "离线查看 .tbx 与 ToolBox API 1.0",
                    icon = ToolBoxIconKey.Code,
                    onClick = onDeveloperHelp,
                    enabled = state.loaded,
                )
            }
        }
    }
}
