package io.toolbox.core.ui.component

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolBoxNavigationItemLayoutTest {

    @Test
    fun navigationTopBarAndSearchFieldKeepBoundedTouchSafeHeightsAtLargeFontScale() {
        assertEquals(56.dp, toolBoxNavigationItemMinHeight(fontScale = 1f))
        assertEquals(72.dp, toolBoxNavigationItemMinHeight(fontScale = 2f))
        assertEquals(56.dp, toolBoxNavigationItemMinHeight(fontScale = 0.5f))
        assertEquals(56.dp, toolBoxTopBarMinHeight(fontScale = 1f))
        assertEquals(72.dp, toolBoxTopBarMinHeight(fontScale = 2f))
        assertEquals(56.dp, toolBoxTopBarMinHeight(fontScale = 0.5f))
        assertEquals(48.dp, toolBoxSearchFieldMinHeight(fontScale = 1f))
        assertEquals(48.dp, toolBoxSearchFieldMinHeight(fontScale = 2f))
        assertEquals(48.dp, toolBoxSearchFieldMinHeight(fontScale = 0.5f))
    }
}
