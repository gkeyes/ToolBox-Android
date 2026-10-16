package io.toolbox.host.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Composes [rememberRuntimeNavigator], the wiring ToolBoxNavigation uses, so dropping a navigate
 * branch, the leave guard, an entry effect or the interrupted-return pop fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RuntimeNavigatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val stack = mutableStateListOf<NavKey>()
    private var navigator: RuntimeNavigator? = null

    private fun composeNavigator() {
        compose.setContent { navigator = rememberRuntimeNavigator(stack, stack.lastOrNull() as? RuntimeRoute) }
        compose.waitForIdle()
    }

    private fun nav() = checkNotNull(navigator)
    private fun current() = nav().transition

    /** Opens [route] and lets it settle: cover in, runtime ready, cover gone. */
    private fun openSettled(route: RuntimeRoute) {
        compose.runOnIdle { nav().navigate(route) }
        compose.waitForIdle()
        compose.runOnIdle { current().entryCoverMeasured = true }
        compose.waitForIdle()
        compose.runOnIdle { current().runtimePresentationReady = true }
        compose.waitForIdle()
    }

    @Test
    fun navigatePushesTheToolAndTheEffectsHandItToTheRuntime() {
        composeNavigator()
        compose.runOnIdle { nav().navigate(A) }
        compose.waitForIdle()
        assertEquals(listOf<NavKey>(A), stack.toList())
        assertEquals(1, nav().generation)
        val a = current()
        assertEquals(A, a.route)
        assertFalse(a.runtimeLayerEnabled)

        compose.runOnIdle { a.entryCoverMeasured = true }
        compose.waitForIdle()
        assertEquals(0f, a.entryProgress.value)
        assertTrue(a.runtimeLayerEnabled)
        assertTrue(a.entryCoverVisible)

        compose.runOnIdle { a.runtimePresentationReady = true }
        compose.waitForIdle()
        assertFalse(a.entryCoverVisible)
    }

    @Test
    fun aSourceHeldUnderTheNextCoverDropsBelowOnceThatCoverIsIn() {
        composeNavigator()
        compose.runOnIdle { nav().navigate(A) }
        compose.waitForIdle()
        // Back while A's cover is still showing, then B opens over it.
        compose.runOnIdle {
            current().sourceAboveRuntime = true
            current().runtimeLeaving = true
            nav().navigate(B)
        }
        compose.waitForIdle()
        val b = current()
        assertEquals(B, b.route)
        assertTrue(b.sourceHeldUnderCover)
        assertTrue(b.sourceAboveRuntime)

        compose.runOnIdle { b.entryCoverMeasured = true }
        compose.waitForIdle()
        assertFalse(b.sourceAboveRuntime)
        assertTrue(b.runtimeLayerEnabled)
    }

    @Test
    fun leaveReturnsTheSourceThenPopsTheTool() {
        composeNavigator()
        openSettled(A)
        val a = current()

        compose.runOnIdle { nav().leave() }
        assertTrue(a.runtimeLeaving)
        compose.waitForIdle()
        assertTrue(stack.isEmpty())
        assertNull(current().route)
    }

    @Test
    fun reopeningTheReturningToolCancelsTheReturn() {
        composeNavigator()
        openSettled(A)
        val a = current()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav().leave() }
        compose.mainClock.advanceTimeBy(64)
        assertTrue(a.sourceAboveRuntime)

        compose.runOnIdle { nav().navigate(A) }
        assertFalse(a.runtimeLeaving)
        assertEquals(1, nav().reopenRevision)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf<NavKey>(A), stack.toList())
        assertSame(a, current())
        assertFalse(a.sourceAboveRuntime)
        assertEquals(1, nav().generation)
    }

    @Test
    fun reopeningTheToolOnTopCancelsTheExitAndBackStays() {
        composeNavigator()
        openSettled(A)
        val a = current()

        compose.runOnIdle { nav().navigate(A) }
        assertTrue(a.reopenRequested)
        assertEquals(1, nav().reopenRevision)

        // The exit save finishing calls Back; the reopen keeps the tool.
        compose.runOnIdle { nav().leave() }
        compose.waitForIdle()
        assertFalse(a.runtimeLeaving)
        assertEquals(listOf<NavKey>(A), stack.toList())
        assertEquals(1, nav().generation)
    }

    @Test
    fun onceTheRuntimeHandledTheReopenBackLeavesAgain() {
        composeNavigator()
        openSettled(A)
        val a = current()
        compose.runOnIdle { nav().navigate(A) }
        assertTrue(a.reopenRequested)

        compose.runOnIdle { nav().reopenHandled() }
        assertFalse(a.reopenRequested)
        compose.runOnIdle { nav().leave() }
        compose.waitForIdle()
        assertTrue(stack.isEmpty())
    }

    @Test
    fun aToolRestoredMidReturnHasItsRouteRemoved() {
        val restoration = StateRestorationTester(compose)
        stack += A
        restoration.setContent { navigator = rememberRuntimeNavigator(stack, stack.lastOrNull() as? RuntimeRoute) }
        compose.waitForIdle()
        assertFalse(current().returnInterrupted)
        // Rotation cuts the return off before it pops the route.
        compose.runOnIdle { current().runtimeLeaving = true }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue(stack.isEmpty())
    }

    @Test
    fun reopeningBeforeTheRuntimeExistsHasNoExitToCancel() {
        composeNavigator()
        compose.runOnIdle { nav().navigate(A) }
        compose.waitForIdle()

        compose.runOnIdle { nav().navigate(A) }
        assertFalse(current().reopenRequested)
        assertEquals(0, nav().reopenRevision)
    }

    @Test
    fun theSameToolPushedRightAfterItsPopGetsAFreshHolder() {
        composeNavigator()
        openSettled(A)
        val a = current()

        // The return pops the route and the tool is opened again before a recomposition.
        compose.runOnIdle {
            a.runtimeLeaving = true
            stack.removeLastOrNull()
            nav().navigate(A)
        }
        assertEquals(2, nav().generation)
        assertEquals(0, nav().reopenRevision)
        compose.waitForIdle()
        assertNotSame(a, current())
        assertFalse(current().runtimeLeaving)
    }

    @Test
    fun theReopenEffectReopensThenHandsBackOnlyForRevisionsAfterItsKeyAppeared() {
        var key by mutableStateOf<Any>("first")
        var revision by mutableIntStateOf(3)
        val calls = mutableListOf<String>()
        compose.setContent {
            RuntimeReopenEffect(key, revision, reopen = { calls += "reopen" }, onHandled = { calls += "handled" })
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), calls)

        revision = 4
        compose.waitForIdle()
        assertEquals(listOf("reopen", "handled"), calls)

        // A new runtime (a new view model) ignores the revision it was created with.
        key = "second"
        compose.waitForIdle()
        assertEquals(2, calls.size)

        revision = 5
        compose.waitForIdle()
        assertEquals(listOf("reopen", "handled", "reopen", "handled"), calls)
    }

    private companion object {
        val A = RuntimeRoute("a")
        val B = RuntimeRoute("b")
    }
}
