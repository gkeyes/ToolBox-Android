package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.SignatureState
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.ui.HomeScreen
import io.toolbox.host.ui.HostTestTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HostAdaptiveScrollTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fixtureLongCatalogScrollsToTheLastStableKeyAndKeepsActionsTouchSafe() {
        val tools = (1..80).map(::catalogTool)
        val lastTool = tools.last()

        composeRule.activity.setContent {
            ToolBoxTheme {
                HomeScreen(
                    state = CatalogUiState(isLoaded = true, tools = tools),
                    onAction = {},
                    onDestination = {},
                    onImport = {},
                    onOpenDetails = {},
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
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 2f)) {
                ToolBoxTheme {
                    HomeScreen(
                        state = CatalogUiState(isLoaded = true),
                        onAction = {},
                        onDestination = {},
                        onImport = {},
                        onOpenDetails = {},
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

private fun catalogTool(index: Int) = CatalogTool(
    toolId = "tool-$index",
    name = "工具 $index",
    signatureState = SignatureState.VERIFIED_TRUSTED,
    activeVersionCode = 1,
    activeVersionName = "1.0.0",
    bundleBytes = 1024,
    launchState = LaunchState.STABLE,
    lastOpenedAt = null,
    categoryId = null,
    pinnedOrder = null,
)
