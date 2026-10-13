package io.toolbox.host

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.toolbox.host.ui.HostTestTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Drives the real app (no test content) into a secondary page and checks its pixels, not just
 * its semantics: an alpha-0 or covered page still "exists" and "is displayed" for the semantics
 * tree, so only a capture catches a blank destination. Runs in every light/dark mode.
 */
@RunWith(AndroidJUnit4::class)
class SecondaryPageVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun secondaryPageIsVisibleAfterPushInEveryModeAndAfterReturning() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(HostTestTags.BottomSettings).fetchSemanticsNodes().isNotEmpty()
        }
        openAppearance()
        assertPainted("明暗模式", "Appearance page after push (initial look)")

        // Switch the light/dark mode from inside the page, leave and come back: every mode must
        // render. (There is a single iOS look now, so mode is the appearance change left.)
        for (look in listOf("深色", "浅色", "跟随系统")) {
            compose.onNodeWithText(look).performClick()
            compose.waitForIdle()
            assertPainted("明暗模式", "Appearance page after switching to $look")
            pressBack()
            compose.onNodeWithTag(HostTestTags.SettingsAppearance).assertExists()
            assertPainted("外观", "Settings after returning from Appearance ($look)")
            openAppearance()
            assertPainted("明暗模式", "Appearance page after re-entering ($look)")
        }
        pressBack()
    }

    private fun openAppearance() {
        compose.onNodeWithTag(HostTestTags.BottomSettings).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(HostTestTags.SettingsAppearance).performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("明暗模式").fetchSemanticsNode() }.isSuccess
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** The node's on-screen pixels must contain glyph strokes, not just a flat background. */
    private fun assertPainted(text: String, what: String) {
        val bitmap = compose.onNodeWithText(text).captureToImage().asAndroidBitmap()
        val reference = bitmap.getPixel(0, 0)
        fun luminance(color: Int): Float {
            val r = (color shr 16 and 0xFF) / 255f
            val g = (color shr 8 and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            return 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        val base = luminance(reference)
        var strokes = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (abs(luminance(bitmap.getPixel(x, y)) - base) > 0.25f) strokes++
        }
        assertTrue("$what is blank: \"$text\" has no visible pixels ($strokes)", strokes >= 12)
    }
}
