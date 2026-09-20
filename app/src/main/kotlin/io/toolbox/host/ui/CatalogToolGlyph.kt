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
import kotlinx.coroutines.flow.collectLatest

internal val LocalToolIconLoader = staticCompositionLocalOf<ToolIconLoader?> { null }

@Composable
internal fun CatalogToolGlyph(
    toolId: String,
    versionCode: Int?,
    visual: ToolVisual,
    size: Dp = ToolBoxThemeTokens.sizes.toolGlyph,
) {
    val bitmap = rememberCatalogToolBitmap(toolId, versionCode)
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    ToolGlyph(
        icon = visual.icon,
        accent = visual.accent,
        size = size,
        imageResource = visual.imageResource,
        imageBitmap = image,
    )
}

@Composable
internal fun rememberCatalogToolBitmap(toolId: String, versionCode: Int?): Bitmap? {
    val loader = LocalToolIconLoader.current
    return key(toolId, versionCode, loader) {
        val bitmap by produceState(loader?.cached(toolId, versionCode)) {
            loader?.invalidations(toolId)?.collectLatest {
                value = loader.cached(toolId, versionCode)
                value = loader.load(toolId, versionCode)
            }
        }
        bitmap
    }
}
