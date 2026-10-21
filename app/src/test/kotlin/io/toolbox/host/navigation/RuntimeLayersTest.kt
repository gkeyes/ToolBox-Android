package io.toolbox.host.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The source, tool, cover and blocker layers ToolBoxNavigation stacks, composed with stand-in
 * pages: which one is drawn in front, which one is in the semantics tree, which one gets a tap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RuntimeLayersTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val taps = mutableListOf<String>()
    private val sourceHidden = mutableStateOf(false)

    private fun stack(transition: RuntimeTransition) {
        compose.setContent {
            Box(Modifier.size(100.dp)) {
                Box(Modifier.fillMaxSize().runtimeSourceLayer(transition, trailingDirection = 1f)) {
                    Page("source", Color.Red)
                }
                // The tool layer draws only once the entry enabled it, as NavigatorRuntimeLayer does.
                if (transition.runtimeLayerEnabled) {
                    Box(Modifier.fillMaxSize().runtimeToolLayer(transition)) { Page("tool", Color.Blue) }
                }
                RuntimeEntryCover(transition, sourceHidden = sourceHidden.value, trailingDirection = 1f) {
                    Page("cover", Color.Green)
                }
                RuntimeInputBlocker(transition)
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Page(tag: String, color: Color) {
        Box(Modifier.fillMaxSize().background(color).testTag(tag).clickable { taps += tag })
    }

    private fun front(): Color {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun tap() = compose.onRoot().performTouchInput { click(center) }

    private fun inTree(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun transition(
        route: RuntimeRoute? = A,
        initialEntry: Float = 0f,
        coverBeneathSource: Boolean = false,
    ) = RuntimeTransition(route = route, initialEntry = initialEntry, coverBeneathSource = coverBeneathSource)

    @Test
    fun withNoToolTheSourceIsInFrontAndTakesTaps() {
        stack(transition(route = null, initialEntry = 1f))
        assertEquals(Color.Red, front())
        assertTrue(inTree("source"))
        assertFalse(inTree("cover"))
        tap()
        assertEquals(listOf("source"), taps)
    }

    @Test
    fun whileTheToolOpensItsCoverIsInFrontAndTapsAreSwallowed() {
        val t = transition()
        stack(t)
        assertEquals(Color.Green, front())
        // Its first placement starts the entry.
        assertTrue(t.entryCoverMeasured)
        assertFalse(inTree("source"))
        tap()
        assertEquals(emptyList<String>(), taps)
    }

    @Test
    fun aSettledToolIsInFrontAloneInTheTreeAndTakesTaps() {
        val t = transition()
        t.runtimeLayerEnabled = true
        t.entryCoverVisible = false
        stack(t)
        assertEquals(Color.Blue, front())
        assertTrue(inTree("tool"))
        assertFalse(inTree("source"))
        assertFalse(inTree("cover"))
        tap()
        assertEquals(listOf("tool"), taps)
    }

    @Test
    fun aReturningSourceIsDrawnOverTheToolWhichLeavesTheTree() {
        val t = transition()
        t.runtimeLayerEnabled = true
        t.entryCoverVisible = false
        t.sourceAboveRuntime = true
        t.runtimeLeaving = true
        stack(t)
        assertEquals(Color.Red, front())
        assertTrue(inTree("source"))
        assertFalse(inTree("tool"))
        tap()
        assertEquals(emptyList<String>(), taps)
    }

    @Test
    fun aCoverStandingInForAReplacedToolStaysBeneathTheSourceUntilItIsHidden() {
        // A switch during a return: the source still shows, the new cover waits beneath it.
        stack(transition(initialEntry = 1f, coverBeneathSource = true))
        assertEquals(Color.Red, front())
        assertTrue(inTree("cover"))

        sourceHidden.value = true
        compose.waitForIdle()
        assertEquals(Color.Green, front())
    }

    private companion object {
        val A = RuntimeRoute("a")
    }
}
