package io.toolbox.core.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Paths copied from the supplied OpenDesign HTML; not SF Symbols or remote assets.
internal object OpenDesignIcons {
    val Tools: ImageVector by lazy {
        ImageVector.Builder("OpenDesignTools", 24.dp, 24.dp, 24f, 24f, autoMirror = false).apply {
            addPath(pathData = PathParser().parsePathString("M7.0 5.0h10.0a3.0 3.0 0 0 1 3.0 3.0v9.0a3.0 3.0 0 0 1 -3.0 3.0h-10.0a3.0 3.0 0 0 1 -3.0 -3.0v-9.0a3.0 3.0 0 0 1 3.0 -3.0Z").toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M8 9h8M8 13h5").toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val Settings: ImageVector by lazy {
        ImageVector.Builder("OpenDesignSettings", 24.dp, 24.dp, 24f, 24f, autoMirror = false).apply {
            addPath(pathData = PathParser().parsePathString("M9.0 12.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0").toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.34 2.34-.06-.06a1.7 1.7 0 0 0-1.88-.34 1.7 1.7 0 0 0-1.02 1.55v.1h-3.3v-.1A1.7 1.7 0 0 0 10.18 18.9a1.7 1.7 0 0 0-1.88.34l-.06.06-2.34-2.34.06-.06A1.7 1.7 0 0 0 6.3 15a1.7 1.7 0 0 0-1.55-1.02h-.1v-3.3h.1A1.7 1.7 0 0 0 6.3 9.66a1.7 1.7 0 0 0-.34-1.88L5.9 7.72 8.24 5.4l.06.06a1.7 1.7 0 0 0 1.88.34 1.7 1.7 0 0 0 1.02-1.55v-.1h3.3v.1a1.7 1.7 0 0 0 1.02 1.55 1.7 1.7 0 0 0 1.88-.34l.06-.06 2.34 2.34-.06.06a1.7 1.7 0 0 0-.34 1.88 1.7 1.7 0 0 0 1.55 1.02h.1v3.3h-.1A1.7 1.7 0 0 0 19.4 15Z").toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val Back: ImageVector by lazy {
        ImageVector.Builder("OpenDesignBack", 24.dp, 24.dp, 24f, 24f, autoMirror = true).apply {
            addPath(pathData = PathParser().parsePathString("m14.5 5-7 7 7 7").toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
}
