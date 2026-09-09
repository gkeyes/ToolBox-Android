package io.toolbox.host

import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.espresso.Espresso.pressBack
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.ui.RuntimeExitConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class RuntimeExitConfirmationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun backRequiresConfirmationAndCancellationKeepsRuntimeAttached() {
        var exits = 0
        var creations = 0
        var releases = 0
        var currentView: WebView? = null
        composeRule.activity.setContent {
            ToolBoxTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { context -> WebView(context).also {
                            creations += 1
                            currentView = it
                        } },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { releases += 1; it.destroy() },
                    )
                    RuntimeExitConfirmation(onConfirm = { exits += 1 })
                }
            }
        }
        composeRule.waitForIdle()
        val originalView = currentView

        pressBack()
        waitForDialog(visible = true)
        composeRule.runOnIdle { assertEquals(0, exits) }
        composeRule.onNodeWithText("继续使用").performClick()
        waitForDialog(visible = false)

        pressBack()
        waitForDialog(visible = true)
        pressBack()
        waitForDialog(visible = false)
        composeRule.runOnIdle {
            assertEquals(0, exits)
            assertEquals(1, creations)
            assertEquals(0, releases)
            assertSame(originalView, currentView)
        }

        pressBack()
        composeRule.onNodeWithText("返回 ToolBox").performClick()
        composeRule.runOnIdle { assertEquals(1, exits) }
        // Consume further Back events during the existing return transition.
        pressBack()
        waitForDialog(visible = false)
        composeRule.runOnIdle { assertEquals(1, exits) }
    }

    private fun waitForDialog(visible: Boolean) {
        val title = composeRule.onNodeWithText("返回 ToolBox？")
        composeRule.waitUntil(timeoutMillis = 10_000) { title.isDisplayed() == visible }
        if (visible) title.assertIsDisplayed() else title.assertDoesNotExist()
    }
}
