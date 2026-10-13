package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/** iOS grouped canvas: a flat systemGroupedBackground. Never captures a WebView. */
@Composable
fun Modifier.toolBoxOpenDesignCanvas(): Modifier = background(ToolBoxThemeTokens.colors.background)

/** iOS inset-grouped card: an opaque secondarySystemGroupedBackground fill, no border or shadow. */
@Composable
fun Modifier.toolBoxOpenDesignSurface(shape: Shape): Modifier =
    clip(shape).background(ToolBoxThemeTokens.colors.surface)
