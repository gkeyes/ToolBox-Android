package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.catalog.RunningToolsViewModel
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import io.toolbox.host.ui.CatalogRunningTools
import io.toolbox.host.ui.ToolManagerScreen
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CatalogRunningToolsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val first = session("watcher", "GitHub 构建守望", 1)
    private val second = session("lab", "通知实验室", 2)
    private val source = MutableStateFlow<List<RuntimeBackgroundSessionUi>>(emptyList())
    private val stopped = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private lateinit var viewModel: RunningToolsViewModel

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
    }

    @Test
    fun runningGroupAppearsBeforeRecentAndStopsOnlyTheConfirmedSession() {
        showHome()
        composeRule.onNodeWithTag("catalog-running-tools").assertDoesNotExist()
        composeRule.runOnIdle { source.value = listOf(first, second) }

        val runningBounds = composeRule.onNodeWithTag("catalog-running-tools").fetchSemanticsNode().boundsInRoot
        val recentBounds = composeRule.onNodeWithText("最近使用").fetchSemanticsNode().boundsInRoot
        assertTrue(runningBounds.bottom <= recentBounds.top)

        composeRule.onNodeWithContentDescription("打开${first.toolName}").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(first.toolId), opened)
            assertTrue(stopped.isEmpty())
        }
        composeRule.onNodeWithContentDescription("停止${first.toolName}后台运行").performClick()
        composeRule.onNodeWithText("停止后台运行？").assertIsDisplayed()
        composeRule.onNodeWithText("继续运行").performClick()
        composeRule.runOnIdle { assertTrue(stopped.isEmpty()) }
        composeRule.onNodeWithTag("catalog-running-${first.sessionId}").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("停止${first.toolName}后台运行").performClick()
        composeRule.onNodeWithText("停止运行").performClick()
        composeRule.onNodeWithTag("catalog-running-${first.sessionId}").assertDoesNotExist()
        composeRule.onNodeWithTag("catalog-running-${second.sessionId}").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf(first.sessionId), stopped) }

        composeRule.onNodeWithContentDescription("停止${second.toolName}后台运行").performClick()
        composeRule.onNodeWithText("停止运行").performClick()
        composeRule.onNodeWithTag("catalog-running-tools").assertDoesNotExist()
        composeRule.onNodeWithText("最近使用").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf(first.sessionId, second.sessionId), stopped) }
    }

    @Test
    fun runningControlsRemainSeparateTouchTargetsAtDoubleFontScale() {
        source.value = listOf(first, second)
        showHome(fontScale = 2f)

        composeRule.onNodeWithContentDescription("打开${first.toolName}")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("停止${first.toolName}后台运行")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText("继续运行").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertTrue(opened.isEmpty())
            assertTrue(stopped.isEmpty())
        }
    }

    private fun showHome(fontScale: Float = 1f) {
        viewModel = RunningToolsViewModel(source) { id ->
            stopped += id
            source.value = source.value.filterNot { it.sessionId == id }
            true
        }
        val tools = listOf(first, second).map {
            CatalogTool(it.toolId, it.toolName, 1, "1.0.0", 1_024L, lastOpenedAt = it.startedAt)
        }
        val catalog = CatalogUiState(isLoaded = true, tools = tools, visibleTools = tools, recentTools = tools)
        composeRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ToolBoxTheme {
                    ToolManagerScreen(
                        state = catalog,
                        importState = ImportUiState(),
                        listState = rememberLazyListState(),
                        onAction = {},
                        onDestination = {},
                        onImport = {},
                        onInstallExamples = {},
                        onDismissImport = {},
                        onOpenDetails = {},
                        runningTools = { CatalogRunningTools(viewModel, tools, onOpen = { opened += it }) },
                    )
                }
            }
        }
    }

    private fun session(id: String, name: String, number: Int) = RuntimeBackgroundSessionUi(
        sessionId = id,
        toolId = "io.toolbox.$id",
        toolName = name,
        startedAt = number.toLong(),
        notificationId = number,
    )
}
