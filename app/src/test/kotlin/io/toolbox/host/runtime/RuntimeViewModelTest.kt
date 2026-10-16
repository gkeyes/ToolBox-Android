package io.toolbox.host.runtime

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** RuntimeViewModel.reopen() against each way Back can be taking the tool away. */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessions = FakeForegroundSessions()
    private val models = mutableListOf<RuntimeViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model() = RuntimeViewModel(TOOL, sessions).also(models::add)

    @Test fun aReopenWithoutAnExitChangesNothing() = runTest(dispatcher) {
        val model = model()
        val revision = model.exitRevision.value
        sessions.calls.clear()

        model.reopen()
        runCurrent()

        assertEquals(emptyList<String>(), sessions.calls)
        assertEquals(revision, model.exitRevision.value)
    }

    @Test fun aReopenAfterAFinishedExitReopensTheSealedBridge() = runTest(dispatcher) {
        val model = model()
        var saved = 0
        model.requestExit { saved += 1 }
        runCurrent()
        assertEquals(1, saved)
        sessions.calls.clear()

        model.reopen()

        assertEquals(listOf("cancelClose"), sessions.calls)
    }

    @Test fun aReopenDuringTheExitSaveStopsTheExit() = runTest(dispatcher) {
        val save = CompletableDeferred<Boolean>()
        sessions.save = save
        val model = model()
        var saved = 0
        model.requestExit { saved += 1 }
        runCurrent()
        assertNotNull(model.closeState.value)

        model.reopen()
        save.complete(true)
        runCurrent()

        assertEquals(0, saved)
        assertNull(model.closeState.value)
        assertEquals("cancelClose", sessions.calls.last())
    }

    @Test fun aReopenAfterDiscardingBuildsTheRuntimeAgain() = runTest(dispatcher) {
        sessions.save = CompletableDeferred(false)
        val model = model()
        var saved = 0
        model.requestExit { saved += 1 }
        runCurrent()
        assertNotNull(model.closeState.value?.error)
        model.discardAndClose()
        assertEquals(1, saved)
        assertEquals("discard", sessions.calls.last())
        val revision = model.exitRevision.value
        sessions.calls.clear()

        model.reopen()

        assertEquals(listOf("open"), sessions.calls)
        // The spent exit confirmation is replaced, so Back asks again.
        assertFalse(model.exitRevision.value == revision)

        // Only once: a second reopen has nothing left to rebuild.
        sessions.calls.clear()
        model.reopen()
        assertEquals(emptyList<String>(), sessions.calls)
    }

    private companion object {
        const val TOOL = "tool"
    }
}
