package io.toolbox.host.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.runtime.FakeForegroundSessions
import io.toolbox.host.runtime.RuntimeUiState
import io.toolbox.host.runtime.RuntimeViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Composes the navigator with the runtime layer ToolBoxNavigation draws, around a real
 * RuntimeViewModel on fake sessions, so a wrong Back, presentation or reopen value fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigatorRuntimeLayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val stack = mutableStateListOf<NavKey>()
    // A failed preparation is presented at once; the layer needs no WebView for it.
    private val sessions = FakeForegroundSessions(RuntimeUiState.Error("TEST", "test"))
    private var navigator: RuntimeNavigator? = null
    private var createdModel: RuntimeViewModel? = null
    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RuntimeViewModel(A.toolId, sessions).also { createdModel = it } as T
    }

    private fun nav() = checkNotNull(navigator)
    private fun current() = nav().transition

    /** Opens A and lets its layer present it. */
    private fun openPresented() {
        compose.setContent {
            ToolBoxTheme {
                val current = rememberRuntimeNavigator(stack, stack.lastOrNull() as? RuntimeRoute)
                navigator = current
                NavigatorRuntimeLayer(current, viewModelFactory = { factory }, toolName = null)
            }
        }
        compose.runOnIdle { nav().navigate(A) }
        compose.waitForIdle()
        compose.runOnIdle { current().entryCoverMeasured = true }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** The second Back confirms the exit (both land at the same paused clock time). */
    private fun confirmExit() {
        pressBack()
        pressBack()
    }

    @Test
    fun theLayerPresentsTheToolAndBackLeavesThroughTheNavigator() {
        openPresented()
        assertTrue(current().runtimeLayerEnabled)
        // The layer's presentation hid the entry cover.
        assertTrue(current().runtimePresentationReady)
        assertFalse(current().entryCoverVisible)

        confirmExit()
        assertTrue(sessions.calls.contains("prepare"))
        assertTrue(stack.isEmpty())
    }

    @Test
    fun aReopenDuringTheExitSaveReachesTheRuntimeAndBackWorksAgain() {
        openPresented()
        val save = CompletableDeferred<Boolean>()
        sessions.save = save
        confirmExit()
        assertEquals("prepare", sessions.calls.last())

        compose.runOnIdle { nav().navigate(A) }
        compose.waitForIdle()
        // The runtime cancelled the close and handed the reopen back.
        assertEquals("cancelClose", sessions.calls.last())
        assertFalse(current().reopenRequested)
        compose.runOnIdle { save.complete(true) }
        compose.waitForIdle()
        assertEquals(listOf<NavKey>(A), stack.toList())

        sessions.save = CompletableDeferred(true)
        confirmExit()
        assertTrue(stack.isEmpty())
    }

    @Test
    fun aReopenAfterDiscardingRebuildsTheRuntime() {
        openPresented()
        sessions.save = CompletableDeferred(false)
        confirmExit()
        assertEquals("prepare", sessions.calls.last())

        // 「放弃并关闭」 starts the return; the tool is opened again before it ends.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { viewModel().discardAndClose() }
        assertTrue(current().runtimeLeaving)
        compose.runOnIdle { nav().navigate(A) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals(listOf<NavKey>(A), stack.toList())
        assertEquals(2, sessions.calls.count { it == "open" })
    }

    private fun viewModel(): RuntimeViewModel = checkNotNull(createdModel)

    private companion object {
        val A = RuntimeRoute("a")
    }
}
