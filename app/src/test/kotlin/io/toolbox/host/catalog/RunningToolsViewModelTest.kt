package io.toolbox.host.catalog

import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunningToolsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val first = session("first", "io.toolbox.first", 1L)
    private val second = session("second", "io.toolbox.second", 2L)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun cancellingConfirmationDoesNotStopAnySession() = runTest(dispatcher) {
        val source = MutableStateFlow(listOf(first, second))
        val calls = mutableListOf<String>()
        val viewModel = RunningToolsViewModel(source) { calls += it; true }
        runCurrent()

        viewModel.requestStop(first.sessionId)
        assertEquals(first, viewModel.state.value.confirmation)
        viewModel.cancelStop()

        assertNull(viewModel.state.value.confirmation)
        assertEquals(listOf(first, second), viewModel.state.value.sessions)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun confirmationStopsOnlyTheSelectedSessionAndIgnoresDuplicateClicks() = runTest(dispatcher) {
        val source = MutableStateFlow(listOf(first, second))
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val viewModel = RunningToolsViewModel(source) { id ->
            calls += id
            release.await()
            source.value = source.value.filterNot { it.sessionId == id }
            true
        }
        runCurrent()
        viewModel.requestStop(first.sessionId)

        viewModel.confirmStop()
        viewModel.confirmStop()
        runCurrent()

        assertEquals(listOf(first.sessionId), calls)
        assertEquals(first.sessionId, viewModel.state.value.stoppingSessionId)
        assertEquals(listOf(first, second), viewModel.state.value.sessions)

        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(second), viewModel.state.value.sessions)
        assertNull(viewModel.state.value.stoppingSessionId)
        assertNull(viewModel.state.value.feedback)
    }

    @Test
    fun anExpiredConfirmationCannotStopAReplacementSession() = runTest(dispatcher) {
        val source = MutableStateFlow(listOf(first))
        val calls = mutableListOf<String>()
        val viewModel = RunningToolsViewModel(source) { calls += it; true }
        runCurrent()
        viewModel.requestStop(first.sessionId)

        val replacement = first.copy(sessionId = "new-session")
        source.value = listOf(replacement)
        viewModel.confirmStop()
        runCurrent()

        assertTrue(calls.isEmpty())
        assertNull(viewModel.state.value.confirmation)
        assertEquals(listOf(replacement), viewModel.state.value.sessions)
    }

    @Test
    fun sourceUpdatesClearTheConfirmationAndRemoveTheLastSession() = runTest(dispatcher) {
        val source = MutableStateFlow(listOf(first))
        val viewModel = RunningToolsViewModel(source) { error("must not stop") }
        runCurrent()
        viewModel.requestStop(first.sessionId)

        source.value = emptyList()
        runCurrent()

        assertTrue(viewModel.state.value.sessions.isEmpty())
        assertNull(viewModel.state.value.confirmation)
    }

    @Test
    fun stopFailureRetainsSourceStateAndAllowsRetryWithoutLeakingErrors() = runTest(dispatcher) {
        val source = MutableStateFlow(listOf(first, second))
        var attempts = 0
        val viewModel = RunningToolsViewModel(source) { id ->
            attempts += 1
            if (attempts == 1) throw IllegalStateException("private-runtime-detail")
            source.value = source.value.filterNot { it.sessionId == id }
            true
        }
        runCurrent()
        viewModel.requestStop(first.sessionId)

        viewModel.confirmStop()
        advanceUntilIdle()

        assertEquals("BACKGROUND_STOP_FAILED", viewModel.state.value.feedback?.code)
        assertEquals(listOf(first, second), viewModel.state.value.sessions)
        assertNull(viewModel.state.value.stoppingSessionId)
        assertTrue(viewModel.state.value.feedback?.message?.contains("private-runtime-detail") == false)

        viewModel.requestStop(first.sessionId)
        viewModel.confirmStop()
        advanceUntilIdle()

        assertEquals(2, attempts)
        assertEquals(listOf(second), viewModel.state.value.sessions)
        assertNull(viewModel.state.value.feedback)
    }

    private fun session(id: String, toolId: String, startedAt: Long) = RuntimeBackgroundSessionUi(
        sessionId = id,
        toolId = toolId,
        toolName = toolId,
        startedAt = startedAt,
        notificationId = startedAt.toInt(),
    )
}
