package io.toolbox.host.browser

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The browser controls float over the page, so without live glass their hairline is their edge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "xxhdpi")
class BrowserControlEdgeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // As BrowserActivity does, so the address capsule can shorten the host.
    @Before fun loadPublicSuffixes() = BrowserPublicSuffix.load(RuntimeEnvironment.getApplication())

    // Pages the colour of the controls' solid fill are the hard case; a 1dp control edge needs 3:1.
    @Test fun lightThemeOnAWhitePage() = assertEdgesContrast(dark = false, page = Color.White)

    @Test fun lightThemeOnAGroupedPage() = assertEdgesContrast(dark = false, page = Color(0xFFF2F2F7))

    @Test fun darkThemeOnAPageTheColourOfTheFill() = assertEdgesContrast(dark = true, page = Color(0xFF232327))

    private fun assertEdgesContrast(dark: Boolean, page: Color) {
        compose.setContent {
            ToolBoxTheme(mode = if (dark) ToolBoxThemeMode.Dark else ToolBoxThemeMode.Light, reduceTransparency = true) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val state = remember { BrowserUiState().apply { address = "https://example.com/"; canBack = true } }
                val filters = remember { BrowserFilterController(context, scope) }
                Column(Modifier.background(page).padding(16.dp)) {
                    BrowserCloseButton(NoActions)
                    BrowserBottomBar(state, filters, NoActions)
                }
            }
        }
        // The close, back and more circles and the address capsule all float over the page.
        listOf(
            "close" to compose.onNodeWithTag("browser_close"),
            "back" to compose.onNodeWithContentDescription("后退"),
            "more" to compose.onNodeWithContentDescription("更多"),
            "address" to compose.onNodeWithTag("browser_address"),
        ).forEach { (name, node) ->
            val pixels = node.captureToImage().toPixelMap()
            val middle = pixels.height / 2
            // The outline is 1dp = 3px here, drawn inside the control's left edge.
            val edge = (0..3).maxOf { contrast(pixels[it, middle], page) }
            assertTrue("$name edge contrast $edge against the page", edge >= 3f)
        }
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
