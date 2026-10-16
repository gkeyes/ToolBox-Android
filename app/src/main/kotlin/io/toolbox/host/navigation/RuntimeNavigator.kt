package io.toolbox.host.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import io.toolbox.host.HostTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Opens, leaves and reopens tools on the secondary [stack], driving the composed [transition].
 * [rememberRuntimeNavigator] sets [transition] on every composition; the decisions read the live stack.
 */
@Stable
internal class RuntimeNavigator(
    private val stack: MutableList<NavKey>,
    private val scope: CoroutineScope,
) {
    /**
     * Bumped whenever a tool is actually pushed. The transition is keyed on it, so the same tool
     * pushed again right after its return popped it (before a recomposition saw the pop) gets a
     * fresh holder instead of the leaving one.
     */
    var generation by mutableIntStateOf(0)
        private set

    /** Bumped when a reopen should undo an exit Back started; the runtime layer acts on it. */
    var reopenRevision by mutableIntStateOf(0)
        private set

    lateinit var transition: RuntimeTransition

    fun navigate(route: ToolBoxRoute) {
        val current = transition
        // The live stack top, not the composed route: the return may have just popped it.
        when (runtimeReopen(route, stack.lastOrNull() as? RuntimeRoute, current.runtimeLeaving)) {
            RuntimeReopen.CancelReturn -> {
                cancelReturn()
                reopenRevision += 1
            }
            // The runtime cancels the close only when Back is still saving for an exit.
            RuntimeReopen.CancelExit -> if (current.runtimeLayerEnabled) {
                current.reopenRequested = true
                reopenRevision += 1
            }
            RuntimeReopen.None -> if (stack.pushSecondary(route) && route is RuntimeRoute) {
                generation += 1
            }
        }
    }

    /** Back from the tool: the source returns over it, then the route is removed. */
    fun leave() {
        val current = transition
        val activeRoute = current.route
        // Reopened while the exit was saving: stay; the runtime cancels the exit.
        if (current.reopenRequested || activeRoute == null || current.runtimeLeaving) return
        current.runtimeLeaving = true
        // A cancelled return may still be animating back; continue from where it stands.
        val resuming = current.sourceAboveRuntime
        current.returnJob?.cancel()
        current.returnJob = scope.launch {
            if (current.runtimeLayerEnabled && !current.entryCoverVisible) {
                if (!resuming) current.sourceReturnProgress.snapTo(1f)
                current.sourceAboveRuntime = true
                withFrameNanos { }
                current.sourceReturnProgress.animateTo(0f, animationSpec = RetainedPageMotion)
            } else {
                if (!resuming) current.sourceReturnProgress.snapTo(0f)
                current.sourceAboveRuntime = true
                withFrameNanos { }
                current.entryProgress.animateTo(1f, animationSpec = RetainedPageMotion)
            }
            if (stack.lastOrNull() == activeRoute) stack.removeLastOrNull()
        }
    }

    /**
     * Reopening the tool that is returning (a notification or shortcut mid-return) cancels the
     * return and brings the tool back, instead of being dropped while the return completes.
     */
    private fun cancelReturn() {
        val current = transition
        if (current.beginReturnCancel()) {
            current.returnJob?.cancel()
            current.returnJob = scope.launch { current.animateReturnCancel() }
        }
    }

    /** The runtime cancelled the exit the reopen asked about: Back leaves the tool again. */
    fun reopenHandled() {
        transition.reopenRequested = false
    }

    /** A tool restored mid-return (rotation) finishes the return: its route comes off. */
    fun finishInterruptedReturn() {
        val current = transition
        if (current.returnInterrupted && stack.lastOrNull() == current.route) stack.removeLastOrNull()
    }
}

/**
 * The navigator for the tool on top of [stack] ([runtimeRoute]), with its transition and the
 * effects that drive it. ToolBoxNavigation and the tests compose the same wiring through this.
 */
@Composable
internal fun rememberRuntimeNavigator(stack: MutableList<NavKey>, runtimeRoute: RuntimeRoute?): RuntimeNavigator {
    val scope = rememberCoroutineScope()
    val navigator = remember(stack, scope) { RuntimeNavigator(stack, scope) }
    val transition = rememberRuntimeTransition(runtimeRoute, navigator.generation)
    navigator.transition = transition
    LaunchedEffect(transition) { navigator.finishInterruptedReturn() }
    RuntimeTransitionEffects(transition)
    return navigator
}

/** The entry and cover hand-off for [transition]: what runs once the cover is in place. */
@Composable
internal fun RuntimeTransitionEffects(transition: RuntimeTransition) {
    LaunchedEffect(transition, transition.entryCoverMeasured) {
        if (transition.route != null && transition.entryCoverMeasured) {
            HostTrace.bestEffortAsyncSection("tool.shell.enter") {
                withFrameNanos { }
                transition.entryProgress.animateTo(0f, animationSpec = RetainedPageMotion)
            }
            transition.runtimeLayerEnabled = true
            // A source held opaque under this cover drops below once the cover is fully in.
            if (!transition.runtimeLeaving) transition.sourceAboveRuntime = false
        }
    }

    LaunchedEffect(
        transition,
        transition.runtimeLayerEnabled,
        transition.runtimePresentationReady,
        transition.runtimeLeaving,
        transition.sourceAboveRuntime,
    ) {
        if (
            transition.route != null && transition.runtimeLayerEnabled && transition.runtimePresentationReady &&
            !transition.runtimeLeaving && !transition.sourceAboveRuntime
        ) {
            withFrameNanos { }
            withFrameNanos { }
            transition.entryCoverVisible = false
        }
    }
}
