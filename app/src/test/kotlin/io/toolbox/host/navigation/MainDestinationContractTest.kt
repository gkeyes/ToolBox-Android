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
        val unavailable = CapabilityUnavailableRoute(io.toolbox.host.ui.HostCapability.ImportTools)

        assertEquals(io.toolbox.host.ui.HostCapability.ImportTools, unavailable.capability)
        assertNotEquals(unavailable, CapabilityUnavailableRoute(io.toolbox.host.ui.HostCapability.Runtime))
    }
}
