package io.toolbox.host.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxSwitchSettingRow
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.SectionHeader
import io.toolbox.host.ui.SurfaceCard
import io.toolbox.host.ui.HostTestTags
import io.toolbox.host.ui.mergePadding

@Composable
internal fun AppearanceScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onReady: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.loaded) {
        if (state.loaded) onReady()
    }
    DetailScreen(title = "外观", onBack = onBack) { chromePadding ->
        AppearanceContent(
            state = state,
            onThemeModeSelected = viewModel::selectTheme,
            onReduceTransparencyChanged = viewModel::setReduceTransparency,
            onRetry = viewModel::retryAppearanceUpdate,
            contentPadding = mergePadding(
                chromePadding,
                PaddingValues(
                    horizontal = ToolBoxThemeTokens.spacing.two,
                    vertical = ToolBoxThemeTokens.spacing.oneHalf,
                ),
            ),
        )
    }
}

@Composable
internal fun AppearanceContent(
    state: SettingsUiState,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onReduceTransparencyChanged: (Boolean) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = ToolBoxThemeTokens.spacing.two,
        vertical = ToolBoxThemeTokens.spacing.oneHalf,
    ),
) {
    val settings = state.settings
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        state.error?.let { error ->
            item("error") {
                SurfaceCard {
                    AppText(error, color = ToolBoxThemeTokens.colors.danger)
                    if (state.canRetry) {
                        ToolBoxTextButton(label = "重试", onClick = onRetry)
                    }
                }
            }
            item("error-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }
        }

        item("style-title") { SectionHeader("当前样式") }
        item("style-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("current-style") {
            ToolBoxGroupedSurface {
                io.toolbox.core.ui.component.ToolBoxValueRow(
                    title = "Liquid Glass",
                    summary = "OpenDesign · 层次、光线与轻盈的内容卡片",
                    value = "已启用",
                    modifier = Modifier.testTag(HostTestTags.AppearanceLiquidGlass),
                )
            }
        }

        item("color-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("color-title") { SectionHeader("明暗与颜色") }
        item("color-title-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("color-settings") {
            ToolBoxGroupedSurface {
                listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEachIndexed { index, (mode, label) ->
                    io.toolbox.core.ui.component.ToolBoxRadioSettingRow(
                        title = label, selected = settings.theme.baseMode == mode,
                        onClick = { onThemeModeSelected(mode.withSystemColor(settings.theme.usesSystemColor)) },
                        enabled = state.loaded,
                        modifier = if (index == 0) Modifier.testTag(HostTestTags.AppearanceMode) else Modifier,
                    )
                    if (index < 2) ToolBoxGroupDivider()
                }
                ToolBoxGroupDivider()
                ToolBoxSwitchSettingRow(
                    title = "系统取色",
                    modifier = Modifier.testTag(HostTestTags.AppearanceSystemColor),
                    summary = "仅用于强调色，内容底板保持中性。",
                    checked = settings.theme.usesSystemColor,
                    onCheckedChange = { enabled ->
                        onThemeModeSelected(settings.theme.baseMode.withSystemColor(enabled))
                    },
                    enabled = state.loaded,
                )
            }
        }

        run {
            item("material-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
            item("material-title") { SectionHeader("玻璃材质") }
            item("material-title-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
            item("material-setting") {
                ToolBoxGroupedSurface {
                    ToolBoxSwitchSettingRow(
                        title = "降低透明度",
                        modifier = Modifier.testTag(HostTestTags.AppearanceReduceTransparency),
                        summary = "关闭实时模糊，使用同色实色材质。",
                        checked = settings.reduceTransparency,
                        onCheckedChange = onReduceTransparencyChanged,
                        enabled = state.loaded,
                    )
                }
            }
        }

        item("footer-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("footer") {
            AppText(
                "外观仅影响 ToolBox 宿主界面，工具内部页面继续使用自己的设计。切换会自动保存并立即生效。",
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
                color = ToolBoxThemeTokens.colors.textSecondary,
            )
        }
    }
}

private val ThemeMode.baseMode: ThemeMode
    get() = when (this) {
        ThemeMode.SYSTEM, ThemeMode.MONET_SYSTEM -> ThemeMode.SYSTEM
        ThemeMode.LIGHT, ThemeMode.MONET_LIGHT -> ThemeMode.LIGHT
        ThemeMode.DARK, ThemeMode.MONET_DARK -> ThemeMode.DARK
    }

private val ThemeMode.usesSystemColor: Boolean
    get() = this == ThemeMode.MONET_SYSTEM || this == ThemeMode.MONET_LIGHT || this == ThemeMode.MONET_DARK

private fun ThemeMode.withSystemColor(enabled: Boolean): ThemeMode = when (baseMode) {
    ThemeMode.SYSTEM -> if (enabled) ThemeMode.MONET_SYSTEM else ThemeMode.SYSTEM
    ThemeMode.LIGHT -> if (enabled) ThemeMode.MONET_LIGHT else ThemeMode.LIGHT
    ThemeMode.DARK -> if (enabled) ThemeMode.MONET_DARK else ThemeMode.DARK
    else -> error("baseMode always returns a non-Monet value")
}
