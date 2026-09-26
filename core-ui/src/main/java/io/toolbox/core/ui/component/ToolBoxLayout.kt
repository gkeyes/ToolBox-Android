package io.toolbox.core.ui.component

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.squircle.squircleBorder
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
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .background(ToolBoxThemeTokens.colors.background)
            .windowInsetsPadding(
                WindowInsets.statusBars
                    .union(WindowInsets.navigationBars)
                    .union(WindowInsets.displayCutout),
            ),
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
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = ToolBoxThemeTokens.colors
    val titleLines = if (LocalDensity.current.fontScale >= 1.3f) 2 else 1
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.statusBars.union(
                    WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
                ),
            )
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        content = {
            Box {
                if (navigationIcon != null && onNavigationClick != null) {
                    ToolBoxIconButton(
                        navigationIcon, navigationContentDescription, onNavigationClick,
                        modifier = Modifier.toolBoxOpenDesignSurface(CircleShape),
                        tint = colors.primary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ToolBoxText(
                    title,
                    style = ToolBoxThemeTokens.textStyles.title.copy(
                        fontSize = 18.sp, lineHeight = 24.sp,
                        textAlign = TextAlign.Center, color = colors.textPrimary,
                    ),
                    maxLines = titleLines,
                    overflow = TextOverflow.Ellipsis,
                )
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

@Composable
fun ToolBoxLargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    defaultWindowInsetsPadding: Boolean = true,
    glassState: ToolBoxGlassState? = null,
    compact: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val isGlass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass
    val barModifier = modifier
    if (isGlass) {
        Row(
            modifier = barModifier
                .fillMaxWidth()
                .then(
                    if (defaultWindowInsetsPadding) Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    else Modifier,
                )
                .heightIn(min = if (compact) 60.dp else 76.dp)
                .padding(
                    start = ToolBoxThemeTokens.spacing.two,
                    top = if (compact) ToolBoxThemeTokens.spacing.compact else ToolBoxThemeTokens.spacing.one,
                    end = ToolBoxThemeTokens.spacing.one,
                    bottom = if (compact) ToolBoxThemeTokens.spacing.compact else ToolBoxThemeTokens.spacing.one,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                ToolBoxText(
                    text = title,
                    style = ToolBoxThemeTokens.textStyles.screenTitle.copy(
                        fontSize = if (compact) 28.sp else ToolBoxThemeTokens.textStyles.screenTitle.fontSize,
                        lineHeight = if (compact) 34.sp else ToolBoxThemeTokens.textStyles.screenTitle.lineHeight,
                        color = ToolBoxThemeTokens.colors.textPrimary,
                    ),
                )
                if (subtitle.isNotBlank()) {
                    ToolBoxText(
                        text = subtitle,
                        style = ToolBoxThemeTokens.textStyles.metadata.copy(
                            color = ToolBoxThemeTokens.colors.textSecondary,
                        ),
                    )
                }
            }
            actions()
        }
        return
    }
    TopAppBar(
        title = title,
        largeTitle = title,
        subtitle = subtitle,
        modifier = barModifier,
        color = if (isGlass) Color.Transparent else ToolBoxThemeTokens.colors.background,
        titleColor = ToolBoxThemeTokens.colors.textPrimary,
        largeTitleColor = ToolBoxThemeTokens.colors.textPrimary,
        subtitleColor = ToolBoxThemeTokens.colors.textSecondary,
        actions = actions,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
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
    if (isGlass) {
        LiquidGlassNavigationBar(
            items = items,
            selectedId = selectedId,
            onItemSelected = onItemSelected,
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
                    onClick = { onItemSelected(item) },
                    icon = item.icon.asImageVector(),
                    label = item.label,
                    modifier = item.testTag?.let(Modifier::testTag) ?: Modifier,
                )
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
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
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
        modifier = modifier.toolBoxOpenDesignSurface(RoundedCornerShape(ToolBoxThemeTokens.radii.card)).then(
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
        modifier = modifier.fillMaxWidth().toolBoxOpenDesignSurface(RoundedCornerShape(ToolBoxThemeTokens.radii.denseSurface)),
        cornerRadius = ToolBoxThemeTokens.radii.denseSurface,
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = endPadding)
            .height(ToolBoxThemeTokens.sizes.divider)
            .background(ToolBoxThemeTokens.colors.divider),
    )
}

@Composable
fun ToolBoxPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = ToolBoxThemeTokens.colors
    val containerColor = if (destructive) colors.danger else colors.primary
    val contentColor = if (destructive) colors.onDanger else colors.onPrimary
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
        enabled = enabled,
        minHeight = ToolBoxThemeTokens.sizes.touchTarget,
        colors = ButtonDefaults.buttonColorsPrimary(
            color = containerColor,
            contentColor = contentColor,
        ),
    ) {
        ToolBoxText(
            text = label,
            style = ToolBoxThemeTokens.textStyles.body.copy(
                color = contentColor.copy(alpha = if (enabled) 1f else 0.46f),
            ),
        )
    }
}

/** Neutral filled action for modal surfaces; stays readable over blurred content. */
@Composable
fun ToolBoxSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ToolBoxThemeTokens.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
        enabled = enabled,
        minHeight = ToolBoxThemeTokens.sizes.touchTarget,
        colors = ButtonDefaults.buttonColorsPrimary(
            color = colors.surfaceMuted,
            contentColor = colors.textPrimary,
        ),
    ) {
        ToolBoxText(label, style = ToolBoxThemeTokens.textStyles.body.copy(
            color = colors.textPrimary.copy(alpha = if (enabled) 1f else 0.46f),
        ))
    }
}

@Composable
fun ToolBoxDestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ToolBoxThemeTokens.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
        enabled = enabled,
        minHeight = ToolBoxThemeTokens.sizes.touchTarget,
        colors = ButtonDefaults.buttonColorsPrimary(
            color = colors.softDanger,
            contentColor = colors.onSoftDanger,
        ),
    ) {
        ToolBoxText(
            text = label,
            style = ToolBoxThemeTokens.textStyles.body.copy(
                color = colors.onSoftDanger.copy(alpha = if (enabled) 1f else 0.46f),
            ),
        )
    }
}

/** Standalone actions have an outline; actions embedded in a row can opt out. */
@Composable
fun ToolBoxTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = ToolBoxThemeTokens.colors.primary,
    outlined: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val outlineModifier = if (outlined) {
        Modifier.squircleBorder(
            width = 1.dp,
            color = toolBoxTextButtonOutlineColor(ToolBoxThemeTokens.colors.textSecondary, enabled, pressed),
            cornerRadius = ButtonDefaults.CornerRadius,
        )
    } else Modifier
    TextButton(
        text = label,
        onClick = onClick,
        modifier = modifier.heightIn(min = ToolBoxThemeTokens.sizes.touchTarget).then(outlineModifier),
        enabled = enabled,
        minHeight = ToolBoxThemeTokens.sizes.touchTarget,
        colors = ButtonDefaults.textButtonColors(
            color = Color.Transparent,
            disabledColor = Color.Transparent,
            textColor = contentColor,
            disabledTextColor = contentColor.copy(alpha = 0.46f),
        ),
        textStyle = ToolBoxThemeTokens.textStyles.body.copy(
            color = contentColor.copy(alpha = if (enabled) 1f else 0.46f),
        ),
        interactionSource = interactionSource,
    )
}

internal fun toolBoxTextButtonOutlineColor(foreground: Color, enabled: Boolean, pressed: Boolean): Color =
    foreground.copy(alpha = when {
        !enabled -> 0.36f
        pressed -> 1f
        else -> 0.78f
    })

@Composable
fun ToolBoxRunningStatusButton(
    stopping: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ToolBoxThemeTokens.colors
    Button(
        onClick = onClick,
        modifier = modifier.sizeIn(
            minWidth = ToolBoxThemeTokens.sizes.touchTarget,
            minHeight = ToolBoxThemeTokens.sizes.touchTarget,
        ),
        enabled = enabled,
        minHeight = ToolBoxThemeTokens.sizes.touchTarget,
        colors = ButtonDefaults.buttonColorsPrimary(
            color = colors.softDanger,
            contentColor = colors.onSoftDanger,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolBoxText(
                text = if (stopping) "停止中" else "停止",
                style = ToolBoxThemeTokens.textStyles.metadata.copy(color = colors.onSoftDanger),
            )
        }
    }
}
