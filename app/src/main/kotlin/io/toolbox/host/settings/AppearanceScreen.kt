package io.toolbox.host.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxMotion
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.rememberToolBoxHaptics
import io.toolbox.core.ui.component.toolBoxEntrance
import io.toolbox.core.ui.component.toolBoxShape
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.HostTestTags
import io.toolbox.host.ui.SectionHeader
import io.toolbox.host.ui.SurfaceCard
import io.toolbox.host.ui.mergePadding
import io.toolbox.host.ui.rememberTitleBoundListState

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

/**
 * The redesign has a single iOS look, so the old Miuix / Liquid Glass style picker is gone. The
 * stored `themeStyle` preference is still read, backed up and restored; it is just not offered.
 */
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
    val entrance = io.toolbox.core.ui.component.rememberToolBoxEntrance("appearance")
    LazyColumn(
        state = rememberTitleBoundListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        state.error?.let { error ->
            item("error") {
                SurfaceCard {
                    AppText(error, color = ToolBoxThemeTokens.colors.onSoftDanger)
                    if (state.canRetry) ToolBoxTextButton(label = "重试", onClick = onRetry)
                }
            }
            item("error-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }
        }
        item("preview") {
            Box(Modifier.toolBoxEntrance(entrance, 0)) {
                AppearancePreview(settings.theme, settings.theme.usesSystemColor, settings.reduceTransparency)
            }
        }
        item("color-title") {
            Box(Modifier.padding(top = 26.dp, bottom = 7.dp)) { SectionHeader("明暗模式") }
        }
        item("color-settings") {
            AppearanceModePicker(
                selected = settings.theme.baseMode,
                onSelect = { mode -> onThemeModeSelected(mode.withSystemColor(settings.theme.usesSystemColor)) },
                enabled = state.loaded,
                modifier = Modifier.toolBoxEntrance(entrance, 1),
            )
        }
        item("system-color-title") {
            Box(Modifier.padding(top = 26.dp, bottom = 7.dp)) { SectionHeader("颜色与材质") }
        }
        item("system-color-settings") {
            ToolBoxGroupedSurface(Modifier.toolBoxEntrance(entrance, 2)) {
                SettingsSwitchRow(
                    title = "系统取色",
                    modifier = Modifier.testTag(HostTestTags.AppearanceSystemColor),
                    summary = "强调色跟随系统，底板保持中性",
                    checked = settings.theme.usesSystemColor,
                    onCheckedChange = { enabled -> onThemeModeSelected(settings.theme.baseMode.withSystemColor(enabled)) },
                    icon = ToolBoxIconKey.Palette,
                    tint = SettingsIconTint.Aqua,
                    enabled = state.loaded,
                )
                SettingsRowSeparator()
                SettingsSwitchRow(
                    title = "降低透明度",
                    modifier = Modifier.testTag(HostTestTags.AppearanceReduceTransparency),
                    summary = "关闭实时模糊，使用实色材质",
                    checked = settings.reduceTransparency,
                    onCheckedChange = onReduceTransparencyChanged,
                    icon = ToolBoxIconKey.Device,
                    tint = SettingsIconTint.Graphite,
                    enabled = state.loaded,
                )
            }
        }
        item("footer") {
            SettingsFooter("更改自动保存，仅影响 ToolBox；小工具内部样式不变。")
        }
    }
}

/** Uses the live theme's real accent, surfaces and type; the blobs only show the material. */
@Composable
private fun AppearancePreview(mode: ThemeMode, systemColor: Boolean, reduceTransparency: Boolean) {
    val colors = ToolBoxThemeTokens.colors
    val materials = ToolBoxThemeTokens.materials
    val dark = colors.background.luminance() < 0.5f
    val blobAlpha = if (dark) 0.55f else 0.8f
    val materialLabel = if (reduceTransparency) "实色材质" else "透明材质"
    val accent = colors.primary
    val panelShape = toolBoxShape(22.dp)
    Column(Modifier.fillMaxWidth().testTag("appearance_preview")) {
        Box(Modifier.padding(bottom = 7.dp)) { SectionHeader("颜色与排版预览") }
        Box(
            Modifier
                .fillMaxWidth()
                .height(156.dp)
                .clip(toolBoxShape(ToolBoxThemeTokens.radii.card))
                .background(colors.surface)
                .drawBehind {
                    val unit = 1.dp.toPx()
                    val blobs = listOf(
                        Triple(Color(0xFFFFC93C), Offset(65f * unit, 55f * unit), 95f * unit),
                        Triple(Color(0xFF7C82FF), Offset(size.width - 65f * unit, 85f * unit), 105f * unit),
                        Triple(Color(0xFF2ECBCB), Offset(210f * unit, size.height + 10f * unit), 100f * unit),
                    )
                    blobs.forEach { (color, center, radius) ->
                        drawCircle(
                            Brush.radialGradient(
                                listOf(color.copy(alpha = blobAlpha), color.copy(alpha = 0f)),
                                center = center,
                                radius = radius,
                            ),
                            radius = radius,
                            center = center,
                        )
                    }
                }
                .clearAndSetSemantics {
                    contentDescription = "外观预览：${mode.baseLabel}，$materialLabel"
                },
        ) {
            Row(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 22.dp)
                    .fillMaxWidth()
                    .heightIn(min = 76.dp)
                    .clip(panelShape)
                    .background(if (reduceTransparency) colors.raised else materials.glassTint)
                    .border(0.5.dp, if (reduceTransparency) Color.Transparent else materials.glassBorder, panelShape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(toolBoxShape(12.dp))
                        .background(Brush.linearGradient(listOf(lerp(accent, Color.White, 0.3f), accent))),
                    contentAlignment = Alignment.Center,
                ) {
                    ToolBoxIcon(ToolBoxIconKey.Tools, null, Modifier.size(23.dp), tint = Color.White)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppText("工具名称", textStyle = ToolBoxThemeTokens.textStyles.headline, maxLines = 1)
                    AppText(
                        "说明文字预览",
                        textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
                Box(
                    Modifier
                        .heightIn(min = 30.dp)
                        .clip(toolBoxShape(15.dp))
                        .background(accent)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        "打开",
                        color = colors.onPrimary,
                        weight = FontWeight.SemiBold,
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                    )
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    "${mode.baseLabel} · ${if (systemColor) "系统取色" else "默认蓝"}",
                    modifier = Modifier.weight(1f),
                    color = accent,
                    weight = FontWeight.SemiBold,
                    textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
                )
                AppText(materialLabel, color = colors.textSecondary, textStyle = ToolBoxThemeTokens.textStyles.sectionTitle)
            }
        }
    }
}

/** Three phone thumbnails (system, light, dark) acting as a radio group, as in iOS Display settings. */
@Composable
private fun AppearanceModePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色")
    val haptics = rememberToolBoxHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .clip(toolBoxShape(ToolBoxThemeTokens.radii.card))
            .background(ToolBoxThemeTokens.colors.surface)
            .padding(start = 10.dp, end = 10.dp, top = 20.dp, bottom = 16.dp)
            .selectableGroup(),
    ) {
        modes.forEachIndexed { index, (mode, label) ->
            val isSelected = mode == selected
            Column(
                Modifier
                    .weight(1f)
                    .then(if (index == 0) Modifier.testTag(HostTestTags.AppearanceMode) else Modifier)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (!isSelected) {
                                haptics.tick()
                                onSelect(mode)
                            }
                        },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ModeThumbnail(mode, isSelected)
                AppText(
                    label,
                    weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textStyle = ToolBoxThemeTokens.textStyles.metadata,
                    maxLines = 1,
                )
                ModeCheck(isSelected)
            }
        }
    }
}

private data class ThumbnailPalette(
    val background: Color,
    val card: Color,
    val ink: Color,
    val faint: Color,
    val glass: Color,
)

private val LightThumbnail = ThumbnailPalette(
    background = Color(0xFFF2F2F7),
    card = Color.White,
    ink = Color.Black.copy(alpha = 0.78f),
    faint = Color(0x473C3C43),
    glass = Color.White.copy(alpha = 0.85f),
)

private val DarkThumbnail = ThumbnailPalette(
    background = Color.Black,
    card = Color(0xFF2C2C2E),
    ink = Color.White.copy(alpha = 0.86f),
    faint = Color(0x4DEBEBF5),
    glass = Color(0xBF58585C),
)

/** Lower-right triangle: the dark half of the "follow system" thumbnail. */
private val DiagonalHalf = GenericShape { size, _ ->
    moveTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

@Composable
private fun ModeThumbnail(mode: ThemeMode, selected: Boolean) {
    val colors = ToolBoxThemeTokens.colors
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = ToolBoxMotion.spatial(),
        label = "mode thumbnail",
    )
    val ring by animateColorAsState(
        targetValue = if (selected) colors.primary else Color.Transparent,
        animationSpec = ToolBoxMotion.effects(),
        label = "mode ring",
    )
    Box(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(2.5.dp, ring, toolBoxShape(20.dp))
            .padding(3.dp)
            .size(width = 76.dp, height = 150.dp)
            .clip(toolBoxShape(17.dp)),
    ) {
        when (mode) {
            ThemeMode.DARK -> ThumbnailScreen(DarkThumbnail)
            ThemeMode.LIGHT -> ThumbnailScreen(LightThumbnail)
            else -> {
                ThumbnailScreen(LightThumbnail)
                Box(Modifier.matchParentSize().clip(DiagonalHalf)) { ThumbnailScreen(DarkThumbnail) }
            }
        }
    }
}

@Composable
private fun ThumbnailScreen(palette: ThumbnailPalette) {
    val accent = ToolBoxThemeTokens.colors.primary
    val small = toolBoxShape(8.dp)
    Box(Modifier.fillMaxSize().background(palette.background)) {
        Column(
            Modifier.fillMaxSize().padding(start = 7.dp, end = 7.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(13.dp, 3.dp).clip(CircleShape).background(palette.faint))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(22.dp, 7.dp).clip(CircleShape).background(Color.Black))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(9.dp, 3.dp).clip(CircleShape).background(palette.faint))
            }
            Box(Modifier.padding(top = 8.dp).size(34.dp, 6.dp).clip(CircleShape).background(palette.ink))
            Row(
                Modifier.fillMaxWidth().height(34.dp).clip(small).background(palette.card).padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(13.dp).clip(toolBoxShape(4.dp)).background(accent))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.fillMaxWidth(0.8f).height(3.dp).clip(CircleShape).background(palette.ink))
                    Box(Modifier.fillMaxWidth(0.55f).height(3.dp).clip(CircleShape).background(palette.faint))
                }
            }
            Row(
                Modifier.fillMaxWidth().height(24.dp).clip(small).background(palette.card).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf(SettingsIconTint.Amber, SettingsIconTint.Mint, SettingsIconTint.Tangerine, SettingsIconTint.Purple)
                    .forEach { tint ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(toolBoxShape(3.dp))
                                .background(Brush.verticalGradient(listOf(tint.top, tint.bottom))),
                        )
                    }
            }
        }
        Box(
            Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 22.dp, bottom = 8.dp)
                .fillMaxWidth().height(11.dp).clip(CircleShape).background(palette.glass),
        )
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp)
                .size(11.dp).clip(CircleShape).background(palette.glass),
        )
    }
}

@Composable
private fun ModeCheck(selected: Boolean) {
    val colors = ToolBoxThemeTokens.colors
    val fill by animateColorAsState(
        targetValue = if (selected) colors.primary else Color.Transparent,
        animationSpec = ToolBoxMotion.effects(),
        label = "mode check",
    )
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(fill)
            .then(if (selected) Modifier else Modifier.border(1.5.dp, colors.textTertiary, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) ToolBoxIcon(ToolBoxIconKey.Check, null, Modifier.size(14.dp), tint = colors.onPrimary)
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
