package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.ui.ToolManagerContent
import io.toolbox.tool.packagekit.lifecycle.PackageImportPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ToolManagerImportConfirmationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activeImportHasOneCancelActionAndCommitHasNone() {
        val phase = mutableStateOf(PackageImportPhase.IMPORTING)
        var cancellations = 0
        composeRule.activity.setContent {
            ToolBoxTheme {
                ToolManagerContent(
                    state = CatalogUiState(isLoaded = true),
                    importState = ImportUiState(working = true, importPhase = phase.value),
                    listState = rememberLazyListState(),
                    onAction = {},
                    contentPadding = PaddingValues(16.dp),
                    onImport = {},
                    onInstallExamples = {},
                    onDismissImport = {},
                    onOpenDetails = {},
                    onCancelActiveImport = {
                        cancellations++
                        phase.value = PackageImportPhase.CANCELLING
                    },
                )
            }
        }
        composeRule.onNodeWithText("取消").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("正在取消安装…").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, cancellations)
            phase.value = PackageImportPhase.COMMITTING
        }
        composeRule.onNodeWithText("正在完成安装…").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertDoesNotExist()
    }

    @Test
    fun versionReplacementConfirmationRendersAndDispatchesOnlyTheSelectedAction() {
        val actions = mutableListOf<String>()
        val confirmation = HostImportConfirmation(
            id = "pending-1",
            toolId = "io.toolbox.versionfixture",
            toolName = "版本确认测试",
            installedVersionName = "2.0.0",
            installedVersionCode = 2,
            incomingVersionName = "2.0.1",
            incomingVersionCode = 2,
            kind = HostImportConfirmationKind.SAME_VERSION,
        )
        composeRule.activity.setContent {
            ToolBoxTheme {
                ToolManagerContent(
                    state = CatalogUiState(isLoaded = true),
                    importState = ImportUiState(confirmation = confirmation),
                    listState = rememberLazyListState(),
                    onAction = {},
                    contentPadding = PaddingValues(16.dp),
                    onImport = {},
                    onInstallExamples = {},
                    onDismissImport = {},
                    onConfirmImport = { actions += "confirm" },
                    onCancelImport = { actions += "cancel" },
                    onOpenDetails = {},
                )
            }
        }

        composeRule.onNodeWithText("覆盖安装同一版本？").assertIsDisplayed()
        composeRule.onNodeWithText("仍要覆盖").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(listOf("confirm"), actions) }

        composeRule.onNodeWithText("取消").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(listOf("confirm", "cancel"), actions) }
    }
}
