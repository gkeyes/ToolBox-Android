package io.toolbox.host.settings

import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.*
import io.toolbox.host.HostBackgroundOperations
import io.toolbox.host.background.BackgroundCancellationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsBackgroundUpdateTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<SettingsViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun failedWriteKeepsTheSavedSwitchAndRetriesTheWriteBeforeStopping() = runTest(dispatcher) {
        val repository = ControlledSettings()
        val background = RecordingBackground()
        val firstWrite = CompletableDeferred<DataResult<Unit>>()
        repository.writeResult = { firstWrite.await() }
        val model = model(repository, background)
        runCurrent()

        model.setBackgroundEnabled(false)
        model.setBackgroundEnabled(false)
        model.setBackgroundEnabled(true)
        model.retryBackgroundUpdate()
        runCurrent()
        assertTrue(model.state.value.backgroundWorking)
        assertFalse(model.state.value.canChangeBackground)
        assertEquals(1, repository.writes)
        firstWrite.complete(DataResult.Failure.StorageFailure("updateSettings"))
        advanceUntilIdle()

        assertTrue(repository.saved.value.backgroundEnabled)
        assertTrue(model.state.value.settings.backgroundEnabled)
        assertFalse(model.state.value.backgroundWorking)
        assertEquals(BackgroundSettingsOperation.SAVE, model.state.value.backgroundOperation)
        assertNotNull(model.state.value.backgroundError)
        assertEquals(0, background.attempts)

        repository.writeResult = { DataResult.Success(Unit) }
        model.retryBackgroundUpdate()
        advanceUntilIdle()
        assertEquals(2, repository.writes)
        assertEquals(1, background.attempts)
        assertFalse(repository.saved.value.backgroundEnabled)
        assertNull(model.state.value.backgroundOperation)
        assertNull(model.state.value.backgroundError)
        assertTrue(model.state.value.canChangeBackground)
    }

    @Test fun failedStopKeepsBackgroundOffAndRetriesOnlyTheUnfinishedStop() = runTest(dispatcher) {
        val repository = ControlledSettings()
        val background = RecordingBackground()
        background.stop = { throw IllegalStateException("stop failed") }
        val model = model(repository, background)
        runCurrent()
        model.setBackgroundEnabled(false)
        advanceUntilIdle()

        assertFalse(repository.saved.value.backgroundEnabled)
        assertFalse(model.state.value.settings.backgroundEnabled)
        assertFalse(model.state.value.backgroundWorking)
        assertEquals(BackgroundSettingsOperation.STOP, model.state.value.backgroundOperation)
        assertNotNull(model.state.value.backgroundError)
        model.setBackgroundEnabled(true)
        advanceUntilIdle()
        assertEquals(1, repository.writes)
        assertEquals(1, background.attempts)

        // A different settings write must not dismiss the unfinished background operation.
        model.selectTheme(ThemeMode.DARK)
        advanceUntilIdle()
        assertNotNull(model.state.value.backgroundError)
        val writesBeforeRetry = repository.writes
        val stopComplete = CompletableDeferred<Unit>()
        background.stop = { stopComplete.await() }
        model.retryBackgroundUpdate()
        model.retryBackgroundUpdate()
        model.setBackgroundEnabled(true)
        runCurrent()
        assertTrue(model.state.value.backgroundWorking)
        assertFalse(model.state.value.settings.backgroundEnabled)
        assertEquals(writesBeforeRetry, repository.writes)
        assertEquals(2, background.attempts)
        stopComplete.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.backgroundWorking)
        assertNull(model.state.value.backgroundOperation)
        assertNull(model.state.value.backgroundError)
        assertFalse(model.state.value.settings.backgroundEnabled)
    }

    @Test fun thrownWriteFailureOffersSaveRetryAndReleasesBusyState() = runTest(dispatcher) {
        val repository = ControlledSettings().apply { writeResult = { throw IllegalStateException("write failed") } }
        val background = RecordingBackground()
        val model = model(repository, background)
        runCurrent()
        model.setBackgroundEnabled(false)
        advanceUntilIdle()
        assertEquals(BackgroundSettingsOperation.SAVE, model.state.value.backgroundOperation)
        assertNotNull(model.state.value.backgroundError)
        assertFalse(model.state.value.backgroundWorking)
        assertTrue(model.state.value.settings.backgroundEnabled)
        assertEquals(0, background.attempts)
    }

    @Test fun coroutineCancellationReleasesBusyStateWithoutReportingStopSuccess() = runTest(dispatcher) {
        val background = RecordingBackground().apply { stop = { throw CancellationException("screen closed") } }
        val model = model(ControlledSettings(), background)
        runCurrent()
        model.setBackgroundEnabled(false)
        advanceUntilIdle()
        assertFalse(model.state.value.backgroundWorking)
        assertFalse(model.state.value.settings.backgroundEnabled)
        assertEquals(BackgroundSettingsOperation.STOP, model.state.value.backgroundOperation)
    }

    private fun model(repository: ControlledSettings, background: RecordingBackground) =
        SettingsViewModel(repository, object : CatalogRepository {
            override fun observeCatalogProjection(): Flow<List<CatalogEntry>> = flowOf(emptyList())
            override fun observeTools(): Flow<List<InstalledTool>> = error("not used")
            override fun observeTool(toolId: String): Flow<InstalledTool?> = error("not used")
        }, background).also(models::add)
}

private class ControlledSettings : HostSettingsRepository {
    val saved = MutableStateFlow(HostSettings())
    override val settings: Flow<HostSettings> = saved
    var writes = 0
    var writeResult: suspend () -> DataResult<Unit> = { DataResult.Success(Unit) }
    override suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit> {
        writes++
        val result = writeResult()
        if (result is DataResult.Success) saved.value = transform(saved.value)
        return result
    }
}

private class RecordingBackground : HostBackgroundOperations {
    var attempts = 0
    var stop: suspend () -> Unit = {}
    override suspend fun cancelAll(toolIds: Collection<String>) { attempts++; stop() }
    override fun observeTasks(toolId: String): Flow<List<BackgroundTask>> = error("not used")
    override fun observeResult(taskId: String): Flow<TaskRunResult?> = error("not used")
    override suspend fun cancel(toolId: String, taskId: String): BackgroundCancellationResult = error("not used")
    override suspend fun cancelTool(toolId: String) = error("not used")
}
