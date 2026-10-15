package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Scaffold
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
    // The tool draws under the status bar itself; side cutouts (landscape) still pad.
    immersiveStatusBar: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    var insets = if (immersiveStatusBar) {
        WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
    } else {
        WindowInsets.statusBars.union(WindowInsets.displayCutout)
    }
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
            // 14dp plus the buttons' own 2dp ring puts the round buttons 16dp from the edge, in line
            // with the page title and cards.
            .padding(horizontal = ToolBoxThemeTokens.spacing.md - 2.dp, vertical = ToolBoxThemeTokens.spacing.xxs),
        content = {
            Box {
                if (navigationIcon != null && onNavigationClick != null) {
                    // iOS 27: a round glass button carrying only the chevron.
                    if (glassState != null) {
                        ToolBoxGlassIconButton(
                            icon = navigationIcon,
                            contentDescription = navigationContentDescription,
                            onClick = onNavigationClick,
                            glassState = glassState,
                            modifier = Modifier.padding(2.dp),
                        )
                    } else {
                        ToolBoxIconButton(
                            navigationIcon, navigationContentDescription, onNavigationClick,
                            modifier = Modifier.padding(2.dp).size(44.dp).toolBoxSolidGlass(CircleShape),
                            tint = colors.textPrimary,
                        )
                    }
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
    startPadding: Dp = ToolBoxRowStartPadding + ToolBoxRowIconSize + ToolBoxRowIconGap,
    endPadding: Dp = 0.dp,
) {
    // iOS inset-grouped hairline: inset after the row icon, running to the card's trailing edge.
    val color = ToolBoxThemeTokens.colors.divider
    Spacer(
        modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .padding(start = startPadding, end = endPadding)
            .drawBehind { drawRect(color) },
    )
}
