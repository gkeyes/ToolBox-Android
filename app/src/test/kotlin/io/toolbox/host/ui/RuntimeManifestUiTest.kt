package io.toolbox.host.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.toolbox.tool.packagekit.ManifestOrientation
import io.toolbox.tool.packagekit.ManifestStatusBarStyle
import io.toolbox.tool.packagekit.ManifestUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RuntimeManifestUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun manifestValuesMapToActivityValues() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ManifestOrientation.PORTRAIT.requestedOrientation())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ManifestOrientation.LANDSCAPE.requestedOrientation())
        assertNull(ManifestOrientation.UNSPECIFIED.requestedOrientation())
        assertNull((null as ManifestOrientation?).requestedOrientation())
        assertEquals(false, ManifestStatusBarStyle.LIGHT.darkStatusBarIcons())
        assertEquals(true, ManifestStatusBarStyle.DARK.darkStatusBarIcons())
        assertNull(ManifestStatusBarStyle.AUTO.darkStatusBarIcons())
    }

    @Test
    fun orientationIsRequestedWhileShownAndRestoredOnLeave() {
        var ui by mutableStateOf<ManifestUi?>(ManifestUi(orientation = ManifestOrientation.PORTRAIT))
        compose.setContent { ui?.let { ApplyRuntimeManifestUi(it) } }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, compose.activity.requestedOrientation)

        ui = ManifestUi(orientation = ManifestOrientation.LANDSCAPE)
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, compose.activity.requestedOrientation)

        ui = null
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
    }

    @Test
    fun unspecifiedOrientationLeavesTheHostAlone() {
        compose.setContent { ApplyRuntimeManifestUi(ManifestUi(orientation = ManifestOrientation.UNSPECIFIED)) }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
    }
}
