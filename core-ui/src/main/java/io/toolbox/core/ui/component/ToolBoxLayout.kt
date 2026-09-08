package io.toolbox.core.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar

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
        modifier = modifier,
        topBar = {
            topBar?.invoke()
        },
        bottomBar = {
            bottomBar?.invoke()
        },
        floatingActionButton = {
            floatingActionButton?.invoke()
        },
        containerColor = ToolBoxThemeTokens.colors.background,
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
    val navigationSlot: @Composable () -> Unit = {
        if (navigationIcon != null && onNavigationClick != null) {
            ToolBoxIconButton(
                icon = navigationIcon,
                contentDescription = navigationContentDescription,
                onClick = onNavigationClick,
            )
        }
    }
    val isGlass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass
    val barModifier = when {
        isGlass && glassState != null -> modifier.toolBoxTopGlassEffect(glassState)
        isGlass -> modifier.toolBoxSolidGlass(RectangleShape)
        else -> modifier
    }
    if (isGlass) {
        Row(
            modifier = barModifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = ToolBoxThemeTokens.sizes.compactChrome)
                .padding(horizontal = ToolBoxThemeTokens.spacing.one),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationSlot()
            Column(Modifier.weight(1f)) {
                ToolBoxText(
                    text = title,
                    style = ToolBoxThemeTokens.textStyles.title.copy(color = ToolBoxThemeTokens.colors.textPrimary),
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
    SmallTopAppBar(
        title = title,
        modifier = barModifier.heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
        color = if (isGlass) Color.Transparent else ToolBoxThemeTokens.colors.background,
        titleColor = ToolBoxThemeTokens.colors.textPrimary,
        subtitle = subtitle,
        subtitleColor = ToolBoxThemeTokens.colors.textSecondary,
        navigationIcon = navigationSlot,
        actions = actions,
        defaultWindowInsetsPadding = true,
    )
}

@Composable
fun ToolBoxLargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    defaultWindowInsetsPadding: Boolean = true,
    glassState: ToolBoxGlassState? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val isGlass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass
    val barModifier = when {
        isGlass && glassState != null -> modifier.toolBoxTopGlassEffect(glassState)
        isGlass -> modifier.toolBoxSolidGlass(RectangleShape)
        else -> modifier
    }
    if (isGlass) {
        Row(
            modifier = barModifier
                .fillMaxWidth()
                .then(
                    if (defaultWindowInsetsPadding) Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    else Modifier,
                )
                .heightIn(min = 76.dp)
                .padding(
                    start = ToolBoxThemeTokens.spacing.two,
                    top = ToolBoxThemeTokens.spacing.one,
                    end = ToolBoxThemeTokens.spacing.one,
                    bottom = ToolBoxThemeTokens.spacing.one,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                ToolBoxText(
                    text = title,
                    style = ToolBoxThemeTokens.textStyles.screenTitle.copy(
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
    val navigationShape = RoundedCornerShape(ToolBoxThemeTokens.radii.full)
    if (isGlass) {
        LiquidGlassNavigationBar(
            items = items,
            selectedId = selectedId,
            onItemSelected = onItemSelected,
            modifier = modifier,
            glassState = glassState,
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
            val isSelected = item.id == selectedId
            NavigationBarItem(
                selected = isSelected,
                onClick = { onItemSelected(item) },
                icon = item.icon.asImageVector(),
                label = item.label,
                modifier = item.testTag?.let(Modifier::testTag) ?: Modifier,
            )
        }
    }
}

@Composable
private fun LiquidGlassNavigationBar(
    items: List<ToolBoxNavigationItem>,
    selectedId: String,
    onItemSelected: (ToolBoxNavigationItem) -> Unit,
    modifier: Modifier,
    glassState: ToolBoxGlassState?,
    navigationShape: RoundedCornerShape,
) {
    if (items.isEmpty()) return
    val surfaceModifier = modifier
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(
            horizontal = ToolBoxThemeTokens.spacing.oneHalf,
            vertical = ToolBoxThemeTokens.spacing.one,
        )
        .let { base ->
            if (glassState != null) {
                base.toolBoxGlassEffect(glassState, navigationShape)
            } else {
                base.toolBoxSolidGlass(navigationShape)
            }
        }

    BoxWithConstraints(
        modifier = surfaceModifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        val itemWidth = maxWidth / items.size
        val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        val selectionOffset by animateDpAsState(
            targetValue = itemWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "navigation selection lens",
        )
        val materials = ToolBoxThemeTokens.materials
        val selectionShape = RoundedCornerShape(ToolBoxThemeTokens.radii.full)
        if (materials.navigationLensEnabled) {
            Box(
                Modifier
                    .offset(x = selectionOffset)
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
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                LiquidGlassNavigationItem(
                    item = item,
                    selected = item.id == selectedId,
                    onClick = { onItemSelected(item) },
                )
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
    val contentScale by animateFloatAsState(
        targetValue = if (pressed && pressFeedbackEnabled) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "navigation item press",
    )
    val color = if (selected) ToolBoxThemeTokens.colors.textPrimary else ToolBoxThemeTokens.colors.textSecondary
    Column(
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
            .semantics { contentDescription = item.label }
            .graphicsLayer {
                scaleX = contentScale
                scaleY = contentScale
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ToolBoxIcon(
            icon = item.icon,
            contentDescription = null,
            tint = color,
        )
        Spacer(Modifier.height(2.dp))
        ToolBoxText(
            text = item.label,
            style = ToolBoxThemeTokens.textStyles.label.copy(
                color = color,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
fun ToolBoxFloatingActionButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ToolBoxIconKey = ToolBoxIconKey.Add,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Button
        },
        containerColor = ToolBoxThemeTokens.colors.primary,
    ) {
        ToolBoxIcon(
            icon = icon,
            contentDescription = null,
            tint = ToolBoxThemeTokens.colors.onPrimary,
        )
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
        modifier = modifier.then(
            if (onClick != null || onLongClick != null) {
                Modifier.sizeIn(minHeight = ToolBoxThemeTokens.sizes.touchTarget)
            } else {
                Modifier
            },
        ),
        cornerRadius = ToolBoxThemeTokens.radii.card,
        insideMargin = contentPadding,
        colors = CardColors(
            color = ToolBoxThemeTokens.colors.surface,
            contentColor = ToolBoxThemeTokens.colors.textPrimary,
        ),
        onClick = onClick,
        onLongPress = onLongClick,
        content = content,
    )
}

@Composable
fun ToolBoxGroupedSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = ToolBoxThemeTokens.radii.denseSurface,
        insideMargin = PaddingValues(0.dp),
        colors = CardColors(
            color = ToolBoxThemeTokens.colors.surface,
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

@Composable
fun ToolBoxTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = ToolBoxThemeTokens.colors.primary,
) {
    TextButton(
        text = label,
        onClick = onClick,
        modifier = modifier.heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
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
    )
}

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
            color = colors.softSuccess,
            contentColor = colors.onSoftSuccess,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!stopping) {
                Box(Modifier.size(ToolBoxThemeTokens.spacing.compact).background(colors.onSoftSuccess, CircleShape))
                Spacer(Modifier.width(ToolBoxThemeTokens.spacing.compact))
            }
            ToolBoxText(
                text = if (stopping) "停止中" else "运行中",
                style = ToolBoxThemeTokens.textStyles.metadata.copy(color = colors.onSoftSuccess),
            )
        }
    }
}
