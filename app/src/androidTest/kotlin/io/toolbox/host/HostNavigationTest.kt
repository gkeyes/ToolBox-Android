package io.toolbox.host

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.toolbox.host.ui.HostTestTags
import org.junit.Rule
import org.junit.Test

class HostNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun hostNavigationShowsEveryPhaseOneStaticSurface() {
        composeRule.onNodeWithText("我的工具箱").assertIsDisplayed()

        composeRule.onNodeWithTag(HostTestTags.BottomTools).performClick()
        composeRule.onNodeWithText("工具管理").assertIsDisplayed()

        composeRule.onNodeWithTag(HostTestTags.ImportFab).performClick()
        composeRule.onNodeWithTag("import_review").assertIsDisplayed()
        composeRule.onNodeWithText("结构检查通过，未发现 Zip Slip 或危险文件").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithTag(HostTestTags.BottomSettings).performClick()
        composeRule.onNodeWithText("主题种子色").assertIsDisplayed()

        composeRule.onNodeWithTag(HostTestTags.PermissionCenter).performClick()
        composeRule.onNodeWithTag(HostTestTags.PermissionCenter).assertIsDisplayed()
        composeRule.onNodeWithText("按工具管理").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithTag(HostTestTags.BottomTools).performClick()
        composeRule.onNodeWithContentDescription("打开仓位计算器，可信").performClick()
        composeRule.onNodeWithTag(HostTestTags.RuntimeShell).assertIsDisplayed()
        composeRule.onNodeWithText("HTML 小工具内容区域").assertIsDisplayed()
    }
}
