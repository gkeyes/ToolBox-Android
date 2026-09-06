package io.toolbox.host.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.data.ThemeStyle
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxChoiceSettingRow
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxSettingChoice
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
            onThemeStyleSelected = viewModel::selectThemeStyle,
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
    onThemeStyleSelected: (ThemeStyle) -> Unit,
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

        item("style-title") { SectionHeader("界面风格") }
        item("style-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("style-previews") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
            ) {
                ThemeStylePreview(
                    style = ThemeStyle.MIUIX,
                    selected = settings.themeStyle == ThemeStyle.MIUIX,
                    enabled = state.loaded,
                    onClick = { onThemeStyleSelected(ThemeStyle.MIUIX) },
                    modifier = Modifier.weight(1f),
                )
                ThemeStylePreview(
                    style = ThemeStyle.LIQUID_GLASS,
                    selected = settings.themeStyle == ThemeStyle.LIQUID_GLASS,
                    enabled = state.loaded,
                    onClick = { onThemeStyleSelected(ThemeStyle.LIQUID_GLASS) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item("color-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
        item("color-title") { SectionHeader("明暗与颜色") }
        item("color-title-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
        item("color-settings") {
            ToolBoxGroupedSurface {
                ToolBoxChoiceSettingRow(
                    title = "明暗模式",
                    modifier = Modifier.testTag(HostTestTags.AppearanceMode),
                    selectedValue = settings.theme.baseMode.name,
                    choices = listOf(
                        ToolBoxSettingChoice(ThemeMode.SYSTEM.name, "跟随系统"),
                        ToolBoxSettingChoice(ThemeMode.LIGHT.name, "浅色"),
                        ToolBoxSettingChoice(ThemeMode.DARK.name, "深色"),
                    ),
                    onSelected = { selected ->
                        onThemeModeSelected(
                            ThemeMode.valueOf(selected).withSystemColor(settings.theme.usesSystemColor),
                        )
                    },
                    enabled = state.loaded,
                )
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

        if (settings.themeStyle == ThemeStyle.LIQUID_GLASS) {
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

@Composable
private fun ThemeStylePreview(
    style: ThemeStyle,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ToolBoxThemeTokens.colors
    val radius = if (style == ThemeStyle.LIQUID_GLASS) 22.dp else 14.dp
    val selectionColor = if (selected) colors.primary else colors.divider
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ToolBoxCard(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .testTag(
                    if (style == ThemeStyle.MIUIX) HostTestTags.AppearanceMiuix
                    else HostTestTags.AppearanceLiquidGlass,
                )
                .border(if (selected) 2.dp else 1.dp, selectionColor, RoundedCornerShape(radius))
                .semantics { this.selected = selected },
            contentPadding = PaddingValues(8.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clip(RoundedCornerShape(radius - 4.dp))
                    .background(if (style == ThemeStyle.LIQUID_GLASS) colors.background else colors.surfaceMuted),
            ) {
                Column(Modifier.fillMaxSize().padding(10.dp)) {
                    Box(
                        Modifier
                            .width(if (style == ThemeStyle.LIQUID_GLASS) 64.dp else 52.dp)
                            .height(if (style == ThemeStyle.LIQUID_GLASS) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(colors.textPrimary.copy(alpha = 0.78f)),
                    )
                    Spacer(Modifier.height(10.dp))
                    repeat(3) { index ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                                .clip(RoundedCornerShape(if (style == ThemeStyle.LIQUID_GLASS) 8.dp else 5.dp))
                                .background(colors.surface.copy(alpha = if (style == ThemeStyle.LIQUID_GLASS) 0.88f else 1f))
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (index == 0) colors.primary else colors.divider))
                            Spacer(Modifier.width(5.dp))
                            Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(colors.textSecondary.copy(alpha = 0.45f)))
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (style == ThemeStyle.LIQUID_GLASS) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp)
                            .fillMaxWidth()
                            .height(20.dp)
                            .clip(CircleShape)
                            .background(colors.surface.copy(alpha = 0.72f))
                            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        AppText(
            style.label,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
            color = if (selected) colors.primary else colors.textSecondary,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
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
