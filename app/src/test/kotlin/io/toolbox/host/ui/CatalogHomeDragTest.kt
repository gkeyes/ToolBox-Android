package io.toolbox.host.ui

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
