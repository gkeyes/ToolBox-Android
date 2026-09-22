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

internal data class CatalogToolPresentation(val bitmap: Bitmap?, val description: String?)

@Composable
internal fun CatalogToolGlyph(
    toolId: String,
    versionCode: Int?,
    visual: ToolVisual,
    size: Dp = ToolBoxThemeTokens.sizes.toolGlyph,
) {
    CatalogToolArtwork(rememberCatalogToolBitmap(toolId, versionCode), visual, size)
}

@Composable
internal fun CatalogToolArtwork(
    bitmap: Bitmap?,
    visual: ToolVisual,
    size: Dp = ToolBoxThemeTokens.sizes.toolGlyph,
) {
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
internal fun rememberCatalogToolBitmap(toolId: String, versionCode: Int?): Bitmap? =
    rememberCatalogToolPresentation(toolId, versionCode).bitmap

@Composable
internal fun rememberCatalogToolPresentation(toolId: String, versionCode: Int?): CatalogToolPresentation {
    val loader = LocalToolIconLoader.current
    return key(toolId, versionCode, loader) {
        val presentation by produceState(
            CatalogToolPresentation(loader?.cached(toolId, versionCode), loader?.cachedDescription(toolId, versionCode)),
        ) {
            loader?.invalidations(toolId)?.collectLatest {
                value = CatalogToolPresentation(loader.cached(toolId, versionCode), loader.cachedDescription(toolId, versionCode))
                val bitmap = loader.load(toolId, versionCode)
                value = CatalogToolPresentation(bitmap, loader.cachedDescription(toolId, versionCode))
            }
        }
        presentation
    }
}
