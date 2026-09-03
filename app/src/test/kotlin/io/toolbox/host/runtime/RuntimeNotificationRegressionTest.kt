package io.toolbox.host.runtime

import com.xzakota.hyper.notification.focus.model.BaseInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeNotificationRegressionTest {
    @Test
    fun focusTextIsWhiteInBothThemeVariantsOfTheDarkLiveSurface() {
        val info = BaseInfo()

        applyWhiteFocusTextColors(info)

        listOf(
            info.colorTitle, info.colorTitleDark,
            info.colorContent, info.colorContentDark,
            info.colorSubTitle, info.colorSubTitleDark,
            info.colorExtraTitle, info.colorExtraTitleDark,
            info.colorSubContent, info.colorSubContentDark,
        ).forEach { color -> assertEquals("#FFFFFF", color) }
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
