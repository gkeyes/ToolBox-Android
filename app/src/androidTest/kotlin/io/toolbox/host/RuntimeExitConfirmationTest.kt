package io.toolbox.host

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.ui.RuntimeExitConfirmation
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.sameInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import androidx.test.espresso.action.ViewActions.pressBack as pressBackAction

class RuntimeExitConfirmationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backRequiresConfirmationAndCancellationKeepsRuntimeAttached() {
        var exits = 0
        var creations = 0
        var releases = 0
        var currentView: WebView? = null
        composeRule.setContent {
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

        pressBackInWindow()
        waitForDialog(visible = true)
        composeRule.runOnIdle { assertEquals(0, exits) }
        composeRule.onNodeWithText("继续使用").performClick()
        waitForDialog(visible = false)

        pressBackInWindow()
        waitForDialog(visible = true)
        pressBackInWindow(dialog = true)
        waitForDialog(visible = false)
        composeRule.runOnIdle {
            assertEquals(0, exits)
            assertEquals(1, creations)
            assertEquals(0, releases)
            assertSame(originalView, currentView)
        }

        pressBackInWindow()
        waitForDialog(visible = true)
        composeRule.onNodeWithText("返回 ToolBox").performClick()
        composeRule.runOnIdle { assertEquals(1, exits) }
        // Consume further Back events during the existing return transition.
        pressBackInWindow()
        waitForDialog(visible = false)
        composeRule.runOnIdle { assertEquals(1, exits) }
    }

    private fun pressBackInWindow(dialog: Boolean = false) {
        composeRule.waitForIdle()
        val activity = composeRule.activity
        val activityDecor = composeRule.runOnUiThread { activity.window.decorView }
        var target: View? = null
        // Compose idleness does not guarantee window focus. Resolve this fixture's
        // current window before sending Back, including its separate Dialog root.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.runOnUiThread {
                target = WindowInspector.getGlobalWindowViews().singleOrNull { view ->
                    view.isAttachedToWindow &&
                        view.isShown &&
                        view.hasWindowFocus() &&
                        !view.isLayoutRequested &&
                        view.context.activityOwner() === activity &&
                        (if (dialog) view !== activityDecor else view === activityDecor)
                }
                target != null
            }
        }
        val exactRoot = withDecorView(sameInstance(checkNotNull(target)))
        onView(isRoot())
            .inRoot(if (dialog) allOf(isDialog(), exactRoot) else exactRoot)
            .perform(pressBackAction())
        composeRule.waitForIdle()
    }

    private fun Context.activityOwner(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> if (baseContext === this) null else baseContext.activityOwner()
        else -> null
    }

    private fun waitForDialog(visible: Boolean) {
        val title = composeRule.onNodeWithText("返回 ToolBox？")
        composeRule.waitUntil(timeoutMillis = 10_000) { title.isDisplayed() == visible }
        if (visible) title.assertIsDisplayed() else title.assertDoesNotExist()
    }
}
