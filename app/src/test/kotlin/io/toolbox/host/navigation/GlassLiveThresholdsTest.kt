package io.toolbox.host.navigation

import io.toolbox.core.ui.component.ToolBoxMotion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassLiveThresholdsTest {
    @Test
    fun aPageIsCoveredOnlyOnceThePageAboveHasFullySlidIn() {
        assertFalse(pageFullyCovered(null))
        assertFalse(pageFullyCovered(1f))
        assertFalse(pageFullyCovered(0.5f))
        assertFalse(pageFullyCovered(0.01f))
        assertTrue(pageFullyCovered(0.001f))
        assertTrue(pageFullyCovered(0f))
    }

    @Test
    fun aPageIsOffScreenOnlyOnceItHasFullySlidOut() {
        assertFalse(pageOffScreen(0f))
        assertFalse(pageOffScreen(0.5f))
        assertFalse(pageOffScreen(0.99f))
        assertTrue(pageOffScreen(0.999f))
        assertTrue(pageOffScreen(1f))
    }

    @Test
    fun withoutARuntimeTheSourceLayerIsNeverHidden() {
        assertFalse(hidden(runtimeOpen = false, entryCoverVisible = true, entryProgress = 0f))
    }

    @Test
    fun enteringARuntimeHidesTheSourceOnlyOnceItHasFadedOut() {
        assertFalse(hidden(entryCoverVisible = true, entryProgress = 1f))
        assertFalse(hidden(entryCoverVisible = true, entryProgress = 0.8f))
        assertTrue(hidden(entryCoverVisible = true, entryProgress = 0f))
    }

    @Test
    fun theRuntimeEntryHidesTheSourceWhereTheSourceLayerAlphaReachesTheHiddenEdge() {
        // The source layer's alpha during the entry is fadeThroughOutgoingAlpha(1 - entryProgress)
        // (ToolBoxNavigation's graphicsLayer), so the glass must pause on the progress where that
        // alpha reaches GlassHiddenEdge: 1 - FadeThroughSplit * (1 - GlassHiddenEdge).
        val cutoff = 1f - ToolBoxMotion.FadeThroughSplit * (1f - GlassHiddenEdge)
        for (progress in listOf(cutoff + 0.01f, cutoff + 0.0001f)) {
            assertTrue(ToolBoxMotion.fadeThroughOutgoingAlpha(1f - progress) > GlassHiddenEdge)
            assertFalse(hidden(entryCoverVisible = true, entryProgress = progress))
        }
        for (progress in listOf(cutoff - 0.0001f, cutoff - 0.01f)) {
            assertTrue(ToolBoxMotion.fadeThroughOutgoingAlpha(1f - progress) <= GlassHiddenEdge)
            assertTrue(hidden(entryCoverVisible = true, entryProgress = progress))
        }
    }

    @Test
    fun returningFromARuntimeKeepsTheSourceLiveUntilItIsFullyGone() {
        assertFalse(hidden(sourceAboveRuntime = true, sourceReturnProgress = 0f))
        assertFalse(hidden(sourceAboveRuntime = true, sourceReturnProgress = 0.9f))
        assertFalse(hidden(sourceAboveRuntime = true, sourceReturnProgress = 0.99f))
        assertFalse(hidden(sourceAboveRuntime = true, sourceReturnProgress = 0.998f))
        assertTrue(hidden(sourceAboveRuntime = true, sourceReturnProgress = 0.999f))
        assertTrue(hidden(sourceAboveRuntime = true, sourceReturnProgress = 1f))
    }

    @Test
    fun aSettledRuntimeHidesTheSource() {
        assertTrue(hidden(entryCoverVisible = false))
    }

    private fun hidden(
        runtimeOpen: Boolean = true,
        sourceAboveRuntime: Boolean = false,
        sourceReturnProgress: Float = 0f,
        entryCoverVisible: Boolean = false,
        entryProgress: Float = 0f,
    ) = sourceLayerHiddenByRuntime(
        runtimeOpen = runtimeOpen,
        sourceAboveRuntime = sourceAboveRuntime,
        sourceReturnProgress = sourceReturnProgress,
        entryCoverVisible = entryCoverVisible,
        entryProgress = entryProgress,
    )
}
