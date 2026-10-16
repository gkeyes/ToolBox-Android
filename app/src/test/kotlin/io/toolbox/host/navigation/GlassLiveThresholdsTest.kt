package io.toolbox.host.navigation

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
        assertTrue(hidden(entryCoverVisible = true, entryProgress = 0f))
    }

    @Test
    fun returningFromARuntimeKeepsTheSourceLiveUntilItIsFullyGone() {
        assertFalse(hidden(sourceAboveRuntime = true, sourceReturnProgress = 0f))
        assertFalse(hidden(sourceAboveRuntime = true, sourceReturnProgress = 0.9f))
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
