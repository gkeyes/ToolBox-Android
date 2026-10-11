package io.toolbox.core.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/**
 * Scroll position that drives the large-title collapse.
 *
 * The bar never changes height: the large title is translated and faded in its layer while
 * the 17sp bar title and hairline fade in, so collapsing never re-measures the page. A screen
 * binds its list with [ToolBoxTitleCollapseSource]; otherwise the scaffold's nested-scroll
 * total is used as a fallback.
 */
@Stable
class ToolBoxTitleCollapse internal constructor() {
    internal var source by mutableStateOf<(() -> Float)?>(null)
    private var accumulated by mutableFloatStateOf(0f)

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            accumulated = (accumulated - consumed.y).coerceAtLeast(0f)
            return Offset.Zero
        }
    }

    /** Pixels the page content has scrolled away from its top. Read in draw / layer phases. */
    fun scrolledPx(): Float = source?.invoke() ?: accumulated

    fun reset() {
        accumulated = 0f
    }
}

@Composable
fun rememberToolBoxTitleCollapse(): ToolBoxTitleCollapse = remember { ToolBoxTitleCollapse() }

/** Provided by screen scaffolds; null where no collapsing title exists (or for off-screen pages). */
val LocalToolBoxTitleCollapse = compositionLocalOf<ToolBoxTitleCollapse?> { null }

/** Binds a lazy list as the exact scroll source for the surrounding collapsing title. */
@Composable
fun ToolBoxTitleCollapseSource(state: LazyListState) {
    val collapse = LocalToolBoxTitleCollapse.current ?: return
    DisposableEffect(collapse, state) {
        // Item sizes before the first visible item are remembered as they scroll past, so the
        // distance stays exact even when the first item is shorter than the title.
        val sizes = HashMap<Int, Int>()
        var gap = 0
        val source: () -> Float = {
            val info = state.layoutInfo.visibleItemsInfo
            info.forEach { if (it.index < 32) sizes[it.index] = it.size }
            if (info.size >= 2) gap = (info[1].offset - info[0].offset - info[0].size).coerceAtLeast(0)
            val first = state.firstVisibleItemIndex
            var total = state.firstVisibleItemScrollOffset.toFloat()
            var known = true
            for (i in 0 until first) {
                val size = sizes[i]
                if (size == null) { known = false; break }
                total += size + gap
            }
            if (known) total else Float.MAX_VALUE / 4f
        }
        collapse.source = source
        onDispose { if (collapse.source === source) collapse.source = null }
    }
}

/** Binds a plain vertical scroll container as the scroll source. */
@Composable
fun ToolBoxTitleCollapseSource(state: ScrollState) {
    val collapse = LocalToolBoxTitleCollapse.current ?: return
    DisposableEffect(collapse, state) {
        val source: () -> Float = { state.value.toFloat() }
        collapse.source = source
        onDispose { if (collapse.source === source) collapse.source = null }
    }
}

/** Height of the expanded title block below the 56dp bar. */
@Composable
fun toolBoxLargeTitleHeight(subtitle: String = ""): Dp =
    ToolBoxThemeTokens.sizes.largeTitle + if (subtitle.isNotBlank()) 20.dp else 0.dp

/**
 * Screen top bar (design system v2 scaffold): a fixed 56dp bar holding navigation, actions and
 * the 17sp title, with the 30sp large title below it. Scrolling slides the large title up under
 * the bar while the bar title and its hairline fade in. Total height is constant; pages pass
 * the scaffold padding as list content padding so content scrolls under the bar.
 */
@Composable
fun ToolBoxCollapsingTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    collapse: ToolBoxTitleCollapse? = LocalToolBoxTitleCollapse.current,
    navigationIcon: ToolBoxIconKey? = null,
    navigationContentDescription: String = "返回",
    onNavigationClick: (() -> Unit)? = null,
    glassState: ToolBoxGlassState? = null,
    statusBarPadding: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = ToolBoxThemeTokens.colors
    val isGlass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass
    val largeHeight = toolBoxLargeTitleHeight(subtitle)
    val insets = if (statusBarPadding) {
        WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
    } else {
        WindowInsets(0, 0, 0, 0)
    }
    val largePx = with(LocalDensity.current) { largeHeight.toPx() }
    fun progress(scrolled: Float): Float = (scrolled / largePx).coerceIn(0f, 1f)

    Box(modifier.fillMaxWidth()) {
        // Large title first so the bar draws over it as it slides up.
        Box(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(insets)
                .padding(top = ToolBoxThemeTokens.sizes.topBar)
                .height(largeHeight)
                .clipToBounds()
                .clearAndSetSemantics { },
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .graphicsLayer {
                        val scrolled = collapse?.scrolledPx() ?: 0f
                        translationY = -scrolled.coerceAtMost(largePx)
                        alpha = 1f - (progress(scrolled) * 1.4f).coerceAtMost(1f)
                    }
                    .padding(horizontal = ToolBoxThemeTokens.spacing.md)
                    .padding(top = ToolBoxThemeTokens.spacing.xs),
            ) {
                ToolBoxText(
                    title,
                    style = ToolBoxThemeTokens.textStyles.screenTitle.copy(color = colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    ToolBoxText(
                        subtitle,
                        style = ToolBoxThemeTokens.textStyles.metadata.copy(color = colors.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val barSurface = when {
            isGlass && glassState != null -> Modifier.toolBoxTopGlassEffect(glassState)
            isGlass -> Modifier.toolBoxSolidGlass(androidx.compose.ui.graphics.RectangleShape)
            else -> Modifier.background(colors.background)
        }
        ToolBoxTopBar(
            title = title,
            modifier = Modifier
                .then(barSurface)
                .drawWithContent {
                    drawContent()
                    val fraction = progress(collapse?.scrolledPx() ?: 0f)
                    if (fraction > 0.01f) {
                        drawLine(
                            colors.divider.copy(alpha = colors.divider.alpha * fraction),
                            Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f,
                        )
                    }
                },
            navigationIcon = navigationIcon,
            navigationContentDescription = navigationContentDescription,
            onNavigationClick = onNavigationClick,
            windowInsets = insets,
            titleAlpha = { ((progress(collapse?.scrolledPx() ?: 0f) - 0.55f) / 0.45f).coerceIn(0f, 1f) },
            actions = actions,
        )
    }
}
