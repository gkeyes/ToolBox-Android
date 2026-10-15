package io.toolbox.core.ui.component

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Rounded rectangle with continuous-curvature ("squircle-like") corners.
 *
 * A circular corner jumps from zero to full curvature where the arc meets the straight
 * edge, which reads as a visible kink on large radii. Here each corner is a single cubic
 * that starts [Extension]× the radius away from the corner, so curvature ramps in from the
 * edge instead. Outlines are generic paths; clipping, borders and shadows all follow them.
 */
@Immutable
class ToolBoxContinuousShape(
    private val topStart: Dp,
    private val topEnd: Dp,
    private val bottomEnd: Dp,
    private val bottomStart: Dp,
) : Shape {
    constructor(radius: Dp) : this(radius, radius, radius, radius)

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val tl = with(density) { (if (ltr) topStart else topEnd).toPx() }
        val tr = with(density) { (if (ltr) topEnd else topStart).toPx() }
        val br = with(density) { (if (ltr) bottomEnd else bottomStart).toPx() }
        val bl = with(density) { (if (ltr) bottomStart else bottomEnd).toPx() }
        if (tl + tr + br + bl <= 0f) {
            return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        }
        val limit = minOf(size.width, size.height) / 2f
        // A capsule radius (>= half the short side) gets a true stadium: the continuous curve at
        // that radius reads as a rounded rectangle, not the flat pill the design calls for.
        if (minOf(tl, tr, br, bl) >= limit) {
            return Outline.Rounded(
                androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(limit)),
            )
        }
        fun reach(r: Float) = minOf(r * Extension, limit)
        fun handle(r: Float) = reach(r) * (Control / Extension)
        val w = size.width
        val h = size.height
        val path = Path().apply {
            val dTl = reach(tl); val cTl = handle(tl)
            val dTr = reach(tr); val cTr = handle(tr)
            val dBr = reach(br); val cBr = handle(br)
            val dBl = reach(bl); val cBl = handle(bl)
            moveTo(dTl, 0f)
            lineTo(w - dTr, 0f)
            if (dTr > 0f) cubicTo(w - cTr, 0f, w, cTr, w, dTr)
            lineTo(w, h - dBr)
            if (dBr > 0f) cubicTo(w, h - cBr, w - cBr, h, w - dBr, h)
            lineTo(dBl, h)
            if (dBl > 0f) cubicTo(cBl, h, 0f, h - cBl, 0f, h - dBl)
            lineTo(0f, dTl)
            if (dTl > 0f) cubicTo(0f, cTl, cTl, 0f, dTl, 0f)
            close()
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean =
        other is ToolBoxContinuousShape && other.topStart == topStart && other.topEnd == topEnd &&
            other.bottomEnd == bottomEnd && other.bottomStart == bottomStart

    override fun hashCode(): Int =
        ((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()

    private companion object {
        /** Corner curve begins this many radii from the corner along each edge. */
        const val Extension = 1.28f

        /** Control points sit this many radii from the corner (on the edges). */
        const val Control = 0.2f
    }
}

fun toolBoxShape(radius: Dp): Shape = if (radius <= 0.dp) androidx.compose.ui.graphics.RectangleShape else ToolBoxContinuousShape(radius)

fun toolBoxTopShape(radius: Dp): Shape = ToolBoxContinuousShape(radius, radius, 0.dp, 0.dp)
