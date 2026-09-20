package io.toolbox.host.catalog

import io.toolbox.core.data.CatalogLayout
import io.toolbox.core.data.CatalogSort
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSortTest {
    private fun tool(id: String, name: String, installed: Long, opened: Long?, key: String = name.lowercase()) =
        CatalogTool(id, name, 1, "1.0.0", 1, opened, installed, key)

    @Test fun allSortsHaveStableTiesAndSearchUsesSameOrder() {
        val tools = listOf(tool("b", "Beta", 2, null), tool("z", "Alpha", 1, 20),
            tool("a", "Alpha", 1, 20), tool("n", "10", 3, 10))
        assertEquals(listOf("n", "b", "a", "z"), tools.catalogSorted(CatalogSort.INSTALLED).map { it.toolId })
        assertEquals(listOf("n", "a", "z", "b"), tools.catalogSorted(CatalogSort.NAME).map { it.toolId })
        assertEquals(listOf("a", "z", "n", "b"), tools.catalogSorted(CatalogSort.LAST_OPENED).map { it.toolId })
        CatalogSort.entries.forEach { sort ->
            val state = CatalogListProjection().project(tools, CatalogLayout(sort = sort), "AlPhA")
            assertEquals(listOf("a", "z"), state.visibleTools.map { it.toolId })
        }
    }

    @Test fun recentShowsAllOpenedToolsAndRetainsAcceptedRequestMeaning() {
        val tools = (0..80).map { tool("id.$it", "工具$it", 0, it.toLong()) } + tool("never", "未打开", 0, null)
        val state = CatalogUiState().withCatalogTools(tools)
        assertEquals(81, state.recentTools.size)
        assertEquals("id.80", state.recentTools.first().toolId)
        assertEquals("id.0", state.recentTools.last().toolId)
    }
}
