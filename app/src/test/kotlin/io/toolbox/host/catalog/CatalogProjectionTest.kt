package io.toolbox.host.catalog

import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogGroup
import io.toolbox.core.data.CatalogLayout
import io.toolbox.core.data.CatalogSort
import io.toolbox.core.data.SecurityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogProjectionTest {
    @Test fun queryAndLayoutChangesReuseSortingAndRecentResults() {
        val work = ProjectionWork()
        val tools = listOf(tool("b", "Beta", 2, 10), tool("a", "Alpha", 1, 20))
        val layout = CatalogLayout(groups = listOf(CatalogGroup("group", "分组")))
        val initial = work.projection.project(tools, layout, "")
        val search = work.projection.project(tools, layout, "AlPhA")
        assertEquals(listOf("a"), search.visibleTools.map { it.toolId })
        assertTrue(search.isSearching)
        assertSame(initial.tools, search.tools)
        assertSame(initial.recentTools, search.recentTools)
        assertEquals(listOf(1, 1, 2), work.counts())

        val changedLayout = layout.copy(
            favorites = listOf("a"),
            groups = listOf(CatalogGroup("group", "已改名", listOf("a"), expanded = false)),
        )
        val expanded = work.projection.project(tools.toList(), changedLayout, " AlPhA ")
        assertEquals(changedLayout, expanded.layout)
        assertEquals(" AlPhA ", expanded.query)
        assertSame(search.visibleTools, expanded.visibleTools)
        assertSame(search.recentTools, expanded.recentTools)
        assertEquals(listOf(1, 1, 2), work.counts())

        val sorted = work.projection.project(tools, changedLayout.copy(sort = CatalogSort.NAME), " AlPhA ")
        assertSame(search.recentTools, sorted.recentTools)
        assertEquals(listOf(2, 1, 3), work.counts())
        val cleared = work.projection.project(tools, changedLayout.copy(sort = CatalogSort.NAME), "  ")
        assertEquals(listOf("a", "b"), cleared.visibleTools.map { it.toolId })
        assertFalse(cleared.isSearching)
        assertEquals(listOf(2, 1, 4), work.counts())
    }

    @Test fun usageAndVersionUpdatesRefreshObjectsWithoutResortingUnchangedNameKeys() {
        val work = ProjectionWork()
        val a = tool("a", "Beta", 2, 20)
        val b = tool("b", "Alpha", 1, 10)
        val layout = CatalogLayout(sort = CatalogSort.NAME)
        work.projection.project(listOf(a, b), layout, "")
        val opened = b.copy(lastOpenedAt = 30)
        val usage = work.projection.project(listOf(a, opened), layout, "")
        assertEquals(listOf("b", "a"), usage.visibleTools.map { it.toolId })
        assertEquals(listOf("b", "a"), usage.recentTools.map { it.toolId })
        assertSame(opened, usage.visibleTools.first())
        assertSame(opened, usage.recentTools.first())
        assertEquals(listOf(1, 2, 2), work.counts())

        val updated = opened.copy(versionCode = 2, versionName = "2.0.0", bundleBytes = 25, installedAt = 100)
        val version = work.projection.project(listOf(updated, a), layout, "")
        assertSame(updated, version.visibleTools.first())
        assertSame(updated, version.recentTools.first())
        assertEquals(listOf(1, 2, 3), work.counts())

        // A never-opened addition affects the full catalog, but not the recent order.
        val neverOpened = tool("c", "Charlie", 3, null)
        val added = work.projection.project(listOf(a, updated, neverOpened), layout, "")
        assertSame(version.recentTools, added.recentTools)
        assertEquals(listOf(2, 2, 4), work.counts())
    }

    @Test fun namesAreReusedForMetadataUpdatesAndEvictedWithRemovedTools() {
        val calculatedNames = mutableListOf<String>()
        val projection = CatalogToolProjection { name -> calculatedNames += name; "key:$name" }
        val a = entry("a", "阿里")
        val b = entry("b", "北京")
        val initial = projection.project(listOf(a, b))
        val changed = projection.project(listOf(a, b.copy(lastOpenedAt = 20, versionCode = 2, bundleBytes = 100)))
        assertEquals(listOf("阿里", "北京"), calculatedNames)
        assertSame(initial.first(), changed.first())
        assertEquals(20L, changed.last().lastOpenedAt)
        assertEquals(2, changed.last().versionCode)
        assertEquals("key:北京", changed.last().nameSortKey)

        val renamed = a.copy(name = "上海")
        projection.project(listOf(renamed, b))
        assertEquals(listOf("阿里", "北京", "上海"), calculatedNames)
        projection.project(listOf(renamed))
        projection.project(listOf(renamed, b))
        assertEquals(listOf("阿里", "北京", "上海", "北京"), calculatedNames)
        projection.project(emptyList())
        projection.project(listOf(renamed))
        assertEquals(listOf("阿里", "北京", "上海", "北京", "上海"), calculatedNames)
    }

    @Test fun cachedListsMatchFreshDerivationAcrossEverySortAndCatalogMutation() {
        val a = tool("a", "apple", 1, 10)
        val b = tool("b", "Beta", 2, null)
        val c = tool("c", "Apple", 1, 10)
        val snapshots = listOf(
            listOf(a, b, c),
            listOf(c, b, a),
            listOf(a.copy(versionCode = 2, bundleBytes = 99), b, c),
            listOf(a.copy(name = "Apple"), b, c), // Same normalized key, different name tie-breaker.
            listOf(a.copy(nameSortKey = "z"), b, c),
            listOf(a.copy(installedAt = 5), b, c),
            listOf(a, b.copy(lastOpenedAt = 30), c.copy(lastOpenedAt = null)),
            listOf(a, c),
            emptyList(),
            listOf(b, c, a),
        )
        CatalogSort.entries.forEach { sort ->
            val projection = CatalogListProjection()
            val layout = CatalogLayout(sort = sort)
            snapshots.forEach { tools ->
                listOf("", " aPp ", "b", "missing", "  ").forEach { query ->
                    val cached = projection.project(tools, layout, query)
                    val fresh = CatalogUiState(layout = layout, query = query).withCatalogTools(tools)
                    assertEquals("$sort / $query", fresh.visibleTools, cached.visibleTools)
                    assertEquals("$sort recent", fresh.recentTools, cached.recentTools)
                    assertEquals(tools, cached.tools)
                    assertEquals(query, cached.query)
                }
            }
        }
    }

    private class ProjectionWork {
        private var sorts = 0
        private var recents = 0
        private var filters = 0
        val projection = CatalogListProjection(
            sortTools = { tools, sort -> sorts++; tools.catalogSorted(sort) },
            recentTools = { tools -> recents++; tools.catalogRecent() },
            filterTools = { tools, query -> filters++; tools.filteredBy(query) },
        )
        fun counts() = listOf(sorts, recents, filters)
    }

    private fun tool(id: String, name: String, installed: Long, opened: Long?) =
        CatalogTool(id, name, 1, "1.0.0", 1, opened, installed)

    private fun entry(id: String, name: String) = CatalogEntry(
        toolId = id,
        name = name,
        securityProfile = SecurityProfile.STRICT,
        installedAt = 1,
        lastOpenedAt = null,
        pinnedOrder = null,
        categoryId = null,
        versionCode = 1,
        version = "1.0.0",
        bundleBytes = 1,
    )
}
