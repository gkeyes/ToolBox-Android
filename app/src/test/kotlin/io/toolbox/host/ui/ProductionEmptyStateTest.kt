package io.toolbox.host.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionEmptyStateTest {
    @Test
    fun freshInstallShowsZeroToolsAndAnImportActionWithoutToolCards() {
        val state = ProductionHostState.freshInstall()

        assertEquals(0, state.home.installedToolCount)
        assertEquals(0, state.toolManager.installedToolCount)
        assertEquals(UiState.Empty, state.home.state)
        assertEquals(UiState.Empty, state.toolManager.state)
        assertTrue(state.home.tools.isEmpty())
        assertTrue(state.toolManager.tools.isEmpty())
    }
}
