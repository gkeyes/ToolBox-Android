package io.toolbox.core.ui.component

import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.rememberUpdatedState
import io.toolbox.core.ui.liquid.LiquidBottomTab
import io.toolbox.core.ui.liquid.LiquidBottomTabs
import io.toolbox.core.ui.liquid.LiquidBottomTabsMetrics
import androidx.compose.ui.platform.LocalView
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.shapes.Capsule
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.core.ui.theme.readableForeground
import androidx.compose.ui.unit.Dp

@Composable
fun ToolBoxNavigationBar(
    items: List<ToolBoxNavigationItem>,
    selectedId: String,
    onItemSelected: (ToolBoxNavigationItem) -> Unit,
    modifier: Modifier = Modifier,
    glassState: ToolBoxGlassState? = null,
) {
    val usesIconOnlyLayout = LocalDensity.current.fontScale >= 1.5f
    val navigationShape = RoundedCornerShape(25.dp)
    val haptics = rememberToolBoxHaptics()
    val select: (ToolBoxNavigationItem) -> Unit = { item ->
        if (item.id != selectedId) haptics.tick()
        onItemSelected(item)
    }
    // Only when the page is really recorded: otherwise the tabs would refract an empty backdrop.
    if (glassState != null && glassRendersLive() && items.isNotEmpty()) {
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
    if (glassState != null && ToolBoxThemeTokens.materials.realBlurEnabled && items.isNotEmpty()) {
        // Standing in for the refracting bar while a transition pauses live glass (opening or
        // leaving a tool): same capsule, tab layout and selection, so only the material changes.
        StandInTabNavigationBar(
            items = items,
            selectedId = selectedId,
            onItemSelected = select,
            modifier = modifier,
            iconOnly = usesIconOnlyLayout,
        )
        return
    }
    LiquidGlassNavigationBar(
        items = items,
        selectedId = selectedId,
        onItemSelected = select,
        modifier = modifier,
        navigationShape = navigationShape,
    )
}

/**
 * [LiquidTabNavigationBar] at rest, without refraction: the same [LiquidBottomTabsMetrics]
 * capsule, tint, rim highlight and shadow (Kyant's own, over an empty backdrop, so the page shows
 * through unblurred), the resting 10% lens and icon-over-label tabs.
 */
@Composable
private fun StandInTabNavigationBar(
    items: List<ToolBoxNavigationItem>,
    selectedId: String,
    onItemSelected: (ToolBoxNavigationItem) -> Unit,
    modifier: Modifier,
    iconOnly: Boolean,
) {
    val colors = ToolBoxThemeTokens.colors
    val dark = colors.background.luminance() < 0.5f
    val tint = ToolBoxThemeTokens.materials.glassTint
    // As the live bar coerces it: an unknown id still shows the lens on the first tab.
    val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val surface = if (LocalView.current.isHardwareAccelerated) {
        Modifier.drawBackdrop(
            backdrop = emptyBackdrop(),
            shape = { Capsule() },
            effects = {},
            onDrawSurface = { drawRect(tint) },
        )
    } else {
        Modifier.toolBoxSolidGlass(Capsule())
    }
    BoxWithConstraints(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf, vertical = ToolBoxThemeTokens.spacing.compact)
            .fillMaxWidth()
            .height(LiquidBottomTabsMetrics.BarHeight)
            .then(surface)
            .padding(LiquidBottomTabsMetrics.BarPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        val itemWidth = maxWidth / items.size
        Box(
            Modifier
                .offset { IntOffset((itemWidth * selectedIndex).roundToPx(), 0) }
                .width(itemWidth)
                .height(LiquidBottomTabsMetrics.LensHeight)
                .clip(Capsule())
                .background(LiquidBottomTabsMetrics.restingLensInk(isLightTheme = !dark)),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            items.forEachIndexed { index, item ->
                key(item.id) {
                    val selected = index == selectedIndex
                    val tint = if (selected) colors.primary else colors.textPrimary
                    androidx.compose.foundation.layout.Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(Capsule())
                            .selectable(
                                selected = selected,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClick = { onItemSelected(item) },
                            )
                            .then(item.testTag?.let(Modifier::testTag) ?: Modifier)
                            .semantics { contentDescription = item.label },
                        verticalArrangement = Arrangement.spacedBy(LiquidBottomTabsMetrics.TabSpacing, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ToolBoxIcon(icon = item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                        if (!iconOnly) {
                            ToolBoxText(
                                text = item.label,
                                style = ToolBoxThemeTokens.textStyles.label.copy(
                                    color = tint,
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
    barHeight: Dp = 58.dp,
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
            .height(barHeight),
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
