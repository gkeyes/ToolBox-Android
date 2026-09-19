package io.toolbox.core.data.settings

import io.toolbox.core.data.*
import org.junit.Assert.*
import org.junit.Test

class CatalogLayoutCodecTest {
    @Test fun roundTripHasNoProductQuotasAndUsesStableIds() {
        val layout = CatalogLayout(favorites = (1..300).map { "tool.$it" }, groups = listOf(
            CatalogGroup("a", "同名".repeat(200), listOf("tool.1")),
            CatalogGroup("b", "同名", listOf("tool.1")),
            CatalogGroup("empty", "空组", expanded = false),
        ), sort = CatalogSort.LAST_OPENED)
        assertEquals(layout, CatalogLayoutCodec.decode(CatalogLayoutCodec.encode(layout)))
        val reordered = layout.moveFavorite("tool.3", -2).moveGroup("empty", -2)
        assertEquals("tool.3", reordered.favorites.first())
        assertEquals("empty", reordered.groups.first().id)
        val deleted = reordered.copy(groups = reordered.groups.filterNot { it.id == "a" })
        assertTrue("tool.1" in deleted.favorites)
        assertEquals(listOf("tool.1"), deleted.groups.single { it.id == "b" }.members)
    }

    @Test fun corruptPresentLayoutNeverDefaultsToEmpty() {
        listOf("{}", "null", """{"version":1,"favorites":["a","a"],"groups":[],"sort":"INSTALLED"}""",
            """{"version":1,"favorites":[],"groups":[{"id":"g","name":" ","members":[],"expanded":true}],"sort":"INSTALLED"}""",
            """{"version":1,"favorites":[],"groups":[],"sort":"unknown"}""").forEach {
            assertTrue(it, runCatching { CatalogLayoutCodec.decode(it) }.isFailure)
        }
    }

    @Test fun orderingAndMembershipAreIndependent() {
        val layout = CatalogLayout(favorites = listOf("a", "b"), groups = listOf(CatalogGroup("g", "组", listOf("a", "b", "c"))))
        val next = layout.moveMember("g", "c", -2).favorite("a", false)
        assertEquals(listOf("c", "a", "b"), next.groups.single().members)
        assertEquals(listOf("b"), next.favorites)
        assertEquals(next, next.moveGroup("missing", -1).moveFavorite("b", Int.MAX_VALUE))
    }
}
