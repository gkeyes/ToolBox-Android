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
    fun capabilityRoutesRetainTypedStateInsteadOfWholeScreenModels() {
        val details = ToolDetailRoute("io.toolbox.alpha")
        val permissions = PermissionCenterRoute("io.toolbox.alpha")
        val runtime = RuntimeRoute("io.toolbox.alpha")

        assertEquals("io.toolbox.alpha", details.toolId)
        assertEquals(details.toolId, permissions.toolId)
        assertEquals(details.toolId, runtime.toolId)
        assertNotEquals(details, ToolDetailRoute("io.toolbox.beta"))
        val importReview: Any = ImportReviewRoute
        assertNotEquals(importReview, runtime)
    }

    @Test
    fun importReviewBackDispatchesThroughSessionCleanupOwner() {
        var importReviewBackCount = 0
        var defaultBackCount = 0

        dispatchHostBack(
            currentRoute = ImportReviewRoute,
            onImportReviewBack = { importReviewBackCount += 1 },
            onDefaultBack = { defaultBackCount += 1 },
        )

        assertEquals(1, importReviewBackCount)
        assertEquals(0, defaultBackCount)

        dispatchHostBack(
            currentRoute = SettingsRoute,
            onImportReviewBack = { importReviewBackCount += 1 },
            onDefaultBack = { defaultBackCount += 1 },
        )

        assertEquals(1, importReviewBackCount)
        assertEquals(1, defaultBackCount)
    }
}
