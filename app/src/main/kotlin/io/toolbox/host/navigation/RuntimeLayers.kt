package io.toolbox.host.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import io.toolbox.core.ui.component.ToolBoxMotion

/*
 * The layers ToolBoxNavigation stacks while a tool opens, shows and returns, back to front:
 * a cover standing in beneath the source (-1), the source when the tool is in front (0), the
 * tool (1), the source when it shows over the tool (2), the entry cover (3), the input blocker (4).
 */

/** Shared-axis travel, read inside graphics layers (Density receiver). */
private val GraphicsLayerScope.PageShiftPx: Float
    get() = ToolBoxMotion.PageShift.toPx()

/**
 * The source layer (primary screen and retained pages): in front of the tool while no tool is
 * open or while it shows over the tool, faded and shifted by the transition, and out of the
 * semantics tree while the tool is in front of it.
 */
internal fun Modifier.runtimeSourceLayer(transition: RuntimeTransition, trailingDirection: Float): Modifier =
    zIndex(if (transition.route == null || transition.sourceAboveRuntime) 2f else 0f)
        .graphicsLayer {
            val runtimeDepth = transition.sourceDepth()
            translationX = -trailingDirection * runtimeDepth * PageShiftPx
            alpha = 1f - runtimeDepth
        }
        .then(if (transition.sourceSemanticsHidden) Modifier.clearAndSetSemantics { } else Modifier)

/** The tool's layer: out of the semantics tree while its cover or the source is in front of it. */
internal fun Modifier.runtimeToolLayer(transition: RuntimeTransition): Modifier =
    zIndex(1f).then(
        if (transition.entryCoverVisible || transition.sourceAboveRuntime) Modifier.clearAndSetSemantics { } else Modifier,
    )

/**
 * The cover shown over the tool until it presents. Its first placement reports
 * [RuntimeTransition.entryCoverMeasured], which starts the entry.
 */
@Composable
internal fun RuntimeEntryCover(
    transition: RuntimeTransition,
    sourceHidden: Boolean,
    trailingDirection: Float,
    content: @Composable () -> Unit,
) {
    if (transition.route == null || !transition.entryCoverVisible) return
    // Keyed per transition: a tool switch (or the same tool pushed again) gives the new
    // cover a fresh node, so its first placement reports entryCoverMeasured.
    key(transition) {
        // After a switch during a return the source is still half-transparent; the new
        // cover fills in opaque beneath it (where the replaced tool was) until the
        // source has faded out, instead of leaving the bare background showing through.
        val coverBeneath = transition.coverBeneathSource && !sourceHidden
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (coverBeneath) -1f else 3f)
                .onGloballyPositioned { coordinates ->
                    if (coordinates.size.width > 0 && coordinates.size.height > 0) {
                        transition.entryCoverMeasured = true
                    }
                }
                .graphicsLayer {
                    val motion = transition.entryProgress.value.coerceIn(0f, 1f)
                    if (transition.coverBeneathSource) {
                        // Stands in for the replaced tool, which sat settled: no slide.
                        translationX = 0f
                        alpha = 1f
                    } else {
                        translationX = trailingDirection * motion * PageShiftPx
                        alpha = ToolBoxMotion.fadeThroughIncomingAlpha(1f - motion)
                    }
                },
        ) {
            content()
        }
    }
}

/** Swallows touches while the cover or the source is in front of the tool. */
@Composable
internal fun RuntimeInputBlocker(transition: RuntimeTransition) {
    if (transition.route != null && (transition.entryCoverVisible || transition.sourceAboveRuntime)) {
        NavigationInputBlocker(Modifier.zIndex(4f))
    }
}
