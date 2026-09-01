package io.toolbox.host.runtime

import com.xzakota.hyper.notification.focus.model.BaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RuntimeNotificationRegressionTest {
    @Test
    fun focusTextProvidesReadableColorsForLightAndDarkSurfaces() {
        val info = BaseInfo()

        applyAdaptiveFocusTextColors(info, "#E53935")

        assertEquals("#E53935", info.colorTitle)
        assertNotEquals("#E53935", info.colorTitleDark)
        assertEquals("#1C1C1E", info.colorContent)
        assertEquals("#FFFFFF", info.colorContentDark)
        assertEquals("#636366", info.colorSubTitle)
        assertEquals("#D1D1D6", info.colorSubTitleDark)
        assertEquals("#636366", info.colorSubContent)
        assertEquals("#D1D1D6", info.colorSubContentDark)
    }

    @Test
    fun leavingRuntimeWithBackgroundSessionKeepsHostAndRefreshesNotification() {
        assertEquals(
            RuntimeForegroundDetachPlan(destroyHost = false, refreshNotification = true),
            runtimeForegroundDetachPlan(hasBackgroundSession = true),
        )
        assertEquals(
            RuntimeForegroundDetachPlan(destroyHost = true, refreshNotification = false),
            runtimeForegroundDetachPlan(hasBackgroundSession = false),
        )
    }
}
