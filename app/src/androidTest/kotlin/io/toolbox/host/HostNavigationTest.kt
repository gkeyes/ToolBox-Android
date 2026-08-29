package io.toolbox.host

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import io.toolbox.host.ui.HostTestTags
import org.junit.Rule
import org.junit.Test

class HostNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun freshProductionCatalogNavigatesToImportReviewAndSettingsWithoutPickerLaunch() {
        val emptyState = composeRule.onNodeWithTag(HostTestTags.CatalogEmptyState)
        composeRule.waitUntil(timeoutMillis = 10_000) { emptyState.isDisplayed() }
        emptyState.assertIsDisplayed()
        composeRule.onAllNodesWithText("仓位计算器").assertCountEquals(0)
        composeRule.onAllNodesWithText("本机目录").assertCountEquals(0)
        composeRule.onAllNodesWithText("搜索名称、ID 或分类").assertCountEquals(0)

        composeRule.onNodeWithTag(HostTestTags.BottomTools).performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        val toolsHeading = composeRule.onNode(hasText("工具") and isHeading())
        val toolsSearch = composeRule.onNodeWithText("搜索名称、ID 或分类")
        composeRule.waitUntil(timeoutMillis = 5_000) { toolsHeading.isDisplayed() }
        toolsHeading.assertIsDisplayed()
        toolsSearch.assertIsDisplayed()
        composeRule.onNodeWithText("已安装 · 0").assertIsDisplayed()

        val toolsScreen = hasTestTag(HostTestTags.PrimaryTools)
        composeRule.onNode(
            hasTestTag(HostTestTags.ImportFab) and hasAnyAncestor(toolsScreen),
        ).performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        val importHeading = composeRule.onNodeWithText("导入工具")
        composeRule.waitUntil(timeoutMillis = 5_000) { importHeading.isDisplayed() }
        importHeading.assertIsDisplayed()
        composeRule.onNodeWithText("选择 .tbx 工具包").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) { toolsHeading.isDisplayed() }
        composeRule.onNode(
            hasTestTag(HostTestTags.BottomSettings) and hasAnyAncestor(toolsScreen),
        ).performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        val themeSetting = composeRule.onNodeWithText("主题")
        composeRule.waitUntil(timeoutMillis = 5_000) { themeSetting.isDisplayed() }
        themeSetting.assertIsDisplayed()
        composeRule.onNodeWithText("审计日志保留").assertIsDisplayed()
        composeRule.onAllNodesWithText("严格策略固定").assertCountEquals(0)
        composeRule.onAllNodesWithText("工具配额与开发者工具").assertCountEquals(0)
    }
}
