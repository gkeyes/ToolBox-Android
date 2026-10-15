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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

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
    val source = rememberToolBoxListScrolledPx(state)
    DisposableEffect(collapse, source) {
        collapse.source = source
        onDispose { if (collapse.source === source) collapse.source = null }
    }
}

/**
 * Exact distance a lazy list has scrolled from its top, readable synchronously in draw/layer
 * phases. Item sizes before the first visible item are remembered as they scroll past, so the
 * value stays exact when the first item is shorter than the title.
 */
@Composable
fun rememberToolBoxListScrolledPx(state: LazyListState): () -> Float = remember(state) {
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
    source
}

/** Collapse fraction (0 = expanded, 1 = collapsed) for [scrolledPx] against the title height. */
fun toolBoxCollapseFraction(scrolledPx: Float, largeTitlePx: Float): Float =
    if (largeTitlePx <= 0f) 0f else (scrolledPx / largeTitlePx).coerceIn(0f, 1f)

/** Bar title / hairline alpha: appears only once the large title is mostly gone. */
fun toolBoxBarTitleAlpha(fraction: Float): Float = ((fraction - 0.55f) / 0.45f).coerceIn(0f, 1f)

/** Large title alpha: fades out slightly ahead of being covered by the bar. */
fun toolBoxLargeTitleAlpha(fraction: Float): Float = 1f - (fraction * 1.4f).coerceAtMost(1f)

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

/**
 * Height of the expanded title block below the 56dp bar. It is measured from the real title and
 * subtitle line heights (including CJK fallback font metrics), so it grows with the system font
 * scale and never clips the subtitle.
 */
@Composable
fun toolBoxLargeTitleHeight(subtitle: String = ""): Dp {
    val density = LocalDensity.current
    val textScale = density.fontScale.coerceAtLeast(1f)
    val styles = ToolBoxThemeTokens.textStyles
    val spacing = ToolBoxThemeTokens.spacing
    val measurer = rememberTextMeasurer()
    val hasSubtitle = subtitle.isNotBlank()
    val measured = remember(measurer, density, styles, hasSubtitle) {
        with(density) {
            val title = measurer.measure(LINE_SAMPLE, styles.screenTitle, maxLines = 1).size.height.toDp()
            val sub = if (hasSubtitle) measurer.measure(LINE_SAMPLE, styles.metadata, maxLines = 1).size.height.toDp() else 0.dp
            spacing.xs + title + sub + if (hasSubtitle) spacing.xxs else 0.dp
        }
    }
    return maxOf(ToolBoxThemeTokens.sizes.largeTitle * textScale, measured)
}

/** Latin ascender/descender plus a CJK glyph, which uses the taller fallback font metrics. */
private const val LINE_SAMPLE = "国Ag"

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
    /** Synchronous scroll source that overrides [collapse] (for example the selected tab's list). */
    scrolledPx: (() -> Float)? = null,
    navigationIcon: ToolBoxIconKey? = null,
    navigationContentDescription: String = "返回",
    onNavigationClick: (() -> Unit)? = null,
    glassState: ToolBoxGlassState? = null,
    statusBarPadding: Boolean = true,
    /** False for a page with only the 17sp bar title (no large title block below the bar). */
    largeTitle: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = ToolBoxThemeTokens.colors
    val largeHeight = toolBoxLargeTitleHeight(subtitle)
    val insets = if (statusBarPadding) {
        WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
    } else {
        WindowInsets(0, 0, 0, 0)
    }
    val largePx = with(LocalDensity.current) { largeHeight.toPx() }
    val readScrolled: () -> Float = scrolledPx ?: { collapse?.scrolledPx() ?: 0f }
    fun progress(scrolled: Float): Float = toolBoxCollapseFraction(scrolled, largePx)

    Box(modifier.fillMaxWidth()) {
        // Large title first so the bar draws over it as it slides up.
        if (largeTitle) Box(
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
                        val scrolled = readScrolled()
                        translationY = -scrolled.coerceAtMost(largePx)
                        alpha = toolBoxLargeTitleAlpha(progress(scrolled))
                    }
                    .padding(horizontal = ToolBoxThemeTokens.spacing.md)
                    .padding(top = ToolBoxThemeTokens.spacing.xs),
            ) {
                // Tab switches change the title in place: cross-fade it rather than swapping a frame.
                androidx.compose.animation.Crossfade(title, animationSpec = ToolBoxMotion.effects(), label = "large title") { text ->
                    ToolBoxText(
                        text,
                        style = ToolBoxThemeTokens.textStyles.screenTitle.copy(color = colors.textPrimary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
        // One deterministic material. A live blur here is switched off whenever its page stops
        // being the active glass page (every push, pop and runtime entry) and back on afterwards,
        // which flashed the bar between blurred glass and its solid fallback during transitions.
        // At the top of a page the bar is see-through onto the same page canvas, so a new page's
        // first frame needs no material at all; the fill and hairline arrive with the collapse.
        // iOS 27 scroll-edge effect: content dissolves into the canvas under the floating glass
        // buttons instead of meeting a solid bar and hairline.
        val edgeFade = 24.dp
        val barSurface = Modifier.drawBehind {
            val fraction = progress(readScrolled())
            if (fraction <= 0f) return@drawBehind
            val fadePx = edgeFade.toPx()
            val total = size.height + fadePx
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to colors.background.copy(alpha = 0.94f * fraction),
                    (size.height / total) * 0.7f to colors.background.copy(alpha = 0.82f * fraction),
                    1f to colors.background.copy(alpha = 0f),
                    endY = total,
                ),
                size = androidx.compose.ui.geometry.Size(size.width, total),
            )
        }
        // Behind the colour fade, the page under the bar and its fade band is blurred like the
        // boards' backdrop-filter. It only draws while the page is the live glass page; during a
        // transition the colour fade above carries the bar on its own.
        if (glassState != null && glassRendersLive()) {
            val barHeight = ToolBoxThemeTokens.sizes.topBar
            Column(
                Modifier
                    // Drawn below the bar but measured as zero height: the band must not make the
                    // top bar (and the page padding taken from it) taller.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        layout(placeable.width, 0) { placeable.place(0, 0) }
                    }
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = progress(readScrolled())
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        val fadeStart = (size.height - edgeFade.toPx()) / size.height
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to Color.Black,
                                fadeStart * 0.85f to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .drawBackdrop(
                        backdrop = glassState.backdrop,
                        shape = { RectangleShape },
                        effects = { blur(12.dp.toPx()) },
                        highlight = null,
                        shadow = null,
                    ),
            ) {
                Spacer(Modifier.windowInsetsTopHeight(insets))
                Spacer(Modifier.height(barHeight + edgeFade))
            }
        }
        ToolBoxTopBar(
            title = title,
            modifier = Modifier.then(barSurface),
            navigationIcon = navigationIcon,
            navigationContentDescription = navigationContentDescription,
            onNavigationClick = onNavigationClick,
            glassState = glassState,
            windowInsets = insets,
            titleAlpha = { if (largeTitle) toolBoxBarTitleAlpha(progress(readScrolled())) else 1f },
            actions = actions,
        )
    }
}
