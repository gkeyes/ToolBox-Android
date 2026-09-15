package io.toolbox.host.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.component.ToolBoxValueRow
import io.toolbox.core.ui.component.ToolBoxSwitchSettingRow
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.BuildConfig
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.HostTestTags
import io.toolbox.host.ui.SectionHeader
import io.toolbox.host.ui.SurfaceCard

@Composable
internal fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
    onAppearance: () -> Unit,
    onBackgroundSafeguards: () -> Unit,
    onToolPermissions: () -> Unit,
    onDeveloperHelp: () -> Unit,
    onAbout: () -> Unit = {},
    onBackupRestore: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        contentPadding = contentPadding,
        onAppearance = onAppearance,
        onBackgroundSafeguards = onBackgroundSafeguards,
        onToolPermissions = onToolPermissions,
        onDeveloperHelp = onDeveloperHelp,
        onAbout = onAbout,
        onBackupRestore = onBackupRestore,
        onBackgroundEnabledChange = viewModel::setBackgroundEnabled,
    )
}

@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onAppearance: () -> Unit,
    onBackgroundSafeguards: () -> Unit,
    onToolPermissions: () -> Unit,
    onDeveloperHelp: () -> Unit,
    onAbout: () -> Unit = {},
    onBackupRestore: () -> Unit = {},
    onBackgroundEnabledChange: (Boolean) -> Unit = {},
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
                ToolBoxValueRow("界面样式", "Liquid Glass", summary = "组件比例与玻璃材质", icon = ToolBoxIconKey.Palette)
                ToolBoxGroupDivider()
                ToolBoxSettingRow(
                    title = "外观模式",
                    modifier = Modifier.testTag(HostTestTags.SettingsAppearance),
                    summary = "Liquid Glass · ${state.settings.theme.baseLabel}",
                    icon = ToolBoxIconKey.Palette,
                    onClick = onAppearance,
                    enabled = state.loaded,
                )
            }
        }
        item("between-groups") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("operation-title") { SectionHeader("运行与权限") }
        item("before-operation") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("operation") {
            ToolBoxGroupedSurface {
                ToolBoxSwitchSettingRow(
                    title = "后台保障", summary = "允许已启动的任务持续运行",
                    checked = state.settings.backgroundEnabled, onCheckedChange = onBackgroundEnabledChange,
                    icon = ToolBoxIconKey.Lock, enabled = state.loaded,
                )
                ToolBoxGroupDivider()
                ToolBoxSettingRow("后台运行设置", summary = "通知、电池与系统权限", icon = ToolBoxIconKey.Clock,
                    onClick = onBackgroundSafeguards, enabled = state.loaded)
                ToolBoxGroupDivider()
                ToolBoxSettingRow(
                    title = "工具权限",
                    summary = "按工具管理已声明的能力",
                    icon = ToolBoxIconKey.Shield,
                    onClick = onToolPermissions,
                    enabled = state.loaded,
                )
            }
        }
        item("backup-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("backup") { ToolBoxGroupedSurface {
            ToolBoxSettingRow("备份与恢复", summary = "宿主设置、工具包与应用数据", icon = ToolBoxIconKey.Folder,
                onClick = onBackupRestore, enabled = state.loaded, modifier = Modifier.testTag("settings_backup_restore"))
        } }
        item("support-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("support-title") { SectionHeader("支持") }
        item("before-support") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("support") {
            ToolBoxGroupedSurface {
                ToolBoxSettingRow(
                    title = "Developer Help",
                    summary = "离线手册与四个范例",
                    icon = ToolBoxIconKey.Code,
                    onClick = onDeveloperHelp,
                    enabled = state.loaded,
                )
                ToolBoxGroupDivider()
                ToolBoxSettingRow(
                    title = "关于 ToolBox", summary = "${BuildConfig.VERSION_NAME} · OpenDesign",
                    icon = ToolBoxIconKey.Tools, onClick = onAbout,
                )
            }
        }
    }
}

private val io.toolbox.core.data.ThemeMode.baseLabel: String
    get() = when (this) {
        io.toolbox.core.data.ThemeMode.SYSTEM,
        io.toolbox.core.data.ThemeMode.MONET_SYSTEM,
        -> "跟随系统"
        io.toolbox.core.data.ThemeMode.LIGHT,
        io.toolbox.core.data.ThemeMode.MONET_LIGHT,
        -> "浅色"
        io.toolbox.core.data.ThemeMode.DARK,
        io.toolbox.core.data.ThemeMode.MONET_DARK,
        -> "深色"
    }
