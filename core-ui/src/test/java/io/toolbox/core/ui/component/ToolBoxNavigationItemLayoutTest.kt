package io.toolbox.core.ui.component

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolBoxNavigationItemLayoutTest {

    @Test
    fun navigationTopBarAndSearchFieldUseFixedTouchSafeMinimums() {
        assertEquals(64.dp, toolBoxNavigationItemMinHeight())
        assertEquals(52.dp, toolBoxTopBarMinHeight())
        assertEquals(48.dp, toolBoxSearchFieldMinHeight())
    }
}
