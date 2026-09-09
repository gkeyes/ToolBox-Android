package io.toolbox.host

import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
        composeRule.onNodeWithText("返回 ToolBox？").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, exits) }
        composeRule.onNodeWithText("继续使用").performClick()
        composeRule.onNodeWithText("返回 ToolBox？").assertDoesNotExist()

        pressBack()
        composeRule.onNodeWithText("返回 ToolBox？").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithText("返回 ToolBox？").assertDoesNotExist()
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
        composeRule.onNodeWithText("返回 ToolBox？").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, exits) }
    }
}
