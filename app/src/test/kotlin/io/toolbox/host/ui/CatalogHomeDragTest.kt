package io.toolbox.host.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class CatalogHomeDragTest {
    @Test fun collapsingGroupsWithoutPointerMovementDoesNotReorder() {
        val moves = mutableListOf<Int>()
        val drag = CatalogHomeDragState().apply { listBounds = Rect(0f, 0f, 400f, 700f); touchSlop = 8f }
        drag.targets["first"] = HomeDragTarget("first", "groups", 0, "First", null, Rect(0f, 0f, 300f, 56f), moves::add)
        drag.targets["source"] = HomeDragTarget("source", "groups", 1, "Source", null, Rect(0f, 500f, 300f, 556f), moves::add)
        drag.start("source", Offset(100f, 525f))
        drag.targets["source"] = drag.targets.getValue("source").copy(bounds = Rect(0f, 56f, 300f, 112f))
        drag.targets["last"] = HomeDragTarget("last", "groups", 2, "Last", null, Rect(0f, 112f, 300f, 168f), moves::add)
        drag.updateTarget()
        drag.finish()
        assertTrue(moves.isEmpty())
        assertNull(drag.active)
        assertFalse(drag.collapsingGroups)
    }

    @Test fun droppingUsesBothCoordinatesAndOnlyTheOriginalCollection() {
        val moves = mutableListOf<Int>()
        val drag = CatalogHomeDragState().apply { listBounds = Rect(0f, 0f, 400f, 700f); touchSlop = 8f }
        drag.targets["a"] = HomeDragTarget("a", "favorites", 0, "A", null, Rect(0f, 0f, 80f, 100f), moves::add)
        drag.targets["b"] = HomeDragTarget("b", "favorites", 1, "B", null, Rect(92f, 0f, 172f, 100f), moves::add)
        drag.targets["other"] = HomeDragTarget("other", "members", 9, "Other", null, Rect(94f, 0f, 174f, 100f), moves::add)
        drag.start("a", Offset(40f, 50f))
        drag.move(Offset(132f, 50f))
        drag.finish()
        assertEquals(listOf(1), moves)
    }

    @Test fun largeTypeAndNarrowWindowsReduceColumnsWithoutClippingMinimumWidth() {
        assertEquals(4, homeGridColumnCount(324f, 1f))
        assertEquals(2, homeGridColumnCount(324f, 1.6f))
        assertEquals(1, homeGridColumnCount(90f, 2f))
        assertTrue(homeGridColumnCount(680f, 1f) > 4)
    }

    @Test fun groupCapsulesTakeAPhoneRowAndShareWideWindows() {
        assertEquals(1, homeFolderColumnCount(358f, 1f))
        assertEquals(1, homeFolderColumnCount(358f, 2f))
        assertEquals(1, homeFolderColumnCount(90f, 1f))
        assertEquals(2, homeFolderColumnCount(680f, 1f))
        assertEquals(4, homeFolderColumnCount(1600f, 1f))
    }

    @Test fun groupCapsuleSlotsStayBetweenSmallAndFullIconSize() {
        assertEquals(72f, groupCapsuleMetrics(358.dp).slot.value, 0.01f)
        assertEquals(44f, groupCapsuleMetrics(120.dp).slot.value, 0.01f)
        val phone = groupCapsuleMetrics(288.dp)
        assertEquals(phone.slot.value + 24f, phone.height.value, 0.01f)
    }

    @Test fun unknownOrLegacyGroupStylesFallBackToTheDefaultSwatchAndIcon() {
        assertEquals(CatalogGroupColors.first(), catalogGroupColor(null))
        assertEquals(CatalogGroupColors.first(), catalogGroupColor("no-such-colour"))
        assertEquals("green", catalogGroupColor("green").key)
        assertEquals(CatalogGroupGlyphs.first(), catalogGroupGlyph(null))
        assertEquals(CatalogGroupGlyphs.first(), catalogGroupGlyph("NoSuchIcon"))
        assertEquals("Code", catalogGroupGlyph("Code").key)
        assertEquals(CatalogGroupColors.size, CatalogGroupColors.map { it.key }.toSet().size)
        assertEquals(CatalogGroupGlyphs.size, CatalogGroupGlyphs.map { it.key }.toSet().size)
    }

    @Test fun edgeScrollStopsAtTheEndOfItsOwnCollection() {
        val drag = CatalogHomeDragState().apply { listBounds = Rect(0f, 0f, 400f, 700f) }
        drag.targets["a"] = HomeDragTarget("a", "favorites", 0, "A", null, Rect(0f, 0f, 80f, 100f), {}, 2)
        drag.targets["b"] = HomeDragTarget("b", "favorites", 1, "B", null, Rect(92f, 0f, 172f, 100f), {}, 2)
        drag.start("a", Offset(40f, 50f))
        drag.move(Offset(130f, 650f))
        assertFalse(drag.canScroll(1f, 0f, 600f))
        assertFalse(drag.canScroll(-1f, 0f, 600f))
        drag.targets["b"] = drag.targets.getValue("b").copy(bounds = Rect(92f, 580f, 172f, 680f))
        assertTrue(drag.canScroll(1f, 0f, 600f))
    }
}
