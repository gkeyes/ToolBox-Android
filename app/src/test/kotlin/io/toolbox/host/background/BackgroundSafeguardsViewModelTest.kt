package io.toolbox.host.background

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundSafeguardsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BackgroundSafeguardsViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun firstStateIsReadBeforeTheFirstFrameAndLiveSupportArrivesLater() = runTest(dispatcher) {
        val source = FakeSource()
        source.system = SYSTEM.copy(notificationsAllowed = true)
        val model = BackgroundSafeguardsViewModel(source).also(models::add)
        assertTrue(model.state.value.systemState.notificationsAllowed)
        assertFalse(model.state.value.focusState.hyperOsSupported)
        assertEquals(1, source.systemReads)

        runCurrent()
        assertTrue(model.state.value.focusState.hyperOsSupported)
        assertEquals(1, source.liveReads)
    }

    @Test fun resumeInvalidatesTheHyperOsCacheButPermissionResultsOnlyReRead() = runTest(dispatcher) {
        val source = FakeSource()
        val model = BackgroundSafeguardsViewModel(source).also(models::add)
        runCurrent()

        source.system = SYSTEM.copy(exactAlarmsAllowed = true)
        model.refresh()
        assertTrue(model.state.value.systemState.exactAlarmsAllowed)
        runCurrent()
        assertEquals(0, source.invalidations)
        assertEquals(2, source.liveReads)

        model.onResume()
        runCurrent()
        assertEquals(1, source.invalidations)
        assertEquals(3, source.systemReads)
        assertEquals(3, source.liveReads)
    }

    @Test fun aNewerRefreshReplacesAPendingLiveSupportRead() = runTest(dispatcher) {
        val source = FakeSource()
        val slow = CompletableDeferred<LiveNotificationSupportState>()
        source.nextLive = { slow.await() }
        val model = BackgroundSafeguardsViewModel(source).also(models::add)
        runCurrent()

        source.nextLive = { LIVE.copy(hyperOsProtocolVersion = 3) }
        model.refresh()
        runCurrent()
        slow.complete(LIVE.copy(hyperOsProtocolVersion = 1))
        runCurrent()
        assertEquals(3, model.state.value.focusState.hyperOsProtocolVersion)
    }

    private class FakeSource : BackgroundSystemStateSource {
        var system = SYSTEM
        var nextLive: suspend () -> LiveNotificationSupportState = { LIVE }
        var systemReads = 0
        var liveReads = 0
        var invalidations = 0

        override fun readSystemState(): BackgroundSystemState {
            systemReads++
            return system
        }

        override suspend fun readLiveSupport(): LiveNotificationSupportState {
            liveReads++
            return nextLive()
        }

        override fun invalidateLiveSupportCache() {
            invalidations++
        }
    }

    private companion object {
        val SYSTEM = BackgroundSystemState(false, false, false, false)
        val LIVE = LiveNotificationSupportState(
            hyperOsProtocolVersion = 2,
            hyperOsSupported = true,
            hyperOsPermissionReported = false,
            androidLiveAvailable = true,
            androidLiveAllowed = false,
        )
    }
}
