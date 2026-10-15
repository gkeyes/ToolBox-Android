package io.toolbox.core.ui.component

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/**
 * iOS 26 search-field glass: white .72 under the neutral wash at .12 (light), #2C2C2E .85 under it
 * at .24 (dark). Thick enough that the field reads at rest and its placeholder stays legible over
 * saturated tiles scrolling beneath it.
 */
@Composable
fun toolBoxSearchGlassTint(): Color {
    val dark = ToolBoxThemeTokens.colors.surface.luminance() < 0.5f
    return if (dark) {
        ToolBoxNeutralFill.copy(alpha = 0.24f).compositeOver(Color(0xFF2C2C2E).copy(alpha = 0.85f))
    } else {
        ToolBoxNeutralFill.copy(alpha = 0.12f).compositeOver(Color.White.copy(alpha = 0.72f))
    }
}

/** A list's backdrop for a field floating over it, recorded over the page canvas, never over nothing. */
@Composable
fun rememberToolBoxListBackdrop(): LayerBackdrop {
    val background = ToolBoxThemeTokens.colors.background
    return rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
}

/** The colour fade the collapsed top bar draws below itself (ToolBoxCollapsingTitle). */
private val TopBarFadeBand = 24.dp

/**
 * A list page's search field as chrome: it scrolls with the list until it reaches the collapsed top
 * bar, then stays pinned just below the bar's fade band, as glass over [listBackdrop] (record the
 * list into it with `layerBackdrop` while [glassRendersLive]). The list holds its place with an
 * item keyed [itemKey] as tall as [onHeightChanged] reports; [contentTop] is the list's top padding.
 */
@Composable
fun ToolBoxPinnedSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    listState: LazyListState,
    listBackdrop: LayerBackdrop,
    contentTop: Dp,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemKey: Any = "search",
) {
    val density = LocalDensity.current
    val live = glassRendersLive()
    val contentTopPx = with(density) { contentTop.roundToPx() }
    val pinnedTopPx = contentTopPx - with(density) { (toolBoxLargeTitleHeight() - TopBarFadeBand).roundToPx() }
    val tint = toolBoxSearchGlassTint()
    ToolBoxSearchField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        containerColor = if (live) Color.Transparent else Color.Unspecified,
        modifier = Modifier
            .zIndex(1f)
            .then(modifier)
            .offset {
                val info = listState.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.key == itemKey }
                // viewportStartOffset is -beforeContentPadding, so add it back for the item's y.
                val natural = when {
                    item != null -> item.offset + info.beforeContentPadding
                    info.visibleItemsInfo.isEmpty() -> contentTopPx
                    else -> pinnedTopPx
                }
                IntOffset(0, maxOf(natural, pinnedTopPx))
            }
            .onSizeChanged { onHeightChanged(it.height) }
            .then(
                if (live) {
                    Modifier.drawBackdrop(
                        backdrop = listBackdrop,
                        shape = { CircleShape },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(12.dp.toPx(), 24.dp.toPx())
                        },
                        onDrawSurface = { drawRect(tint) },
                    )
                } else {
                    Modifier
                },
            ),
    )
}
