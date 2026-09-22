package io.toolbox.host.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.data.ThemeStyle
import io.toolbox.core.ui.component.*
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
            onThemeStyleSelected = viewModel::selectThemeStyle,
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
    onThemeStyleSelected: (ThemeStyle) -> Unit = {},
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
                    if (state.canRetry) ToolBoxTextButton(label = "重试", onClick = onRetry)
                }
            }
            item("error-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }
        }
        item("preview") { AppearancePreview(settings.themeStyle, settings.theme) }
        item("preview-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("style-title") { SectionHeader("界面样式") }
        item("style-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("current-style") {
            ToolBoxGroupedSurface(Modifier.testTag(HostTestTags.AppearanceLiquidGlass)) {
                ThemeStyle.entries.forEachIndexed { index, style ->
                    ToolBoxRadioSettingRow(
                        title = style.displayLabel,
                        selected = settings.themeStyle == style,
                        onClick = { if (settings.themeStyle != style) onThemeStyleSelected(style) },
                        enabled = state.loaded,
                    )
                    if (index < ThemeStyle.entries.lastIndex) ToolBoxGroupDivider()
                }
            }
        }
        item("color-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("color-title") { SectionHeader("明暗模式") }
        item("color-title-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("color-settings") {
            ToolBoxGroupedSurface {
                listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEachIndexed { index, (mode, label) ->
                    ToolBoxRadioSettingRow(
                        title = label, selected = settings.theme.baseMode == mode,
                        onClick = { onThemeModeSelected(mode.withSystemColor(settings.theme.usesSystemColor)) },
                        enabled = state.loaded,
                        modifier = if (index == 0) Modifier.testTag(HostTestTags.AppearanceMode) else Modifier,
                    )
                    if (index < 2) ToolBoxGroupDivider()
                }
            }
        }
        item("system-color-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("system-color-title") {
            SectionHeader(if (settings.themeStyle == ThemeStyle.LIQUID_GLASS) "颜色与材质" else "颜色")
        }
        item("system-color-title-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("system-color-settings") {
            ToolBoxGroupedSurface {
                ToolBoxSwitchSettingRow(
                    title = "系统取色",
                    modifier = Modifier.testTag(HostTestTags.AppearanceSystemColor),
                    summary = "强调色跟随系统，底板保持中性",
                    checked = settings.theme.usesSystemColor,
                    onCheckedChange = { enabled -> onThemeModeSelected(settings.theme.baseMode.withSystemColor(enabled)) },
                    enabled = state.loaded,
                )
                if (settings.themeStyle == ThemeStyle.LIQUID_GLASS) {
                    ToolBoxGroupDivider(startPadding = ToolBoxThemeTokens.spacing.oneHalf)
                    ToolBoxSwitchSettingRow(
                        title = "降低透明度",
                        modifier = Modifier.testTag(HostTestTags.AppearanceReduceTransparency),
                        summary = "关闭实时模糊，使用实色材质",
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
                "更改自动保存，仅影响 ToolBox；小工具内部样式不变。",
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
                color = ToolBoxThemeTokens.colors.textSecondary,
            )
        }
    }
}

/** Uses the live theme's real card, type and color tokens; no second theme or animated mock UI. */
@Composable
private fun AppearancePreview(style: ThemeStyle, mode: ThemeMode) {
    val colors = ToolBoxThemeTokens.colors
    Column(Modifier.fillMaxWidth().testTag("appearance_preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("颜色与排版预览")
        SurfaceCard(Modifier.clearAndSetSemantics {
            contentDescription = "外观预览：${style.displayLabel}，${mode.baseLabel}"
        }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(colors.softPrimary, RoundedCornerShape(ToolBoxThemeTokens.radii.control)),
                    contentAlignment = Alignment.Center) {
                    ToolBoxIcon(ToolBoxIconKey.Tools, null, tint = colors.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText("工具名称", textStyle = ToolBoxThemeTokens.textStyles.title)
                    AppText("说明文字预览", textStyle = ToolBoxThemeTokens.textStyles.metadata, color = colors.textSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            ToolBoxGroupDivider(startPadding = 0.dp)
            Spacer(Modifier.height(12.dp))
            AppText("${style.displayLabel} · ${mode.baseLabel}", color = colors.primary,
                textStyle = ToolBoxThemeTokens.textStyles.metadata)
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
