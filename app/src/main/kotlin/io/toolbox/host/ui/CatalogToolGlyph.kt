package io.toolbox.host.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.icons.ToolIconLoader

internal val LocalToolIconLoader = staticCompositionLocalOf<ToolIconLoader?> { null }

@Composable
internal fun CatalogToolGlyph(
    toolId: String,
    versionCode: Int?,
    visual: ToolVisual,
    size: Dp = ToolBoxThemeTokens.sizes.toolGlyph,
) {
    val loader = LocalToolIconLoader.current
    key(toolId, versionCode, loader) {
        val bitmap by produceState<Bitmap?>(null) { value = loader?.load(toolId, versionCode) }
        val image = remember(bitmap) { bitmap?.asImageBitmap() }
        ToolGlyph(
            icon = visual.icon,
            accent = visual.accent,
            size = size,
            imageResource = visual.imageResource,
            imageBitmap = image,
        )
    }
}
