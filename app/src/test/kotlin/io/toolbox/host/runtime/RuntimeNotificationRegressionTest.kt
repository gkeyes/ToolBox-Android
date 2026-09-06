package io.toolbox.host.runtime

import com.xzakota.hyper.notification.focus.model.BaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RuntimeNotificationRegressionTest {
    @Test
    fun focusTextColorsRemainUnderSystemUiControl() {
        val info = BaseInfo()

        listOf(
            info.colorTitle, info.colorTitleDark,
            info.colorContent, info.colorContentDark,
            info.colorSubTitle, info.colorSubTitleDark,
            info.colorExtraTitle, info.colorExtraTitleDark,
            info.colorSubContent, info.colorSubContentDark,
        ).forEach { color -> assertNotEquals("#FFFFFF", color?.uppercase()) }
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
