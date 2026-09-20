package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportOutcome
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.ui.ToolManagerContent
import io.toolbox.tool.packagekit.lifecycle.PackageImportPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportFeedbackLifetimeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val importState = mutableStateOf(ImportUiState())
    private val dismissed = mutableListOf<ImportUiState>()
    private val expired = mutableListOf<Long>()
    private val accessibility = RecordingAccessibilityManager { compose.mainClock.currentTime }

    @After fun restoreClock() { compose.mainClock.autoAdvance = true }

    @Test
    fun successUsesTheAccessibilityRecommendedLifetime() {
        accessibility.recommendedMillis = 9_000
        renderFrozen()
        val success = success(1)
        show(success)
        val request = compose.runOnIdle { accessibility.requests.single() }
        assertEquals(3_000L, request.originalMillis)
        assertTrue(request.containsIcons)
        assertTrue(request.containsText)
        assertFalse(request.containsControls)

        advanceTo(request.startedAt + 3_100)
        assertStillShowing(success)
        advanceTo(request.startedAt + accessibility.recommendedMillis - 64)
        assertStillShowing(success)
        advanceTo(request.startedAt + accessibility.recommendedMillis + 64)
        compose.runOnIdle {
            assertEquals(listOf(success.feedbackId), expired)
            assertTrue("Automatic expiry must use the identity-aware callback", dismissed.isEmpty())
        }
        compose.onNodeWithText(requireNotNull(success.message)).assertDoesNotExist()
    }

    @Test
    fun anIdenticalNewSuccessGetsItsOwnLifetime() {
        renderFrozen()
        val first = success(1)
        show(first)
        val firstStart = compose.runOnIdle { accessibility.requests.single().startedAt }
        advanceTo(firstStart + 2_000)
        val replacement = first.copy(feedbackId = 2)
        show(replacement)
        val replacementStart = compose.runOnIdle {
            assertEquals(2, accessibility.requests.size)
            accessibility.requests.last().startedAt
        }

        advanceTo(firstStart + accessibility.recommendedMillis + 64)
        assertStillShowing(replacement)
        advanceTo(replacementStart + accessibility.recommendedMillis + 64)
        compose.runOnIdle {
            assertEquals(listOf(replacement.feedbackId), expired)
            assertTrue(dismissed.isEmpty())
        }
    }

    @Test
    fun workingCancellationAndFailureCancelAnOlderSuccessAndErrorsStayUntilDismissed() {
        renderFrozen()
        val replacements = listOf(
            ImportUiState(working = true, importPhase = PackageImportPhase.IMPORTING),
            ImportUiState(message = "已取消安装", outcome = ImportOutcome.Cancelled, feedbackId = 20),
            ImportUiState(message = "安装失败，请重新选择工具包。", outcome = ImportOutcome.Failure, feedbackId = 30),
        )
        replacements.forEachIndexed { index, replacement ->
            show(success(index.toLong() + 1))
            val startedAt = compose.runOnIdle { accessibility.requests.last().startedAt }
            advanceTo(startedAt + 1_000)
            show(replacement)
            advanceTo(startedAt + accessibility.recommendedMillis + 64)
            assertStillShowing(replacement)
        }

        val failure = replacements.last()
        compose.mainClock.advanceTimeBy(accessibility.recommendedMillis * 2)
        assertStillShowing(failure)
        compose.onNodeWithContentDescription("关闭提示").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(failure), dismissed) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(requireNotNull(failure.message)).assertDoesNotExist()
    }

    private fun renderFrozen() {
        compose.activity.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                ToolBoxTheme(reduceTransparency = true) {
                    ToolManagerContent(
                        state = CatalogUiState(isLoaded = true),
                        importState = importState.value,
                        listState = rememberLazyListState(),
                        contentPadding = PaddingValues(16.dp),
                        onAction = {}, onImport = {}, onInstallExamples = {}, onOpenDetails = {},
                        onDismissImport = {
                            dismissed += importState.value
                            importState.value = ImportUiState()
                        },
                        onExpireImportSuccess = { expectedFeedbackId ->
                            expired += expectedFeedbackId
                            importState.value = ImportUiState()
                        },
                        home = true,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun show(state: ImportUiState) {
        compose.runOnIdle { importState.value = state }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun advanceTo(timeMillis: Long) {
        val remaining = timeMillis - compose.mainClock.currentTime
        check(remaining >= 0) { "The requested deadline must still be ahead of the test clock" }
        compose.mainClock.advanceTimeBy(remaining, ignoreFrameDuration = true)
        compose.waitForIdle()
    }

    private fun assertStillShowing(expected: ImportUiState) {
        compose.runOnIdle {
            assertEquals(expected, importState.value)
            assertTrue("An earlier success must not dismiss the current feedback", dismissed.isEmpty())
            assertTrue("An earlier success timer must be canceled when a new state is shown", expired.isEmpty())
        }
        compose.onNodeWithText(if (expected.working) expected.progressMessage else requireNotNull(expected.message))
            .assertIsDisplayed()
    }

    private fun success(id: Long) = ImportUiState(message = "测试工具 已安装", outcome = ImportOutcome.Success, feedbackId = id)

    private data class TimeoutRequest(
        val startedAt: Long,
        val originalMillis: Long,
        val containsIcons: Boolean,
        val containsText: Boolean,
        val containsControls: Boolean,
    )

    private class RecordingAccessibilityManager(private val now: () -> Long) : AccessibilityManager {
        var recommendedMillis = 6_000L
        val requests = mutableListOf<TimeoutRequest>()

        override fun calculateRecommendedTimeoutMillis(
            originalTimeoutMillis: Long,
            containsIcons: Boolean,
            containsText: Boolean,
            containsControls: Boolean,
        ): Long {
            requests += TimeoutRequest(now(), originalTimeoutMillis, containsIcons, containsText, containsControls)
            return recommendedMillis
        }
    }
}
