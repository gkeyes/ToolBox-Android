package io.toolbox.host.navigation

import io.toolbox.host.ui.MainDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MainDestinationContractTest {
    @Test
    fun topLevelDestinationsExposeTheThreeHostTabsInDesignOrder() {
        assertEquals(
            listOf(
                MainDestination.Home,
                MainDestination.Tools,
                MainDestination.Settings,
            ),
            MainDestination.entries.toList(),
        )
        assertEquals(listOf("首页", "工具", "设置"), MainDestination.entries.map(MainDestination::label))
    }

    @Test
    fun parameterizedRoutesRetainOpaqueIdentifiersInsteadOfWholeScreenModels() {
        val import = ImportReviewRoute(sessionId = "inspection-42")
        val runtime = RuntimeRoute(toolId = "io.toolbox.positioncalculator")
        val detail = ToolDetailRoute(toolId = "io.toolbox.positioncalculator")

        assertEquals("inspection-42", import.sessionId)
        assertEquals("io.toolbox.positioncalculator", runtime.toolId)
        assertEquals("io.toolbox.positioncalculator", detail.toolId)
        assertNotEquals(import, ImportReviewRoute(sessionId = "inspection-43"))
        assertNotEquals(runtime, RuntimeRoute(toolId = "io.toolbox.other"))
        assertNotEquals(detail, ToolDetailRoute(toolId = "io.toolbox.other"))
    }
}
