package io.toolbox.core.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolBoxAppScaffoldInsetTest {

    @Test
    fun compactScaffoldAssignsEachSystemInsetToOneSemanticOwner() {
        val ownership = ToolBoxAppScaffoldInsetPolicy.resolve(
            hasTopBar = true,
            hasBottomBar = true,
            hasFloatingActionButton = true,
        )

        assertEquals(ToolBoxInsetOwner.TopBar, ownership.statusBars)
        assertEquals(ToolBoxInsetOwner.TopBar, ownership.displayCutout)
        assertEquals(ToolBoxInsetOwner.BottomBar, ownership.navigationBars)
        assertEquals(ToolBoxInsetOwner.Content, ownership.ime)
        assertEquals(ToolBoxInsetOwner.InheritedFromBottomBar, ownership.floatingActionButton)
    }

    @Test
    fun mediumScaffoldLeavesNavigationAndImeToContentInsteadOfTheSideNavigation() {
        val ownership = ToolBoxAppScaffoldInsetPolicy.resolve(
            hasTopBar = true,
            hasBottomBar = false,
            hasFloatingActionButton = false,
        )

        assertEquals(ToolBoxInsetOwner.TopBar, ownership.statusBars)
        assertEquals(ToolBoxInsetOwner.TopBar, ownership.displayCutout)
        assertEquals(ToolBoxInsetOwner.Content, ownership.navigationBars)
        assertEquals(ToolBoxInsetOwner.Content, ownership.ime)
        assertEquals(ToolBoxInsetOwner.None, ownership.floatingActionButton)
    }

    @Test
    fun scaffoldWithoutBottomBarKeepsContentAndFloatingActionButtonIndependentlyReachable() {
        val ownership = ToolBoxAppScaffoldInsetPolicy.resolve(
            hasTopBar = true,
            hasBottomBar = false,
            hasFloatingActionButton = true,
        )

        assertEquals(ToolBoxInsetOwner.Content, ownership.navigationBars)
        assertEquals(ToolBoxInsetOwner.Content, ownership.ime)
        assertEquals(ToolBoxInsetOwner.FloatingActionButton, ownership.floatingActionButton)
    }

    @Test
    fun expandedScaffoldWithoutBarsKeepsCutoutNavigationAndImeWithContent() {
        val ownership = ToolBoxAppScaffoldInsetPolicy.resolve(
            hasTopBar = false,
            hasBottomBar = false,
            hasFloatingActionButton = false,
        )

        assertEquals(ToolBoxInsetOwner.Content, ownership.statusBars)
        assertEquals(ToolBoxInsetOwner.Content, ownership.displayCutout)
        assertEquals(ToolBoxInsetOwner.Content, ownership.navigationBars)
        assertEquals(ToolBoxInsetOwner.Content, ownership.ime)
        assertEquals(ToolBoxInsetOwner.None, ownership.floatingActionButton)
    }
}
