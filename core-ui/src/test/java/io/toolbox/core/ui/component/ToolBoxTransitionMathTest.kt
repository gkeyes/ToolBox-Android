package io.toolbox.core.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the top-bar flicker fix: no frame shows two pages' bars or an intermediate bar state. */
class ToolBoxTransitionMathTest {
    private val largeTitlePx = 156f

    @Test
    fun fadeThroughNeverShowsTwoPagesAtOnce() {
        for (step in 0..1000) {
            val visibility = step / 1000f
            val incoming = ToolBoxMotion.fadeThroughIncomingAlpha(visibility)
            val outgoing = ToolBoxMotion.fadeThroughOutgoingAlpha(visibility)
            assertEquals("Pages overlap at visibility $visibility", 0f, incoming * outgoing, 0f)
        }
    }

    @Test
    fun fadeThroughEndpointsAreExact() {
        // First frame of a push: the new page is invisible and the page beneath is untouched.
        assertEquals(0f, ToolBoxMotion.fadeThroughIncomingAlpha(0f), 0f)
        assertEquals(1f, ToolBoxMotion.fadeThroughOutgoingAlpha(0f), 0f)
        // Settled: only the new page remains.
        assertEquals(1f, ToolBoxMotion.fadeThroughIncomingAlpha(1f), 0f)
        assertEquals(0f, ToolBoxMotion.fadeThroughOutgoingAlpha(1f), 0f)
    }

    @Test
    fun freshListStartsFullyExpandedWithoutIntermediateBarAlpha() {
        val fraction = toolBoxCollapseFraction(0f, largeTitlePx)
        assertEquals(0f, fraction, 0f)
        assertEquals("Bar title must be hidden on the first frame", 0f, toolBoxBarTitleAlpha(fraction), 0f)
        assertEquals("Large title must be fully shown on the first frame", 1f, toolBoxLargeTitleAlpha(fraction), 0f)
    }

    @Test
    fun scrolledPastTitleIsFullyCollapsed() {
        val fraction = toolBoxCollapseFraction(largeTitlePx * 3, largeTitlePx)
        assertEquals(1f, fraction, 0f)
        assertEquals(1f, toolBoxBarTitleAlpha(fraction), 0f)
        assertEquals(0f, toolBoxLargeTitleAlpha(fraction), 0f)
    }

    @Test
    fun barTitleOnlyAppearsOnceLargeTitleIsMostlyGone() {
        for (step in 0..100) {
            val fraction = step / 100f
            if (toolBoxBarTitleAlpha(fraction) > 0f) {
                assertTrue("Both titles visible at $fraction", toolBoxLargeTitleAlpha(fraction) <= 0.25f)
            }
        }
    }

    @Test
    fun staggerSpringStartsAtZeroAndSettles() {
        assertEquals(0f, ToolBoxMotion.springProgress(0f, ToolBoxMotion.SpatialDamping, ToolBoxMotion.SpatialStiffness), 0f)
        val settled = ToolBoxMotion.springProgress(ToolBoxMotion.EntranceDurationMillis.toFloat(), ToolBoxMotion.SpatialDamping, ToolBoxMotion.SpatialStiffness)
        assertEquals(1f, settled, 0.01f)
    }
}
