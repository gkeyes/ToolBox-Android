package io.toolbox.core.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the blank-secondary-page fix: a settled, uncovered page is always fully visible. */
class ToolBoxPageVisibilityTest {
    @Test
    fun settledDestinationIsAlwaysFullyVisible() {
        // Settled push (and the instant result with animations disabled): progress 0, nothing above.
        assertEquals(1f, ToolBoxMotion.pageLayerAlpha(motion = 0f, coverage = 0f), 0f)
        // Rounding noise from a spring that has just settled must not hide the page either.
        assertEquals(1f, ToolBoxMotion.pageLayerAlpha(motion = 0.0005f, coverage = 0f), 0f)
        assertEquals(1f, ToolBoxMotion.pageLayerAlpha(motion = -0.01f, coverage = -0.01f), 0f)
    }

    @Test
    fun pageAppearsMonotonicallyDuringItsPush() {
        var previous = -1f
        for (step in 1000 downTo 0) {
            val alpha = ToolBoxMotion.pageLayerAlpha(motion = step / 1000f, coverage = 0f)
            assertTrue("alpha went backwards at ${step / 1000f}", alpha >= previous)
            previous = alpha
        }
        assertEquals(1f, previous, 0f)
    }

    @Test
    fun onlyAPageCoveredBySettledPageIsHidden() {
        assertEquals(0f, ToolBoxMotion.pageLayerAlpha(motion = 0f, coverage = 1f), 0f)
        assertEquals(0f, ToolBoxMotion.pageLayerAlpha(motion = 1f, coverage = 0f), 0f)
    }
}
