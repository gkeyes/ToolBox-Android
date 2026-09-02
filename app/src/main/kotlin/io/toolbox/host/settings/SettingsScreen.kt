package io.toolbox.host.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.ui.component.ToolBoxChoiceSettingRow
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxSettingChoice
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.component.ToolBoxValueRow
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.BuildConfig
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.SectionHeader
import io.toolbox.host.ui.SurfaceCard

@Composable
internal fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
    onBackgroundSafeguards: () -> Unit,
    onToolPermissions: () -> Unit,
    onDeveloperHelp: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        contentPadding = contentPadding,
        onThemeSelected = { viewModel.selectTheme(ThemeMode.valueOf(it)) },
        onBackgroundSafeguards = onBackgroundSafeguards,
        onToolPermissions = onToolPermissions,
        onDeveloperHelp = onDeveloperHelp,
    )
}

@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onThemeSelected: (String) -> Unit,
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
        item("appearance-title") { SectionHeader("偏好") }
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
                ToolBoxGroupDivider()
                ToolBoxSettingRow(
                    title = "后台保障",
                    summary = if (state.settings.backgroundEnabled) "已开启 · 管理会话与系统权限" else "已关闭",
                    icon = ToolBoxIconKey.Clock,
                    onClick = onBackgroundSafeguards,
                    enabled = state.loaded,
                )
            }
        }
        item("between-groups") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("operation-title") { SectionHeader("工具") }
        item("before-operation") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("operation") {
            ToolBoxGroupedSurface {
                ToolBoxSettingRow(
                    title = "工具权限",
                    summary = "按工具管理已声明能力",
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
                    summary = "离线查看 .tbx 与 ToolBox API 1.0",
                    icon = ToolBoxIconKey.Code,
                    onClick = onDeveloperHelp,
                    enabled = state.loaded,
                )
                ToolBoxGroupDivider()
                ToolBoxValueRow(
                    title = "关于 ToolBox",
                    summary = "个人网页工具宿主",
                    value = "${BuildConfig.VERSION_NAME} · API 1.0",
                    icon = ToolBoxIconKey.Tools,
                )
            }
        }
        if (state.loaded && state.error == null) {
            item("saved-hint-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }
            item("saved-hint") { SettingsSavedHint() }
        }
    }
}

@Composable
private fun SettingsSavedHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ToolBoxThemeTokens.radii.control))
            .background(ToolBoxThemeTokens.colors.softSuccess)
            .heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
            .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBoxIcon(
            icon = ToolBoxIconKey.Check,
            contentDescription = null,
            tint = ToolBoxThemeTokens.colors.success,
        )
        Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
        AppText(
            text = "设置会自动保存，不会改变工具内部页面。",
            modifier = Modifier.weight(1f),
            color = ToolBoxThemeTokens.colors.success,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
    }
}
