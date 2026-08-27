package io.toolbox.host

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.ui.HomeScreen
import io.toolbox.host.ui.HostCatalogScreenModel
import io.toolbox.host.ui.HostTestTags
import io.toolbox.host.ui.ToolCardModel
import io.toolbox.host.ui.UiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HostAdaptiveScrollTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun longCatalogScrollsToTheLastStableKeyAndKeepsActionsTouchSafe() {
        val lastTool = ToolCardModel(
            toolId = "tool-80",
            title = "工具 80",
            metadata = "测试工具",
            symbol = "80",
        )
        val tools = (1..80).map { index ->
            if (index == 80) {
                lastTool
            } else {
                ToolCardModel(
                    toolId = "tool-$index",
                    title = "工具 $index",
                    metadata = "测试工具",
                    symbol = index.toString(),
                )
            }
        }

        composeRule.activity.setContent {
            ToolBoxTheme {
                HomeScreen(
                    model = HostCatalogScreenModel(UiState.Content(tools)),
                    onDestination = {},
                    onImport = {},
                    onLaunchTool = {},
                )
            }
        }

        composeRule.onNodeWithTag(HostTestTags.CatalogList)
            .performScrollToNode(hasTestTag(HostTestTags.ToolCardPrefix + lastTool.toolId))
        composeRule.onNodeWithTag(HostTestTags.ToolCardPrefix + lastTool.toolId).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("导入 .tbx 工具包").assertIsDisplayed()

        val minimumSizePx = with(composeRule.density) { 48.dp.toPx() }
        listOf(HostTestTags.ToolCardPrefix + lastTool.toolId, HostTestTags.ImportFab).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag width must be at least 48dp", bounds.width >= minimumSizePx)
            assertTrue("$tag height must be at least 48dp", bounds.height >= minimumSizePx)
        }
    }

    @Test
    fun freshInstallRemainsReachableAtTwoHundredPercentFontScale() {
        composeRule.activity.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 2f),
            ) {
                ToolBoxTheme {
                    HomeScreen(
                        model = HostCatalogScreenModel(UiState.Empty),
                        onDestination = {},
                        onImport = {},
                        onLaunchTool = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(HostTestTags.CatalogList)
            .performScrollToNode(hasTestTag(HostTestTags.CatalogEmptyState))
        composeRule.onNodeWithTag(HostTestTags.CatalogEmptyState).assertIsDisplayed()
        composeRule.onNodeWithText("导入 .tbx 工具包").assertIsDisplayed()

        val minimumTouchTargetPx = with(composeRule.density) { 48.dp.toPx() }
        val minimumNavigationHeightPx = with(composeRule.density) { 104.dp.toPx() }
        composeRule.onNodeWithTag(HostTestTags.BottomNavigationContainer).assertIsDisplayed()
        assertTrue(
            "2× font-scale navigation container must reserve at least 104dp",
            composeRule.onNodeWithTag(HostTestTags.BottomNavigationContainer).fetchSemanticsNode().boundsInRoot.height >= minimumNavigationHeightPx,
        )
        listOf(
            HostTestTags.BottomHome to "首页",
            HostTestTags.BottomTools to "工具",
            HostTestTags.BottomSettings to "设置",
        ).forEach { (tag, label) ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$label width must be at least 48dp", bounds.width >= minimumTouchTargetPx)
            assertTrue("$label height must be at least 48dp", bounds.height >= minimumTouchTargetPx)
        }
    }
}
