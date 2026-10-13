package io.toolbox.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import kotlin.math.cos
import kotlin.math.sin

private const val BusySpokes = 8
private const val BusyCycleMillis = 800

/**
 * iOS activity indicator: eight rounded spokes whose opacity steps around the circle.
 * The owning control announces its state; the visual spinner is not another focus target.
 */
@Composable
fun ToolBoxBusyIndicator(modifier: Modifier = Modifier) {
    val color = ToolBoxThemeTokens.colors.textSecondary
    val transition = rememberInfiniteTransition(label = "busy indicator")
    val step = transition.animateFloat(
        initialValue = 0f,
        targetValue = BusySpokes.toFloat(),
        animationSpec = infiniteRepeatable(tween(BusyCycleMillis, easing = LinearEasing)),
        label = "busy spoke",
    )
    Canvas(modifier.clearAndSetSemantics { }.size(24.dp)) {
        val head = step.value.toInt() % BusySpokes
        val radius = size.minDimension / 2f
        val inner = radius * 0.48f
        val stroke = size.minDimension * 0.095f
        val outer = radius - stroke / 2f
        for (index in 0 until BusySpokes) {
            // The newest spoke is fully opaque; older ones fade towards the tail.
            val age = (head - index + BusySpokes) % BusySpokes
            val alpha = 1f - age * (0.78f / (BusySpokes - 1))
            val angle = Math.toRadians(index * 360.0 / BusySpokes - 90.0)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            drawLine(
                color = color.copy(alpha = color.alpha * alpha),
                start = Offset(center.x + dx * inner, center.y + dy * inner),
                end = Offset(center.x + dx * outer, center.y + dy * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
