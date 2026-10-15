package io.toolbox.host.ui

import io.toolbox.host.catalog.CatalogTool
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeShortcutsTest {
    private fun tool(id: String) = CatalogTool(id, id, 1, "1.0.0", 1, null)
    private fun ids(prefix: String, count: Int) = (1..count).map { tool("$prefix$it") }

    @Test fun keepsEveryFavoriteBeyondTheLimit() {
        val shortcuts = homeShortcuts(ids("f", 10), ids("r", 3))
        assertEquals(ids("f", 10).map { it.toolId }, shortcuts.map { it.toolId })
    }

    @Test fun recentToolsFillOnlyTheFreeSlotsWithoutDuplicates() {
        val favorites = ids("f", 5)
        val recent = listOf(tool("f2"), tool("r1"), tool("r1"), tool("r2"), tool("r3"), tool("r4"))
        assertEquals(listOf("f1", "f2", "f3", "f4", "f5", "r1", "r2", "r3"), homeShortcuts(favorites, recent).map { it.toolId })
    }
}
