package io.toolbox.core.ui.component

import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.rememberUpdatedState
import io.toolbox.core.ui.liquid.LiquidBottomTab
import io.toolbox.core.ui.liquid.LiquidBottomTabs
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.core.ui.theme.readableForeground
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.utils.PressFeedbackType

data class ToolBoxNavigationItem(
    val id: String,
    val label: String,
    val icon: ToolBoxIconKey,
    val testTag: String? = null,
)

@Composable
fun ToolBoxAppScaffold(
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val hasTopBar = topBar != null
    val hasBottomBar = bottomBar != null
    Scaffold(
        modifier = modifier.toolBoxOpenDesignCanvas(),
        topBar = {
            topBar?.invoke()
        },
        bottomBar = {
            bottomBar?.invoke()
        },
        floatingActionButton = {
            floatingActionButton?.invoke()
        },
        containerColor = Color.Transparent,
        contentWindowInsets = toolBoxScaffoldContentInsets(
            hasTopBar = hasTopBar,
            hasBottomBar = hasBottomBar,
        ),
        content = content,
    )
}

@Composable
fun ToolBoxRuntimeScaffold(
    modifier: Modifier = Modifier,
    immersiveNavigationBar: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    var insets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    if (!immersiveNavigationBar) {
        insets = insets.union(WindowInsets.navigationBars)
    }
    Box(
        modifier = modifier
            .background(ToolBoxThemeTokens.colors.background)
            .windowInsetsPadding(insets),
        content = content,
    )
}

@Composable
private fun toolBoxScaffoldContentInsets(
    hasTopBar: Boolean,
    hasBottomBar: Boolean,
): WindowInsets {
    var insets = WindowInsets(0, 0, 0, 0)
    if (!hasTopBar) {
        insets = insets
            .union(WindowInsets.statusBars)
            .union(WindowInsets.displayCutout)
    }
    if (!hasBottomBar) {
        insets = insets.union(WindowInsets.navigationBars)
    }
    return insets
}

@Composable
fun ToolBoxTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    navigationIcon: ToolBoxIconKey? = null,
    navigationContentDescription: String = "返回",
    onNavigationClick: (() -> Unit)? = null,
    glassState: ToolBoxGlassState? = null,
    windowInsets: WindowInsets = WindowInsets.statusBars.union(
        WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
    ),
    titleAlpha: () -> Float = { 1f },
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = ToolBoxThemeTokens.colors
    val titleLines = if (LocalDensity.current.fontScale >= 1.3f) 2 else 1
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(windowInsets)
            .heightIn(min = ToolBoxThemeTokens.sizes.topBar)
            .padding(horizontal = ToolBoxThemeTokens.spacing.xs, vertical = ToolBoxThemeTokens.spacing.xxs),
        content = {
            Box {
                if (navigationIcon != null && onNavigationClick != null) {
                    ToolBoxIconButton(
                        navigationIcon, navigationContentDescription, onNavigationClick,
                        tint = colors.primary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            Column(
                Modifier.graphicsLayer { alpha = titleAlpha() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.animation.Crossfade(title, animationSpec = ToolBoxMotion.effects(), label = "bar title") { text ->
                    ToolBoxText(
                        text,
                        style = ToolBoxThemeTokens.textStyles.collapsedTitle.copy(
                            textAlign = TextAlign.Center, color = colors.textPrimary,
                        ),
                        maxLines = titleLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (subtitle.isNotBlank()) {
                    ToolBoxText(
                        subtitle,
                        style = ToolBoxThemeTokens.textStyles.metadata.copy(
                            textAlign = TextAlign.Center, color = colors.textSecondary,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val navigation = measurables[0].measure(loose)
        val actionsPlaceable = measurables[1].measure(
            loose.copy(maxWidth = (constraints.maxWidth - navigation.width).coerceAtLeast(0)),
        )
        val gap = 8.dp.roundToPx()
        val sideWidth = maxOf(navigation.width, actionsPlaceable.width)
        val centeredWidth = (constraints.maxWidth - 2 * (sideWidth + gap)).coerceAtLeast(0)
        // Stay screen-centered when possible; on narrow screens use the space
        // between the actual controls instead of letting the title draw under them.
        val centered = centeredWidth >= 80.dp.roundToPx()
        val titleWidth = if (centered) centeredWidth else {
            (constraints.maxWidth - navigation.width - actionsPlaceable.width - 2 * gap).coerceAtLeast(0)
        }
        val titlePlaceable = measurables[2].measure(loose.copy(minWidth = titleWidth, maxWidth = titleWidth))
        val height = maxOf(navigation.height, actionsPlaceable.height, titlePlaceable.height)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val titleStart = if (centered) sideWidth + gap else navigation.width + gap
        layout(constraints.maxWidth, height) {
            navigation.placeRelative(0, (height - navigation.height) / 2)
            actionsPlaceable.placeRelative(
                constraints.maxWidth - actionsPlaceable.width,
                (height - actionsPlaceable.height) / 2,
            )
            titlePlaceable.placeRelative(titleStart, (height - titlePlaceable.height) / 2)
        }
    }
}

/** Top-level screen bar: large title that collapses into the 17sp bar title on scroll. */
@Composable
fun ToolBoxLargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    defaultWindowInsetsPadding: Boolean = true,
    glassState: ToolBoxGlassState? = null,
    compact: Boolean = false,
    scrolledPx: (() -> Float)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    ToolBoxCollapsingTopBar(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        scrolledPx = scrolledPx,
        glassState = glassState,
        statusBarPadding = defaultWindowInsetsPadding,
        actions = actions,
    )
}

@Composable
fun ToolBoxNavigationBar(
    items: List<ToolBoxNavigationItem>,
    selectedId: String,
    onItemSelected: (ToolBoxNavigationItem) -> Unit,
    modifier: Modifier = Modifier,
    glassState: ToolBoxGlassState? = null,
) {
    val usesIconOnlyLayout = LocalDensity.current.fontScale >= 1.5f
    val isGlass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass
    val navigationShape = RoundedCornerShape(25.dp)
    val haptics = rememberToolBoxHaptics()
    val select: (ToolBoxNavigationItem) -> Unit = { item ->
        if (item.id != selectedId) haptics.tick()
        onItemSelected(item)
    }
    if (isGlass && glassState != null && ToolBoxThemeTokens.materials.realBlurEnabled && items.isNotEmpty()) {
        LiquidTabNavigationBar(
            items = items,
            selectedId = selectedId,
            onItemSelected = select,
            modifier = modifier,
            glassState = glassState,
            iconOnly = usesIconOnlyLayout,
        )
        return
    }
    if (isGlass) {
        LiquidGlassNavigationBar(
            items = items,
            selectedId = selectedId,
            onItemSelected = select,
            modifier = modifier,
            navigationShape = navigationShape,
        )
        return
    }
    val navigationModifier = when {
        glassState != null -> modifier
            .toolBoxGlassEffect(glassState, navigationShape)
        else -> modifier
    }
    NavigationBar(
        modifier = navigationModifier,
        color = ToolBoxThemeTokens.colors.surface,
        showDivider = false,
        defaultWindowInsetsPadding = true,
        mode = if (usesIconOnlyLayout) {
            NavigationBarDisplayMode.IconOnly
        } else {
            NavigationBarDisplayMode.IconAndText
        },
    ) {
        items.forEach { item ->
            key(item.id) {
                NavigationBarItem(
                    selected = item.id == selectedId,
                    onClick = { select(item) },
                    icon = item.icon.asImageVector(),
                    label = item.label,
                    modifier = item.testTag?.let(Modifier::testTag) ?: Modifier,
                )
            }
        }
    }
}

/**
 * iOS 27 floating tab bar: the vendored Kyant [LiquidBottomTabs] capsule refracting the page
 * behind it, with a draggable lens that morphs between tabs.
 */
@Composable
private fun LiquidTabNavigationBar(
    items: List<ToolBoxNavigationItem>,
    selectedId: String,
    onItemSelected: (ToolBoxNavigationItem) -> Unit,
    modifier: Modifier,
    glassState: ToolBoxGlassState,
    iconOnly: Boolean,
) {
    val colors = ToolBoxThemeTokens.colors
    val dark = colors.background.luminance() < 0.5f
    val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val currentItems by rememberUpdatedState(items)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    LiquidBottomTabs(
        selectedTabIndex = { currentSelectedIndex },
        onTabSelected = { index -> currentItems.getOrNull(index)?.let(onItemSelected) },
        backdrop = glassState.backdrop,
        tabsCount = items.size,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf, vertical = ToolBoxThemeTokens.spacing.compact)
            .selectableGroup(),
        isLightTheme = !dark,
        accentColor = colors.primary,
        containerColor = ToolBoxThemeTokens.materials.glassTint,
    ) {
        items.forEachIndexed { index, item ->
            key(item.id) {
                LiquidBottomTab(
                    onClick = { onItemSelected(item) },
                    modifier = Modifier
                        .then(item.testTag?.let(Modifier::testTag) ?: Modifier)
                        .semantics {
                            contentDescription = item.label
                            this.selected = index == selectedIndex
                        },
                ) {
                    ToolBoxIcon(
                        icon = item.icon,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                    if (!iconOnly) {
                        ToolBoxText(
                            text = item.label,
                            style = ToolBoxThemeTokens.textStyles.label.copy(
                                color = colors.textPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassNavigationBar(
    items: List<ToolBoxNavigationItem>,
    selectedId: String,
    onItemSelected: (ToolBoxNavigationItem) -> Unit,
    modifier: Modifier,
    navigationShape: RoundedCornerShape,
) {
    if (items.isEmpty()) return
    val surfaceModifier = modifier
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(
            horizontal = ToolBoxThemeTokens.spacing.oneHalf,
            vertical = ToolBoxThemeTokens.spacing.compact,
        )
        // Bottom chrome must keep one material while pages move. A live Haze
        // backdrop can briefly fall back while the pager re-layers content,
        // which visibly shifts the whole navigation bar from tinted glass to white.
        // Keep the bar itself deterministic; the selection lens still animates.
        .toolBoxSolidGlass(navigationShape)

    BoxWithConstraints(
        modifier = surfaceModifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        val itemWidth = maxWidth / items.size
        val selectedIndex = items.indexOfFirst { it.id == selectedId }
        val selectionOffset = animateDpAsState(
            targetValue = itemWidth * selectedIndex.coerceAtLeast(0),
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
            label = "navigation selection lens",
        )
        val materials = ToolBoxThemeTokens.materials
        val selectionShape = RoundedCornerShape(20.dp)
        if (materials.navigationLensEnabled && selectedIndex >= 0) {
            Box(
                Modifier
                    // Read animation state in placement, not composition. offset also mirrors in RTL.
                    .offset { IntOffset(selectionOffset.value.roundToPx(), 0) }
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 5.dp, vertical = 5.dp)
                    .clip(selectionShape)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(
                                materials.navigationSelectionHighlight,
                                materials.navigationSelectionTint,
                                materials.navigationSelectionTint,
                            ),
                        ),
                    )
                    .border(0.75.dp, materials.navigationSelectionBorder, selectionShape),
            )
        }
        Row(
            modifier = Modifier.fillMaxSize().selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                key(item.id) {
                    LiquidGlassNavigationItem(
                        item = item,
                        selected = item.id == selectedId,
                        onClick = { onItemSelected(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.LiquidGlassNavigationItem(
    item: ToolBoxNavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressFeedbackEnabled = ToolBoxThemeTokens.materials.pressFeedbackEnabled
    val contentScale = animateFloatAsState(
        targetValue = if (pressed && pressFeedbackEnabled) 0.97f else 1f,
        animationSpec = ToolBoxMotion.pressSpec(pressed),
        label = "navigation item press",
    )
    val colors = ToolBoxThemeTokens.colors
    val color = if (selected) {
        io.toolbox.core.ui.theme.readableForeground(
            colors.primary,
            listOf(ToolBoxThemeTokens.materials.navigationSelectionTint, colors.surface),
        )
    } else colors.textSecondary
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .then(item.testTag?.let(Modifier::testTag) ?: Modifier)
            .semantics { contentDescription = item.label },
        contentAlignment = Alignment.Center,
    ) {
        // Only the visual content scales; the tab's hit target stays full-sized.
        Row(
            modifier = Modifier.graphicsLayer {
                scaleX = contentScale.value
                scaleY = contentScale.value
            },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBoxIcon(
                icon = item.icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            if (LocalDensity.current.fontScale < 1.5f) {
                Spacer(Modifier.width(7.dp))
                ToolBoxText(
                    text = item.label,
                    style = ToolBoxThemeTokens.textStyles.label.copy(
                        color = color,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ToolBoxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(ToolBoxThemeTokens.spacing.two),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.toolBoxOpenDesignSurface(toolBoxShape(ToolBoxThemeTokens.radii.card)).then(
            if (onClick != null || onLongClick != null) {
                Modifier.sizeIn(minHeight = ToolBoxThemeTokens.sizes.touchTarget)
            } else {
                Modifier
            },
        ),
        cornerRadius = ToolBoxThemeTokens.radii.card,
        insideMargin = contentPadding,
        colors = CardColors(
            color = Color.Transparent,
            contentColor = ToolBoxThemeTokens.colors.textPrimary,
        ),
        onClick = onClick,
        onLongPress = onLongClick,
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = onClick != null || onLongClick != null,
        content = content,
    )
}

@Composable
fun ToolBoxGroupedSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().toolBoxOpenDesignSurface(toolBoxShape(ToolBoxThemeTokens.radii.card)),
        cornerRadius = ToolBoxThemeTokens.radii.card,
        insideMargin = PaddingValues(0.dp),
        colors = CardColors(
            color = Color.Transparent,
            contentColor = ToolBoxThemeTokens.colors.textPrimary,
        ),
        content = content,
    )
}

@Composable
fun ToolBoxGroupDivider(
    modifier: Modifier = Modifier,
    startPadding: Dp = 60.dp,
    endPadding: Dp = 0.dp,
) {
    // Design system v2: group cards never draw separators; rows separate by 56dp height and inset.
    Unit
}

private enum class ToolBoxActionTone { Primary, Tonal, Neutral, Danger, DangerConfirmation }

private data class ToolBoxActionColors(val container: Color, val content: Color)

/** WCAG contrast between two opaque colors. */
private fun actionContrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * Solid fills keep the accent's hue and saturation and only lose brightness until white labels
 * reach 4.5:1, so dark mode gets a deep, saturated button instead of a washed-out tint.
 */
private fun solidActionColors(fill: Color): ToolBoxActionColors {
    if (fill.luminance() > 0.45f) return ToolBoxActionColors(fill, Color(0xFF111214))
    if (actionContrast(fill, Color.White) >= 4.5f) return ToolBoxActionColors(fill, Color.White)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(fill.toArgb(), hsv)
    hsv[1] = (hsv[1] + (1f - hsv[1]) * 0.35f).coerceAtMost(1f)
    var candidate = fill
    while (hsv[2] > 0.2f) {
        hsv[2] -= 0.01f
        candidate = Color(android.graphics.Color.HSVToColor(hsv))
        if (actionContrast(candidate, Color.White) >= 4.5f) break
    }
    return ToolBoxActionColors(candidate, Color.White)
}

@Composable
private fun toolBoxActionColors(tone: ToolBoxActionTone, contentColorOverride: Color?): ToolBoxActionColors {
    val colors = ToolBoxThemeTokens.colors
    val dark = colors.surface.luminance() < 0.5f
    val accent = colors.primary
    return when (tone) {
        ToolBoxActionTone.Primary -> solidActionColors(accent)
        ToolBoxActionTone.DangerConfirmation -> solidActionColors(if (dark) Color(0xFFD13438) else colors.danger)
        ToolBoxActionTone.Tonal -> {
            val container = accent.copy(alpha = if (dark) 0.22f else 0.12f).compositeOver(colors.surface)
            val content = contentColorOverride?.takeUnless { it == accent }
                ?: if (dark) lerp(accent, Color.White, 0.3f) else accent
            ToolBoxActionColors(container, readableForeground(content, listOf(container)))
        }
        ToolBoxActionTone.Neutral -> {
            val container = if (dark) Color.White.copy(alpha = 0.07f).compositeOver(colors.surfaceMuted) else colors.surfaceMuted
            ToolBoxActionColors(container, readableForeground(contentColorOverride ?: colors.textPrimary, listOf(container)))
        }
        ToolBoxActionTone.Danger -> {
            val container = colors.softDanger
            ToolBoxActionColors(container, readableForeground(colors.onSoftDanger, listOf(container)))
        }
    }
}

@Composable
private fun ToolBoxActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ToolBoxActionTone,
    contentColorOverride: Color? = null,
    icon: ToolBoxIconKey? = null,
    iconColorOverride: Color? = null,
    outlined: Boolean = true,
    compact: Boolean = false,
) {
    val colors = ToolBoxThemeTokens.colors
    val filled = outlined
    val palette = if (filled) {
        toolBoxActionColors(tone, contentColorOverride)
    } else {
        val preferred = contentColorOverride ?: when (tone) {
            ToolBoxActionTone.Danger, ToolBoxActionTone.DangerConfirmation -> colors.danger
            ToolBoxActionTone.Neutral -> colors.textPrimary
            else -> colors.primary
        }
        ToolBoxActionColors(Color.Transparent, readableForeground(preferred, listOf(colors.surface)))
    }
    val emphasized = tone == ToolBoxActionTone.Primary || tone == ToolBoxActionTone.DangerConfirmation
    val shape = toolBoxShape(ToolBoxThemeTokens.radii.button)
    val haptics = rememberToolBoxHaptics()

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (enabled && pressed) 1f else 0f,
        animationSpec = ToolBoxMotion.pressSpec(pressed),
        label = "toolbox-action-press",
    )
    // A state layer tinted by the label color reads in both themes, unlike a grey wash.
    val pressedOverlay = palette.content.copy(alpha = (if (filled && emphasized) 0.14f else 0.10f) * press.coerceIn(0f, 1f))
    val background = if (filled) pressedOverlay.compositeOver(palette.container) else pressedOverlay

    val labelStyle = if (compact) {
        ToolBoxThemeTokens.textStyles.metadata.copy(fontWeight = FontWeight.Medium)
    } else {
        ToolBoxThemeTokens.textStyles.body.copy(
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.1.sp,
        )
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp)
            .heightIn(
                min = when {
                    compact -> 40.dp
                    filled && emphasized -> ToolBoxThemeTokens.sizes.primaryButton
                    else -> ToolBoxThemeTokens.sizes.touchTarget
                },
            )
            .graphicsLayer {
                val scale = 1f - (1f - ToolBoxMotion.PressScale) * press
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.42f
            }
            .clip(shape)
            .background(background)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (emphasized || tone == ToolBoxActionTone.Danger) haptics.confirm()
                    onClick()
                },
            )
            .padding(
                horizontal = if (compact) 12.dp else if (filled) 20.dp else 16.dp,
                vertical = if (compact) 7.dp else 12.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                ToolBoxIcon(
                    icon = icon,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 13.dp else 18.dp),
                    tint = iconColorOverride ?: palette.content,
                )
                Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
            }
            ToolBoxText(
                text = label,
                style = labelStyle.copy(color = palette.content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ToolBoxPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    ToolBoxActionButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tone = if (destructive) ToolBoxActionTone.DangerConfirmation else ToolBoxActionTone.Primary,
    )
}

@Composable
fun ToolBoxSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Secondary actions are tonal: accent at 12% behind an accent label.
    ToolBoxActionButton(label, onClick, modifier, enabled, ToolBoxActionTone.Tonal)
}

@Composable
fun ToolBoxDestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ToolBoxActionButton(label, onClick, modifier, enabled, ToolBoxActionTone.Danger)
}

/** Standalone actions use a light material surface; embedded text actions stay minimal. */
@Composable
fun ToolBoxTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = ToolBoxThemeTokens.colors.primary,
    outlined: Boolean = true,
) {
    ToolBoxActionButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tone = when (contentColor) {
            ToolBoxThemeTokens.colors.onSoftDanger, ToolBoxThemeTokens.colors.danger -> ToolBoxActionTone.Danger
            ToolBoxThemeTokens.colors.primary -> ToolBoxActionTone.Tonal
            else -> ToolBoxActionTone.Neutral
        },
        contentColorOverride = contentColor,
        outlined = outlined,
    )
}

@Composable
fun ToolBoxRunningStatusButton(
    stopping: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ToolBoxActionButton(
        label = if (stopping) "停止中" else "停止",
        onClick = onClick,
        modifier = modifier.sizeIn(minWidth = ToolBoxThemeTokens.sizes.touchTarget),
        enabled = enabled,
        tone = ToolBoxActionTone.Neutral,
        icon = ToolBoxIconKey.Stop,
        iconColorOverride = ToolBoxThemeTokens.colors.onSoftDanger,
        compact = true,
    )
}
