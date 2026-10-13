package io.toolbox.host.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.toolBoxPressFeedback
import io.toolbox.core.ui.component.toolBoxShape
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxBusyIndicator
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.core.ui.component.rememberToolBoxEntrance
import io.toolbox.core.ui.component.toolBoxEntrance
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
    val entrance = rememberToolBoxEntrance("settings")
    io.toolbox.core.ui.component.ToolBoxTitleCollapseSource(listState)
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = contentPadding,
        overscrollEffect = null,
    ) {
        state.error?.let {
            item("error") { SurfaceCard { AppText(it, color = ToolBoxThemeTokens.colors.onSoftDanger) } }
            item("after-error") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }
        }
        if (state.backgroundError != null) {
            item("background-error") { BackgroundSettingsFeedback(state, onRetryBackground) }
            item("after-background-error") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }
        }
        item("hero") {
            SettingsHeroCard(onClick = onAbout, modifier = Modifier.toolBoxEntrance(entrance, 0))
        }
        item("hero-gap") { Spacer(Modifier.height(20.dp)) }
        item("appearance") {
            ToolBoxGroupedSurface(Modifier.toolBoxEntrance(entrance, 1)) {
                SettingsNavRow(
                    title = "外观",
                    icon = ToolBoxIconKey.Palette,
                    tint = SettingsIconTint.Pink,
                    modifier = Modifier.testTag(HostTestTags.SettingsAppearance),
                    summary = "${state.settings.theme.baseLabel} · ${state.settings.materialLabel}",
                    onClick = onAppearance,
                    enabled = state.loaded,
                )
            }
        }
        item("operation-title") {
            Box(Modifier.toolBoxEntrance(entrance, 2).padding(top = 26.dp, bottom = 7.dp)) { SectionHeader("运行与权限") }
        }
        item("operation") {
            ToolBoxGroupedSurface(Modifier.toolBoxEntrance(entrance, 3)) {
                SettingsSwitchRow(
                    title = "后台保障", summary = state.backgroundProgressSummary ?: "允许已启动的任务持续运行",
                    checked = state.settings.backgroundEnabled, onCheckedChange = onBackgroundEnabledChange,
                    icon = ToolBoxIconKey.Lock, tint = SettingsIconTint.Green, enabled = state.canChangeBackground,
                    modifier = Modifier.testTag("settings_background_enabled"),
                )
                SettingsRowSeparator()
                SettingsNavRow(
                    title = "后台运行设置", summary = "通知、电池与系统权限",
                    icon = ToolBoxIconKey.Clock, tint = SettingsIconTint.Orange,
                    onClick = onBackgroundSafeguards, enabled = state.loaded,
                )
                SettingsRowSeparator()
                SettingsNavRow(
                    title = "工具权限",
                    summary = "查看授权状态，按工具调整",
                    icon = ToolBoxIconKey.Shield,
                    tint = SettingsIconTint.Blue,
                    onClick = onToolPermissions,
                    enabled = state.loaded,
                )
            }
        }
        item("backup-gap") { Spacer(Modifier.height(20.dp)) }
        item("backup") {
            ToolBoxGroupedSurface(Modifier.toolBoxEntrance(entrance, 4)) {
                SettingsNavRow(
                    title = "备份与恢复", summary = "导出本地备份，或从文件恢复",
                    icon = ToolBoxIconKey.Folder, tint = SettingsIconTint.Teal,
                    onClick = onBackupRestore, enabled = state.loaded,
                    modifier = Modifier.testTag("settings_backup_restore"),
                )
            }
        }
        item("support-title") {
            Box(Modifier.toolBoxEntrance(entrance, 5).padding(top = 26.dp, bottom = 7.dp)) { SectionHeader("支持") }
        }
        item("support") {
            ToolBoxGroupedSurface(Modifier.toolBoxEntrance(entrance, 6)) {
                SettingsNavRow(
                    title = "开发者帮助",
                    summary = "调试状态、离线手册与四个范例",
                    icon = ToolBoxIconKey.Code,
                    tint = SettingsIconTint.Indigo,
                    onClick = onDeveloperHelp,
                    enabled = state.loaded,
                )
                SettingsRowSeparator()
                SettingsNavRow(
                    title = "关于 ToolBox", summary = "版本 ${BuildConfig.VERSION_NAME}",
                    icon = ToolBoxIconKey.Tools, tint = SettingsIconTint.Graphite, onClick = onAbout,
                )
            }
        }
        item("support-footer") {
            Box(Modifier.toolBoxEntrance(entrance, 7)) { SettingsFooter("设置仅保存在本机；导出备份可随时迁移到新设备。") }
        }
    }
}

/** Top card of the Settings tab: the app identity, linking to About. */
@Composable
private fun SettingsHeroCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ToolBoxThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val shape = toolBoxShape(ToolBoxThemeTokens.radii.card)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toolBoxPressFeedback(interactionSource, shape = shape)
            .clip(shape)
            .background(colors.surface)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF6FB4EE).copy(alpha = 0.22f), Color(0x006FB4EE)),
                        center = Offset.Zero,
                        radius = size.width * 0.75f,
                    ),
                )
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = "关于 ToolBox",
                onClick = onClick,
            )
            .padding(start = 16.dp, end = 14.dp, top = 18.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsIconTile(ToolBoxIconKey.Tools, SettingsIconTint.Graphite, size = 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AppText("ToolBox", textStyle = ToolBoxThemeTokens.textStyles.detailTitle)
            AppText(
                "版本 ${BuildConfig.VERSION_NAME} · .tbx 小工具宿主",
                color = colors.textSecondary,
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
            )
        }
        ToolBoxIcon(ToolBoxIconKey.ChevronRight, null, Modifier.size(16.dp), tint = colors.textTertiary)
    }
}

/** Summary of the material setting shown on the Appearance row. */
internal val io.toolbox.core.data.HostSettings.materialLabel: String
    get() = if (reduceTransparency) "实色材质" else "透明材质"

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
