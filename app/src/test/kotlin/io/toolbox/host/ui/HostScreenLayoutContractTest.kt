package io.toolbox.host.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HostScreenLayoutContractTest {
    @Test
    fun compactAndMediumWidthsSelectTheContentSpacingUsedByTheHost() {
        val compact = hostRouteLayoutFor(360.dp)
        val medium = hostRouteLayoutFor(840.dp)

        assertEquals(true, compact.isCompact)
        assertEquals(false, medium.isCompact)
        assertEquals(16.dp, compact.horizontalContentPadding)
        assertEquals(28.dp, medium.horizontalContentPadding)
        assertEquals(16.dp, compact.verticalContentPadding)
        assertEquals(16.dp, medium.verticalContentPadding)
    }
}
