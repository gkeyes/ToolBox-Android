package io.toolbox.host

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
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
        composeRule.onNodeWithText("我的工具箱").assertIsDisplayed()
        composeRule.onNodeWithText("0 个已安装工具").assertIsDisplayed()
        composeRule.onNodeWithTag(HostTestTags.CatalogEmptyState).assertIsDisplayed()
        composeRule.onAllNodesWithText("仓位计算器").assertCountEquals(0)

        composeRule.onNodeWithTag(HostTestTags.BottomTools).performClick()
        composeRule.onNodeWithText("工具管理").assertIsDisplayed()
        composeRule.onNodeWithTag(HostTestTags.CatalogEmptyState).assertIsDisplayed()

        composeRule.onNodeWithTag(HostTestTags.ImportFab).performClick()
        composeRule.onNodeWithText("导入工具").assertIsDisplayed()
        composeRule.onNodeWithText("选择 .tbx 工具包").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithTag(HostTestTags.BottomSettings).performClick()
        composeRule.onNodeWithText("主题").assertIsDisplayed()
        composeRule.onNodeWithText("审计日志保留").assertIsDisplayed()
        composeRule.onNodeWithText("严格策略固定").assertIsDisplayed()
    }
}
