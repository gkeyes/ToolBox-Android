package io.toolbox.host.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.host.MainActivity
import io.toolbox.host.runtime.RuntimeCloseUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeSaveDialogBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun miuixCloseChoicesAndCallbacks() = exercise(ToolBoxThemeStyle.Miuix)
    @Test fun liquidGlassCloseChoicesAndCallbacks() = exercise(ToolBoxThemeStyle.LiquidGlass)

    private fun exercise(style: ToolBoxThemeStyle) {
        val saving = mutableStateOf<RuntimeCloseUiState?>(RuntimeCloseUiState(reloading = false))
        var retries = 0
        var cancels = 0
        var discards = 0
        compose.activity.setContent {
            ToolBoxTheme(style = style, reduceTransparency = true) {
                saving.value?.let { state ->
                    RuntimeSaveDialog(state,
                        onRetry = { retries++; saving.value = null },
                        onCancel = { cancels++; saving.value = null },
                        onDiscard = { discards++; saving.value = null },
                    )
                }
            }
        }

        compose.onNodeWithText("正在保存").assertIsDisplayed()
        compose.onNodeWithText("取消退出").assertIsDisplayed()
        compose.onNodeWithText("继续等待").assertDoesNotExist()
        compose.onNodeWithText("放弃保存并退出").assertDoesNotExist()

        compose.runOnIdle { saving.value = RuntimeCloseUiState(reloading = false, slow = true) }
        compose.onNodeWithText("仍在保存").assertIsDisplayed()
        compose.onNodeWithText("继续等待").assertIsDisplayed().performClick()
        compose.onNodeWithText("正在等待保存完成").assertIsDisplayed()
        compose.onNodeWithText("继续等待").assertDoesNotExist()
        compose.onNodeWithText("放弃保存并退出").assertIsDisplayed()
        compose.onNodeWithText("取消退出").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, cancels) }

        compose.runOnIdle { saving.value = RuntimeCloseUiState(reloading = true, slow = true) }
        compose.onNodeWithText("继续等待").assertIsDisplayed()
        compose.onNodeWithText("取消重载").assertIsDisplayed()
        compose.onNodeWithText("放弃保存并重载").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, discards) }

        compose.runOnIdle { saving.value = RuntimeCloseUiState(reloading = false, error = "保存未完成") }
        compose.onNodeWithText("保存未完成").assertIsDisplayed()
        compose.onNodeWithText("页面已保留。").assertIsDisplayed()
        compose.onNodeWithText("继续等待").assertDoesNotExist()
        compose.onNodeWithText("重试保存").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, cancels)
            assertEquals(1, discards)
        }
    }
}
