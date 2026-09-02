package io.toolbox.host

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import io.toolbox.host.ui.HostTestTags
import org.junit.Rule
import org.junit.Test

class HostNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bundledExampleCanBeManagedFromInstallThroughDelete() {
        val emptyState = composeRule.onNodeWithTag(HostTestTags.CatalogEmptyState)
        composeRule.waitUntil(timeoutMillis = 10_000) { emptyState.isDisplayed() }
        emptyState.assertIsDisplayed()

        composeRule.onNodeWithText("安装四个范例").performClick()
        waitForVisibleText("后台任务演示", timeoutMillis = 20_000)

        composeRule.onNodeWithContentDescription("管理后台任务演示").performClick()
        waitForVisibleText("权限")
        composeRule.onNodeWithText("权限").performClick()
        waitForVisibleText("工具权限")

        val networkPermission = composeRule.onNodeWithTag(
            HostTestTags.PermissionRowPrefix + "network",
        )
        networkPermission.assertIsOff()
        networkPermission.performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            networkPermission.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On
        }
        networkPermission.assertIsOn()

        composeRule.onNodeWithContentDescription("返回").performClick()
        waitForVisibleText("打开工具")
        composeRule.onNodeWithText("打开工具").performClick()
        val runtimeShell = composeRule.onNodeWithTag(HostTestTags.RuntimeShell)
        composeRule.waitUntil(timeoutMillis = 10_000) { runtimeShell.isDisplayed() }
        runtimeShell.assertIsDisplayed()

        composeRule.onNodeWithContentDescription("返回").performClick()
        waitForVisibleText("后台任务")
        composeRule.onNodeWithText("后台任务").performClick()
        waitForVisibleText("没有后台任务")

        composeRule.onNodeWithContentDescription("返回").performClick()
        waitForVisibleText("打开工具")
        composeRule.onNodeWithContentDescription("更多操作").performClick()
        composeRule.onNodeWithContentDescription("从菜单删除后台任务演示").performClick()
        waitForVisibleText("确认删除")
        composeRule.onNodeWithText("确认删除").performClick()
        waitForVisibleText("该工具已不存在")

        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("后台任务演示").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithText("后台任务演示").assertCountEquals(0)
        composeRule.onNodeWithText("仓位计算器").assertIsDisplayed()
        composeRule.onNodeWithText("快速笔记").assertIsDisplayed()
        composeRule.onNodeWithText("通知实验室").assertIsDisplayed()
    }

    private fun waitForVisibleText(text: String, timeoutMillis: Long = 10_000) {
        val node = composeRule.onNodeWithText(text)
        composeRule.waitUntil(timeoutMillis) { node.isDisplayed() }
        node.assertIsDisplayed()
    }
}
