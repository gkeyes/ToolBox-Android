package io.toolbox.host.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class VisibleUiStateTest {
    @get:Rule
    val compose = createComposeRule()

    private data class UiState(val revision: Int, val confirmation: Boolean)

    @Test
    fun coveredConfirmationStaysLiveThenPausesAndResumesWithLatestState() {
        val source = MutableStateFlow(UiState(1, false))
        val visible = mutableStateOf(true)
        compose.setContent {
            val state by source.collectAsStateWhileVisible(visible.value) { it.confirmation }
            BasicText("${state.revision}|${state.confirmation}")
        }
        compose.onNodeWithText("1|false").assertExists()
        compose.runOnIdle { source.value = UiState(2, true) }
        compose.onNodeWithText("2|true").assertExists()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { source.value = UiState(3, true) }
        compose.onNodeWithText("3|true").assertExists()
        compose.runOnIdle { source.value = UiState(4, false) }
        compose.onNodeWithText("4|false").assertExists()
        compose.runOnIdle { source.value = UiState(5, false) }
        compose.onNodeWithText("4|false").assertExists()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("5|false").assertExists()
    }

    @Test
    fun confirmationOpenedWhileCoveredActivatesTheSubscription() {
        val source = MutableStateFlow(UiState(1, false))
        compose.setContent {
            val state by source.collectAsStateWhileVisible(false) { it.confirmation }
            BasicText("${state.revision}|${state.confirmation}")
        }
        compose.onNodeWithText("1|false").assertExists()
        compose.runOnIdle { source.value = UiState(2, false) }
        compose.onNodeWithText("1|false").assertExists()
        compose.runOnIdle { source.value = UiState(3, true) }
        compose.onNodeWithText("3|true").assertExists()
        compose.runOnIdle { source.value = UiState(4, false) }
        compose.onNodeWithText("4|false").assertExists()
    }
}
