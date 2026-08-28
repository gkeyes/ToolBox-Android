package io.toolbox.core.ui.component

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolBoxNavigationItemLayoutTest {

    @Test
    fun navigationTopBarAndSearchFieldMinHeightsExpandWithLargeFontScaleWithoutDroppingBelowTouchTarget() {
        assertEquals(64.dp, toolBoxNavigationItemMinHeight(fontScale = 1f))
        assertEquals(128.dp, toolBoxNavigationItemMinHeight(fontScale = 2f))
        assertEquals(64.dp, toolBoxNavigationItemMinHeight(fontScale = 0.5f))
        assertEquals(64.dp, toolBoxTopBarMinHeight(fontScale = 1f))
        assertEquals(128.dp, toolBoxTopBarMinHeight(fontScale = 2f))
        assertEquals(64.dp, toolBoxTopBarMinHeight(fontScale = 0.5f))
        assertEquals(52.dp, toolBoxSearchFieldMinHeight(fontScale = 1f))
        assertEquals(104.dp, toolBoxSearchFieldMinHeight(fontScale = 2f))
        assertEquals(52.dp, toolBoxSearchFieldMinHeight(fontScale = 0.5f))
    }
}
