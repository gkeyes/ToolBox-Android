package io.toolbox.host.browser

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The close button floats over the page, so without live glass its hairline is its edge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "xxhdpi")
class BrowserCloseButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // Pages the colour of the button's solid fill are the hard case; a 1dp control edge needs 3:1.
    @Test fun lightThemeOnAWhitePage() = assertEdgeContrast(dark = false, page = Color.White)

    @Test fun lightThemeOnAGroupedPage() = assertEdgeContrast(dark = false, page = Color(0xFFF2F2F7))

    @Test fun darkThemeOnAPageTheColourOfTheFill() = assertEdgeContrast(dark = true, page = Color(0xFF232327))

    private fun assertEdgeContrast(dark: Boolean, page: Color) {
        compose.setContent {
            ToolBoxTheme(mode = if (dark) ToolBoxThemeMode.Dark else ToolBoxThemeMode.Light, reduceTransparency = true) {
                Box(Modifier.background(page).padding(16.dp)) {
                    BrowserCloseButton(NoActions)
                }
            }
        }
        val pixels = compose.onNodeWithTag("browser_close").captureToImage().toPixelMap()
        val middle = pixels.height / 2
        // The outline is 1dp = 3px here, drawn inside the circle's left edge.
        val edge = (0..3).maxOf { contrast(pixels[it, middle], page) }
        assertTrue("edge contrast $edge against the page", edge >= 3f)
    }

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private object NoActions : BrowserScreenActions {
        override fun close() = Unit
        override fun reload() = Unit
        override fun performLoadAction() = Unit
        override fun traverseHistory(forward: Boolean) = Unit
        override fun share() = Unit
        override fun openSystemBrowser() = Unit
        override fun copyAddress() = Unit
        override fun startMediaDiagnostics() = Unit
        override fun copyMediaDiagnostics() = Unit
        override fun changeUserAgentMode(mode: BrowserUserAgentMode) = Unit
        override fun clearWebsiteData() = Unit
        override fun restartUnresponsivePage() = Unit
        override fun applySystemBarAppearance(light: Boolean) = Unit
    }
}
