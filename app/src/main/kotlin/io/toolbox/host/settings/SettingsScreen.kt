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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        contentPadding = contentPadding,
        onAppearance = onAppearance,
        onBackgroundSafeguards = onBackgroundSafeguards,
        onToolPermissions = onToolPermissions,
        onDeveloperHelp = onDeveloperHelp,
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
                ToolBoxSettingRow(
                    title = "外观",
                    modifier = Modifier.testTag(HostTestTags.SettingsAppearance),
                    summary = "${state.settings.themeStyle.label} · ${state.settings.theme.baseLabel}",
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
                ToolBoxSettingRow(
                    title = "后台保障",
                    summary = if (state.settings.backgroundEnabled) "已开启" else "已关闭",
                    icon = ToolBoxIconKey.Clock,
                    onClick = onBackgroundSafeguards,
                    enabled = state.loaded,
                )
                ToolBoxGroupDivider()
                ToolBoxSettingRow(
                    title = "工具权限",
                    icon = ToolBoxIconKey.Shield,
                    onClick = onToolPermissions,
                    enabled = state.loaded,
                )
            }
        }
        item("support-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("support-title") { SectionHeader("支持") }
        item("before-support") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("support") {
            ToolBoxGroupedSurface {
                ToolBoxSettingRow(
                    title = "开发帮助",
                    icon = ToolBoxIconKey.Code,
                    onClick = onDeveloperHelp,
                    enabled = state.loaded,
                )
                ToolBoxGroupDivider()
                ToolBoxValueRow(
                    title = "关于 ToolBox",
                    value = "${BuildConfig.VERSION_NAME} · API 1.0",
                    icon = ToolBoxIconKey.Tools,
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
