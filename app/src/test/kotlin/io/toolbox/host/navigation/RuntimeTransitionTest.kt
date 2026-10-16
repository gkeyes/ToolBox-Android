package io.toolbox.host.navigation

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.toolbox.core.ui.component.ToolBoxMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composes the same transition holders ToolBoxNavigation uses, so dropping the carry, starting a
 * replacing tool at Animatable(1f) or losing a per-route key fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RuntimeTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var route by mutableStateOf<RuntimeRoute?>(null)
    private var generation by mutableIntStateOf(0)
    private var scope: CoroutineScope? = null
    private var transition: RuntimeTransition? = null
    private var glassLive: Boolean? = null
    private var sourceHidden: Boolean? = null

    private fun composeTransition(initial: RuntimeRoute?) {
        route = initial
        compose.setContent {
            val current = rememberRuntimeTransition(route, generation)
            scope = rememberCoroutineScope()
            val live by rememberPrimaryGlassLive(current, firstRetainedProgress = null)
            val hidden by rememberSourceHidden(current)
            transition = current
            glassLive = live
            sourceHidden = hidden
        }
        compose.waitForIdle()
    }

    private fun current() = checkNotNull(transition)

    private fun snap(animatable: Animatable<Float, *>, value: Float) =
        compose.runOnIdle { runBlocking { animatable.snapTo(value) } }

    @Test
    fun aFreshToolOpensFromAFullyVisibleSource() {
        composeTransition(null)
        assertEquals(0f, current().entryProgress.value)

        route = RuntimeRoute("a")
        compose.waitForIdle()
        assertEquals(1f, current().entryProgress.value)
        assertFalse(current().coverBeneathSource)
    }

    @Test
    fun aSwitchMidEntryContinuesFromTheReplacedToolsProgress() {
        composeTransition(RuntimeRoute("a"))
        val a = current()
        snap(a.entryProgress, 0.4f)

        route = RuntimeRoute("b")
        compose.waitForIdle()
        assertNotSame(a, current())
        assertEquals(0.4f, current().entryProgress.value)
        assertFalse(current().coverBeneathSource)
    }

    @Test
    fun aSwitchFromASettledToolKeepsTheSourceHidden() {
        composeTransition(RuntimeRoute("a"))
        snap(current().entryProgress, 0f)
        compose.runOnIdle { current().entryCoverVisible = false }

        route = RuntimeRoute("b")
        compose.waitForIdle()
        assertEquals(0f, current().entryProgress.value)
        compose.runOnIdle { assertEquals(true, sourceHidden) }
    }

    @Test
    fun aSwitchDuringTheReturnKeepsTheSourceAlphaAndPutsTheCoverBeneathIt() {
        composeTransition(RuntimeRoute("a"))
        val a = current()
        compose.runOnIdle {
            a.entryCoverVisible = false
            a.sourceAboveRuntime = true
        }
        snap(a.sourceReturnProgress, 0.6f)

        route = RuntimeRoute("b")
        compose.waitForIdle()
        val b = current()
        assertTrue(b.coverBeneathSource)
        assertEquals(1f - 0.6f, ToolBoxMotion.fadeThroughOutgoingAlpha(1f - b.entryProgress.value), 0.0001f)
    }

    @Test
    fun aSwitchAfterBackWhileLoadingCarriesTheCoverEntryAndKeepsTheCoverOnTop() {
        composeTransition(RuntimeRoute("a"))
        val a = current()
        snap(a.entryProgress, 0.3f)
        // Back while the cover is still showing: the source sits opaque beneath the cover.
        compose.runOnIdle {
            a.sourceAboveRuntime = true
            a.runtimeLeaving = true
        }

        route = RuntimeRoute("b")
        compose.waitForIdle()
        val b = current()
        assertFalse(b.coverBeneathSource)
        assertEquals(0.3f, b.entryProgress.value, 0.0001f)
        // The source stays opaque under the new cover, exactly as it was under the old one.
        assertEquals(0f, a.sourceDepth())
        assertTrue(b.sourceHeldUnderCover)
        assertTrue(b.sourceAboveRuntime)
        assertEquals(0f, b.sourceDepth())
        assertFalse(b.sourceHidden)
        // Held under the incoming cover, the source stays out of the semantics tree; the old one,
        // going out, kept its semantics.
        assertTrue(b.sourceSemanticsHidden)
        assertFalse(a.sourceSemanticsHidden)
    }

    @Test
    fun theSameToolPushedAgainAfterItsReturnGetsAFreshHolder() {
        composeTransition(RuntimeRoute("a"))
        val a = current()
        compose.runOnIdle {
            a.entryCoverVisible = false
            a.runtimeLeaving = true
        }

        // Popped and pushed again before a recomposition: same route, next generation.
        generation += 1
        compose.waitForIdle()
        val again = current()
        assertNotSame(a, again)
        assertFalse(again.runtimeLeaving)
        assertFalse(again.returnInterrupted)
    }

    @Test
    fun cancellingAReturnFromASettledToolBringsTheToolBack() {
        composeTransition(RuntimeRoute("a"))
        val a = current()
        compose.runOnIdle {
            a.runtimeLayerEnabled = true
            a.entryCoverVisible = false
            a.sourceAboveRuntime = true
            a.runtimeLeaving = true
        }
        snap(a.sourceReturnProgress, 0.4f)

        compose.runOnIdle {
            assertTrue(a.beginReturnCancel())
            checkNotNull(scope).launch { a.animateReturnCancel(snap()) }
        }
        compose.waitForIdle()
        assertFalse(a.runtimeLeaving)
        assertFalse(a.sourceAboveRuntime)
        assertEquals(0f, a.sourceReturnProgress.value)
        assertTrue(a.sourceHidden)
        assertFalse(a.beginReturnCancel())
    }

    @Test
    fun cancellingAReturnWhileLoadingFadesTheCoverBackInAndEnablesTheTool() {
        composeTransition(RuntimeRoute("a"))
        val a = current()
        snap(a.entryProgress, 0.6f)
        compose.runOnIdle {
            a.sourceAboveRuntime = true
            a.runtimeLeaving = true
        }

        compose.runOnIdle {
            assertTrue(a.beginReturnCancel())
            checkNotNull(scope).launch { a.animateReturnCancel(snap()) }
        }
        compose.waitForIdle()
        assertEquals(0f, a.entryProgress.value)
        assertTrue(a.runtimeLayerEnabled)
        assertFalse(a.sourceAboveRuntime)
        assertTrue(a.entryCoverVisible)
        assertTrue(a.sourceHidden)
    }

    @Test
    fun reopeningTheToolOnTopCancelsItsReturnOrPendingExit() {
        val a = RuntimeRoute("a")
        assertEquals(RuntimeReopen.CancelReturn, runtimeReopen(a, RuntimeRoute("a"), leaving = true))
        assertEquals(RuntimeReopen.CancelExit, runtimeReopen(a, RuntimeRoute("a"), leaving = false))
        assertEquals(RuntimeReopen.None, runtimeReopen(RuntimeRoute("b"), a, leaving = true))
        // Already popped by the return: push it again.
        assertEquals(RuntimeReopen.None, runtimeReopen(a, null, leaving = true))
        assertEquals(RuntimeReopen.None, runtimeReopen(SettingsRoute, a, leaving = true))
    }

    @Test
    fun glassAndSourceHiddenFollowTheNewToolAfterASwitch() {
        composeTransition(RuntimeRoute("a"))
        snap(current().entryProgress, 0.9f)
        compose.runOnIdle {
            assertEquals(true, glassLive)
            assertEquals(false, sourceHidden)
        }

        route = RuntimeRoute("b")
        compose.waitForIdle()
        // The new tool settles: only its state hides the source now.
        compose.runOnIdle { current().entryCoverVisible = false }
        compose.runOnIdle {
            assertEquals(false, glassLive)
            assertEquals(true, sourceHidden)
        }
    }

    @Test
    fun closingTheToolStartsTheNextOpenFresh() {
        composeTransition(RuntimeRoute("a"))
        snap(current().entryProgress, 0f)

        route = null
        compose.waitForIdle()
        route = RuntimeRoute("b")
        compose.waitForIdle()
        assertEquals(1f, current().entryProgress.value)
    }

    @Test
    fun reopeningTheSameToolAfterClosingItStartsFresh() {
        composeTransition(RuntimeRoute("a"))
        snap(current().entryProgress, 0f)

        route = null
        compose.waitForIdle()
        route = RuntimeRoute("a")
        compose.waitForIdle()
        assertEquals(1f, current().entryProgress.value)
        assertFalse(current().returnInterrupted)
    }

    @Test
    fun aToolRestoredMidReturnShowsTheSourceAndFinishesTheReturn() {
        val restoration = StateRestorationTester(compose)
        var restored: RuntimeTransition? = null
        restoration.setContent { restored = rememberRuntimeTransition(RuntimeRoute("a")) }
        compose.waitForIdle()
        assertFalse(checkNotNull(restored).returnInterrupted)
        compose.runOnIdle { checkNotNull(restored).runtimeLeaving = true }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        val transition = checkNotNull(restored)
        assertTrue(transition.returnInterrupted)
        assertTrue(transition.runtimeLeaving)
        assertTrue(transition.sourceAboveRuntime)
        assertFalse(transition.entryCoverVisible)
        assertFalse(transition.sourceHidden)
    }

    @Test
    fun aSwitchDuringTheReturnIsNotTreatedAsAnInterruptedReturn() {
        composeTransition(RuntimeRoute("a"))
        compose.runOnIdle { current().runtimeLeaving = true }

        route = RuntimeRoute("b")
        compose.waitForIdle()
        assertFalse(current().returnInterrupted)
        assertFalse(current().runtimeLeaving)
    }

    @Test
    fun aToolRestoredAfterRecreationStartsSettledInsteadOfReplayingItsEntry() {
        val restoration = StateRestorationTester(compose)
        var restored: RuntimeTransition? = null
        restoration.setContent { restored = rememberRuntimeTransition(RuntimeRoute("a")) }
        compose.waitForIdle()
        assertEquals(1f, checkNotNull(restored).entryProgress.value)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(0f, checkNotNull(restored).entryProgress.value)
    }

    @Test
    fun runtimeEntryStartMapsOpeningAndCarry() {
        assertEquals(1f, runtimeEntryStart(opening = true, carriedEntry = null))
        assertEquals(0.4f, runtimeEntryStart(opening = true, carriedEntry = 0.4f))
        assertEquals(0f, runtimeEntryStart(opening = false, carriedEntry = 0.4f))
    }
}
