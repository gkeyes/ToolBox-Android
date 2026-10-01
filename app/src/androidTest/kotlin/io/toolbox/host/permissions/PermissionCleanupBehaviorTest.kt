package io.toolbox.host.permissions

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.ToolVersion
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.host.MainActivity
import io.toolbox.host.ui.HostTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PermissionCleanupBehaviorTest(private val style: ToolBoxThemeStyle) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun secureStorageTouchShowsWipeConfirmationBeforeAnyMutation() {
        val capability = "storage.secure"
        val rowTag = HostTestTags.PermissionRowPrefix + capability
        val dialogTitle = "关闭并清除安全存储？"
        val state = mutableStateOf(PermissionCenterUiState(
            toolName = "权限测试工具",
            loadState = PermissionLoadState.Ready,
            items = listOf(PermissionItem(capability, "安全存储", "保存此工具的安全数据", true, emptyList())),
        ))
        val toggles = mutableListOf<Pair<String, Boolean>>()
        compose.activity.setContent {
            ToolBoxTheme(style = style, reduceTransparency = true) {
                PermissionCenterContent(
                    state = state.value,
                    onBack = {},
                    onOpenSystemSettings = {},
                    onSetEnabled = { key, enabled -> toggles += key to enabled },
                )
            }
        }
        val row = compose.onNodeWithTag(rowTag).performScrollTo().assertIsOn().assertIsEnabled()
        fun touchCheckedThumb() {
            val rowBounds = row.fetchSemanticsNode().boundsInRoot
            val switchBounds = compose.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
                    hasAnyAncestor(hasTestTag(rowTag)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().minBy { it.boundsInRoot.width }.boundsInRoot
            assertTrue("Expected the switch track inside the permission row", switchBounds.width < rowBounds.width)
            row.performTouchInput {
                click(Offset(
                    switchBounds.left + switchBounds.width * 0.72f - rowBounds.left,
                    switchBounds.center.y - rowBounds.top,
                ))
            }
        }
        // Touch the title area, exercising the production row's pointer handler.
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        row.performTouchInput { click(Offset(rowBounds.width * 0.25f, rowBounds.height / 2f)) }
        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
        compose.runOnIdle { assertTrue(toggles.isEmpty()) }
        compose.onNodeWithText("取消").performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithText(dialogTitle).assertDoesNotExist()
        row.assertIsOn()
        compose.runOnIdle { assertTrue(toggles.isEmpty()) }

        // Touch the checked switch thumb itself, not a semantics click on the row.
        touchCheckedThumb()
        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
        compose.runOnIdle { assertTrue(toggles.isEmpty()) }
        compose.onNodeWithText("关闭并清除").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(capability to false), toggles)
            state.value = state.value.copy(busyCapabilities = setOf(capability))
        }
        compose.waitForIdle()
        compose.onNodeWithText(dialogTitle).assertDoesNotExist()
        row.assertIsOn().assertIsNotEnabled()
        touchCheckedThumb()
        compose.onNodeWithText(dialogTitle).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(capability to false), toggles) }
    }

    @Test fun cleanupRetryHasItsOwnActionAndNeverTurnsTheSwitchOn() {
        val capability = "storage.secure"
        val version = ToolVersion("io.toolbox.retry", 1, "1.0.0", BundleLocator("tools/retry/first"), 32, "hash", 1L)
        val retry = PermissionCleanupRetry(version, PermissionGrant(version.toolId, capability, false, 2L))
        val state = mutableStateOf(PermissionCenterUiState(
            toolName = "权限测试工具",
            loadState = PermissionLoadState.Ready,
            items = listOf(PermissionItem(capability, "安全存储", "保存此工具的安全数据", false, emptyList())),
            cleanupRetries = mapOf(capability to retry),
        ))
        val retries = mutableListOf<String>()
        val toggles = mutableListOf<Pair<String, Boolean>>()
        compose.activity.setContent {
            ToolBoxTheme(style = style, reduceTransparency = true) {
                PermissionCenterContent(
                    state = state.value, onBack = {}, onOpenSystemSettings = {},
                    onSetEnabled = { key, enabled -> toggles += key to enabled },
                    onRetryCleanup = { key ->
                        retries += key
                        state.value = state.value.copy(busyCapabilities = setOf(key))
                    },
                )
            }
        }
        val permission = compose.onNodeWithTag(HostTestTags.PermissionRowPrefix + capability)
        val action = compose.onNodeWithTag("permission_cleanup_retry:$capability")
        permission.assertIsOff().assertIsEnabled()
        compose.onNodeWithText("权限已关闭，清除尚未完成。").assertIsDisplayed()
        action.performScrollTo().performClick()
        permission.assertIsOff().assertIsNotEnabled()
        action.assertIsNotEnabled()
        compose.onNodeWithText("正在清除…").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(capability), retries)
            assertTrue(toggles.isEmpty())
            state.value = state.value.copy(busyCapabilities = emptySet())
        }
        action.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(capability, capability), retries)
            state.value = state.value.copy(busyCapabilities = emptySet(), cleanupRetries = emptyMap())
        }
        action.assertDoesNotExist()
        permission.assertIsOff().assertIsEnabled()
        compose.runOnIdle { assertTrue(toggles.isEmpty()) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = ToolBoxThemeStyle.entries.map { arrayOf<Any>(it) }
    }
}
