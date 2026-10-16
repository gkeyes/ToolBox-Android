package io.toolbox.host.navigation

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.toolbox.core.ui.component.ToolBoxMotion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RuntimeTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun primaryGlassFollowsTheCurrentRoutesHiddenStateAfterAToolSwitch() {
        // ToolBoxNavigation recreates the runtime transition state per route, so the glass must read
        // the new route's state after a switch, not the replaced route's.
        var route by mutableStateOf<RuntimeRoute?>(RuntimeRoute("a"))
        val hiddenA = mutableStateOf(false)
        val hiddenB = mutableStateOf(true)
        var live: Boolean? = null
        compose.setContent {
            val current = route
            val hidden = if (current?.toolId == "b") hiddenB else hiddenA
            val glassLive by rememberPrimaryGlassLive(current, firstRetainedProgress = null) { hidden.value }
            live = glassLive
        }
        compose.runOnIdle { assertEquals(true, live) }

        route = RuntimeRoute("b")
        compose.runOnIdle { assertEquals(false, live) }

        hiddenB.value = false
        compose.runOnIdle { assertEquals(true, live) }
    }

    @Test
    fun primaryGlassPausesOnlyOnceTheFirstPageFullyCoversIt() {
        val progress = Animatable(0.5f)
        var live: Boolean? = null
        compose.setContent {
            val glassLive by rememberPrimaryGlassLive(null, progress) { false }
            live = glassLive
        }
        compose.runOnIdle { assertEquals(true, live) }

        compose.runOnIdle { runBlocking { progress.snapTo(0f) } }
        compose.runOnIdle { assertEquals(false, live) }
    }

    @Test
    fun aFreshToolOpensFromAFullyVisibleSource() {
        assertEquals(1f, runtimeEntryStart(opening = true, carriedEntry = null))
        assertEquals(0f, runtimeEntryStart(opening = false, carriedEntry = 0.4f))
    }

    @Test
    fun aToolSwitchContinuesTheTransitionInsteadOfReplayingIt() {
        // Mid-entry: the incoming tool picks up at the outgoing tool's entry progress.
        val midEntry = runtimeEntryEquivalent(sourceAboveRuntime = false, sourceReturnProgress = 0f, entryProgress = 0.4f)
        assertEquals(0.4f, runtimeEntryStart(opening = true, carriedEntry = midEntry))
        // Settled tool: the incoming tool starts with its cover fully shown and the source hidden.
        val settled = runtimeEntryEquivalent(sourceAboveRuntime = false, sourceReturnProgress = 0f, entryProgress = 0f)
        assertEquals(0f, runtimeEntryStart(opening = true, carriedEntry = settled))
    }

    @Test
    fun aSwitchDuringTheReturnKeepsTheSourceAtTheSameAlpha() {
        for (returnProgress in listOf(1f, 0.6f, 0.2f, 0f)) {
            val entry = runtimeEntryEquivalent(sourceAboveRuntime = true, sourceReturnProgress = returnProgress, entryProgress = 0f)
            val sourceAlphaWhileReturning = 1f - returnProgress
            val sourceAlphaAfterSwitch = ToolBoxMotion.fadeThroughOutgoingAlpha(1f - entry)
            assertEquals(sourceAlphaWhileReturning, sourceAlphaAfterSwitch, 0.0001f)
        }
    }
}
