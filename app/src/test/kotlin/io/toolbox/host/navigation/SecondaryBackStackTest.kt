package io.toolbox.host.navigation

import android.content.Intent
import io.toolbox.host.shouldOpenLaunchIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.NavKey

class SecondaryBackStackTest {
    @Test
    fun openingAToolWhileAnotherIsOpenReplacesItInsteadOfStackingIt() {
        val stack = mutableListOf<NavKey>(RuntimeRoute("a"))

        stack.pushSecondary(RuntimeRoute("b"))

        assertEquals(listOf<NavKey>(RuntimeRoute("b")), stack)
        assertEquals(emptyList<ToolBoxRoute>(), retainedSecondaryRoutes(stack.filterIsInstance<ToolBoxRoute>()))
    }

    @Test
    fun openingAToolFromItsDetailsKeepsTheDetailsPageBeneath() {
        val stack = mutableListOf<NavKey>(ToolDetailRoute("a"), RuntimeRoute("a"))

        stack.pushSecondary(RuntimeRoute("b"))

        assertEquals(listOf<NavKey>(ToolDetailRoute("a"), RuntimeRoute("b")), stack)
    }

    @Test
    fun reopeningTheToolOnTopChangesNothing() {
        val stack = mutableListOf<NavKey>(RuntimeRoute("a"))

        stack.pushSecondary(RuntimeRoute("a"))

        assertEquals(listOf<NavKey>(RuntimeRoute("a")), stack)
    }

    @Test
    fun retainedPagesNeverIncludeAToolPage() {
        val routes = listOf(ToolDetailRoute("a"), RuntimeRoute("a"), AboutRoute, RuntimeRoute("b"))

        assertEquals(listOf(ToolDetailRoute("a"), AboutRoute), retainedSecondaryRoutes(routes))
    }

    @Test
    fun onlyAFreshLaunchActsOnItsIntent() {
        assertTrue(shouldOpenLaunchIntent(restored = false, intentFlags = 0))
        // Rotation, dark/light switch or resize recreates the activity with the old launch intent.
        assertFalse(shouldOpenLaunchIntent(restored = true, intentFlags = 0))
        assertFalse(shouldOpenLaunchIntent(restored = false, intentFlags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
    }
}
