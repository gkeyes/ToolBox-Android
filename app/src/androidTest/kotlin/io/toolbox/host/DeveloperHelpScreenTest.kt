package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.help.DeveloperHelpContent
import io.toolbox.host.help.DeveloperHelpTestTags
import io.toolbox.host.help.parseHelpDocument
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeveloperHelpScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun topicsCollapseSearchAndCopyWithoutLosingTheirContentAtLargeText() {
        val document = parseHelpDocument(
            """
                # 开发帮助
                离线说明。

                ## 入门
                从保存到通知。

                ### 保存
                保存内容说明。

                ### 定时器
                后台定时更新说明。

                ```js
                await ToolBox.background.setTimer("clock", 10000);
                ```

                ## 网络
                HTTPS 请求。

                ### 请求
                network.request 返回状态与正文。
            """.trimIndent(),
        )
        val copied = mutableListOf<String>()
        var installs = 0
        composeRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ToolBoxTheme {
                    DeveloperHelpContent(
                        document = document,
                        onInstallExamples = { installs += 1 },
                        onCopy = { copied += it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.onNodeWithText("保存内容说明。").assertDoesNotExist()
        reveal(hasTestTag(DeveloperHelpTestTags.InstallExamples)).performClick()
        composeRule.runOnIdle { assertEquals(1, installs) }
        reveal(hasTestTag(DeveloperHelpTestTags.chapter("入门"))).performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        reveal(hasTestTag(DeveloperHelpTestTags.article("入门/保存"))).performClick()
        reveal(hasText("保存内容说明。")).assertIsDisplayed()
        reveal(hasTestTag(DeveloperHelpTestTags.article("入门/定时器"))).performClick()
        composeRule.onNodeWithText("保存内容说明。").assertDoesNotExist()
        reveal(hasContentDescription("复制定时器中的js")).performClick()
        composeRule.runOnIdle {
            assertEquals("await ToolBox.background.setTimer(\"clock\", 10000);", copied.single())
        }
        reveal(hasTestTag(DeveloperHelpTestTags.chapter("网络"))).performClick()
        composeRule.onNodeWithText("后台定时更新说明。").assertDoesNotExist()
        reveal(hasTestTag(DeveloperHelpTestTags.Search)).performTextInput("setTimer")
        reveal(hasTestTag(DeveloperHelpTestTags.article("入门/定时器"))).performClick()
        reveal(hasText("后台定时更新说明。")).assertIsDisplayed()
        reveal(hasTestTag(DeveloperHelpTestTags.CopyAll)).performClick()
        composeRule.runOnIdle { assertEquals(document.source, copied.last()) }
    }

    private fun reveal(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        composeRule.onNodeWithTag(DeveloperHelpTestTags.List).performScrollToNode(matcher)
        return composeRule.onNode(matcher)
    }
}
