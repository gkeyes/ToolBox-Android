package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserInteractionStateTest {
    @Test fun idleOffersReload() {
        assertEquals(BrowserLoadAction.Reload, BrowserInteractionState().loadAction(clearing = false))
    }

    @Test fun navigationOffersStopUntilFinished() {
        val loading = BrowserInteractionState().pageStarted()
        assertEquals(BrowserLoadAction.Stop, loading.loadAction(clearing = false))
        assertEquals(BrowserLoadAction.Reload, loading.pageFinished().loadAction(clearing = false))
    }

    @Test fun stopDoesNotBecomeANetworkFailureOrRestartLoadingWhenFinishedArrives() {
        val stopped = BrowserInteractionState().pageStarted().stopRequested().pageFinished()
        assertTrue(stopped.stoppedByUser)
        assertFalse(stopped.loading)
        assertEquals(BrowserLoadAction.Reload, stopped.loadAction(clearing = false))
    }

    @Test fun newNavigationClearsIntentionalStop() {
        assertFalse(BrowserInteractionState().stopRequested().pageStarted().stoppedByUser)
    }

    @Test fun clearingDisablesEveryLoadAction() {
        for (state in listOf(
            BrowserInteractionState(),
            BrowserInteractionState().pageStarted(),
            BrowserInteractionState().rendererUnresponsive(),
        )) assertEquals(BrowserLoadAction.Disabled, state.loadAction(clearing = true))
    }

    @Test fun rendererStallOffersRecoveryRatherThanStopOrSilentReload() {
        val state = BrowserInteractionState().pageStarted().rendererUnresponsive()
        assertTrue(state.showRecoveryPrompt)
        assertEquals(BrowserLoadAction.Recover, state.loadAction(clearing = false))
        assertFalse(state.restarting)
    }

    @Test fun keepWaitingPreservesPageAndSuppressesRepeatedCallbacks() {
        val waiting = BrowserInteractionState().pageStarted().rendererUnresponsive().keepWaiting()
            .rendererUnresponsive().rendererUnresponsive()
        assertFalse(waiting.showRecoveryPrompt)
        assertTrue(waiting.unresponsive)
        assertTrue(waiting.loading)
        assertFalse(waiting.restarting)
    }

    @Test fun toolbarCanReopenRecoveryAfterWaiting() {
        val waiting = BrowserInteractionState().rendererUnresponsive().keepWaiting()
        assertEquals(BrowserLoadAction.Recover, waiting.loadAction(clearing = false))
        assertTrue(waiting.requestRecoveryPrompt().showRecoveryPrompt)
    }

    @Test fun responsiveCallbackClosesStalePrompt() {
        val recovered = BrowserInteractionState().rendererUnresponsive().rendererResponsive()
        assertFalse(recovered.showRecoveryPrompt)
        assertFalse(recovered.unresponsive)
    }

    @Test fun laterStallCanPromptAgainAfterRecovery() {
        val nextEpisode = BrowserInteractionState().rendererUnresponsive().keepWaiting()
            .rendererResponsive().rendererUnresponsive()
        assertTrue(nextEpisode.showRecoveryPrompt)
    }

    @Test fun finishedNavigationAloneDoesNotClaimRendererHasRecovered() {
        assertTrue(BrowserInteractionState().rendererUnresponsive().pageFinished().unresponsive)
    }

    @Test fun explicitRestartDisablesDuplicateRequests() {
        val restarting = BrowserInteractionState().rendererUnresponsive().restartRequested()
        assertTrue(restarting.restarting)
        assertFalse(restarting.showRecoveryPrompt)
        assertEquals(BrowserLoadAction.Disabled, restarting.loadAction(clearing = false))
    }

    @Test fun lateCallbacksDoNotCancelAnAcceptedRestart() {
        val restarting = BrowserInteractionState().rendererUnresponsive().restartRequested()
        assertEquals(restarting, restarting.rendererResponsive())
        assertEquals(restarting, restarting.rendererUnresponsive())
    }

    @Test fun failedRestartAllowsAnotherExplicitAttemptWithoutPromptLoop() {
        val failed = BrowserInteractionState().rendererUnresponsive().restartRequested().restartFailed()
        assertFalse(failed.restarting)
        assertFalse(failed.showRecoveryPrompt)
        assertEquals(BrowserLoadAction.Recover, failed.loadAction(clearing = false))
    }
}
