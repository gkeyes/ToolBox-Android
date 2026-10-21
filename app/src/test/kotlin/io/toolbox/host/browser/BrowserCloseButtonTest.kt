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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The close button floats over the page, so without live glass it still needs an edge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "xxhdpi")
class BrowserCloseButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withReducedTransparencyTheCloseButtonIsOutlinedOnAWhitePage() {
        compose.setContent {
            ToolBoxTheme(reduceTransparency = true) {
                Box(Modifier.background(Color.White).padding(16.dp)) {
                    BrowserCloseButton(NoActions)
                }
            }
        }
        val pixels = compose.onNodeWithTag("browser_close").captureToImage().toPixelMap()
        val middle = pixels.height / 2
        // The outline (1dp = 3px here) against the fill a quarter of the way in, clear of the icon.
        val edge = (0..3).minOf { pixels[it, middle].luminance() }
        val fill = pixels[pixels.width / 4, middle].luminance()
        assertTrue("edge $edge vs fill $fill", fill - edge > 0.04f)
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
