package io.toolbox.host.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.component.ToolBoxSwitchSettingRow
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxBusyIndicator
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.BuildConfig
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.HostTestTags
import io.toolbox.host.ui.SectionHeader
import io.toolbox.host.ui.SurfaceCard
import top.yukonga.miuix.kmp.utils.overScrollVertical

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
    listState: LazyListState = rememberLazyListState(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        listState = listState,
        contentPadding = contentPadding,
        onAppearance = onAppearance,
        onBackgroundSafeguards = onBackgroundSafeguards,
        onToolPermissions = onToolPermissions,
        onDeveloperHelp = onDeveloperHelp,
        onAbout = onAbout,
        onBackupRestore = onBackupRestore,
        onBackgroundEnabledChange = viewModel::setBackgroundEnabled,
        onRetryBackground = viewModel::retryBackgroundUpdate,
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
    listState: LazyListState = rememberLazyListState(),
    onRetryBackground: () -> Unit = {},
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = contentPadding,
        overscrollEffect = null,
    ) {
        state.error?.let {
            item("error") { SurfaceCard { AppText(it, color = ToolBoxThemeTokens.colors.danger) } }
            item("after-error") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }
        }
        if (state.backgroundError != null) {
            item("background-error") { BackgroundSettingsFeedback(state, onRetryBackground) }
            item("after-background-error") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }
        }
        item("appearance") {
            ToolBoxGroupedSurface {
                ToolBoxSettingRow(
                    title = "外观",
                    modifier = Modifier.testTag(HostTestTags.SettingsAppearance),
                    summary = "${state.settings.themeStyle.displayLabel} · ${state.settings.theme.baseLabel}",
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
                    title = "后台保障", summary = state.backgroundProgressSummary ?: "允许已启动的任务持续运行",
                    checked = state.settings.backgroundEnabled, onCheckedChange = onBackgroundEnabledChange,
                    icon = ToolBoxIconKey.Lock, enabled = state.canChangeBackground,
                    modifier = Modifier.testTag("settings_background_enabled"),
                )
                ToolBoxGroupDivider()
                ToolBoxSettingRow("后台运行设置", summary = "通知、电池与系统权限", icon = ToolBoxIconKey.Clock,
                    onClick = onBackgroundSafeguards, enabled = state.loaded)
                ToolBoxGroupDivider()
                ToolBoxSettingRow(
                    title = "工具权限",
                    summary = "查看授权状态，按工具调整",
                    icon = ToolBoxIconKey.Shield,
                    onClick = onToolPermissions,
                    enabled = state.loaded,
                )
            }
        }
        item("backup-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("backup") { ToolBoxGroupedSurface {
            ToolBoxSettingRow("备份与恢复", summary = "导出本地备份，或从文件恢复", icon = ToolBoxIconKey.Folder,
                onClick = onBackupRestore, enabled = state.loaded, modifier = Modifier.testTag("settings_backup_restore"))
        } }
        item("support-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("support-title") { SectionHeader("支持") }
        item("before-support") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("support") {
            ToolBoxGroupedSurface {
                ToolBoxSettingRow(
                    title = "开发者帮助",
                    summary = "离线手册与四个范例",
                    icon = ToolBoxIconKey.Code,
                    onClick = onDeveloperHelp,
                    enabled = state.loaded,
                )
                ToolBoxGroupDivider()
                ToolBoxSettingRow(
                    title = "关于 ToolBox", summary = "版本 ${BuildConfig.VERSION_NAME}",
                    icon = ToolBoxIconKey.Tools, onClick = onAbout,
                )
            }
        }
    }
}

@Composable
internal fun BackgroundSettingsFeedback(state: SettingsUiState, onRetry: () -> Unit) {
    val error = state.backgroundError ?: return
    SurfaceCard {
        AppText(error, color = ToolBoxThemeTokens.colors.danger,
            modifier = Modifier.semantics { if (!state.backgroundWorking) liveRegion = LiveRegionMode.Polite },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one)) {
            ToolBoxTextButton(
                label = if (state.backgroundOperation == BackgroundSettingsOperation.STOP) "重试停止" else "重试保存",
                onClick = onRetry,
                enabled = !state.backgroundWorking,
                modifier = Modifier.testTag("background_settings_retry").semantics {
                    if (state.backgroundWorking) stateDescription = state.backgroundProgressSummary.orEmpty()
                },
            )
            Box(Modifier.size(24.dp)) { if (state.backgroundWorking) ToolBoxBusyIndicator() }
        }
    }
}

internal val SettingsUiState.backgroundProgressSummary: String?
    get() = if (!backgroundWorking) null else when (backgroundOperation) {
        BackgroundSettingsOperation.SAVE -> "正在保存…"
        BackgroundSettingsOperation.STOP -> "正在停止后台运行…"
        null -> null
    }

internal val io.toolbox.core.data.ThemeStyle.displayLabel: String
    get() = when (this) {
        io.toolbox.core.data.ThemeStyle.MIUIX -> "Miuix"
        io.toolbox.core.data.ThemeStyle.LIQUID_GLASS -> "Liquid Glass"
    }

internal val io.toolbox.core.data.ThemeMode.baseLabel: String
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
