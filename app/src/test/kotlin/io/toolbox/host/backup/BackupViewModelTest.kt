package io.toolbox.host.backup

import io.toolbox.tool.packagekit.backup.*
import java.io.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BackupViewModel>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.forEach { it.viewModelScope.cancel() }; dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun model(engine: FakeEngine = FakeEngine()) = BackupViewModel(engine, object : BackupDocumentIO {
        override suspend fun open(location: String) = ByteArrayInputStream(byteArrayOf(1))
        override suspend fun save(file: File, location: String, progress: (String, Int) -> Unit, published: () -> Unit) { progress("write", 90); published() }
    }).also(models::add)
    private suspend inline fun <reified T : BackupUiState> BackupViewModel.await() = state.first { it is T } as T

    @Test fun exportRequiresConsentAndPickerCancellationCleansTemporaryFile() = runTest(dispatcher) {
        val engine = FakeEngine(); val model = model(engine)
        model.requestExport(); assertEquals(BackupUiState.ExportConsent, model.state.value); assertEquals(0, engine.exports)
        model.prepareExport(); model.await<BackupUiState.ExportReady>(); assertTrue(engine.output!!.exists())
        model.exportPickerStarted(); model.exportSelected(null)
        assertEquals("已取消", model.await<BackupUiState.Result>().title); assertFalse(engine.output!!.exists())
    }
    @Test fun selectedDestinationGetsPublishedResultAndCleanup() = runTest(dispatcher) {
        val engine = FakeEngine(); val model = model(engine)
        model.prepareExport(); model.await<BackupUiState.ExportReady>(); model.exportSelected("local")
        assertEquals("备份已导出", model.await<BackupUiState.Result>().title); assertFalse(engine.output!!.exists())
    }
    @Test fun restoreOnlyBeginsAfterPreviewConfirmationAndShowsResult() = runTest(dispatcher) {
        val engine = FakeEngine(); val model = model(engine)
        model.restoreSelected("local"); model.await<BackupUiState.ConfirmRestore>(); assertEquals(0, engine.restores)
        model.confirmRestore(); assertEquals("恢复已完成", model.await<BackupUiState.Result>().title); assertEquals(1, engine.restores)
    }
    @Test fun restoreFailureIsActionableAndCleansPreview() = runTest(dispatcher) {
        val engine = FakeEngine().apply { failure = BackupException("CHECKSUM") }; val model = model(engine)
        model.restoreSelected("local"); val preview = model.await<BackupUiState.ConfirmRestore>().preview
        model.confirmRestore(); val result = model.await<BackupUiState.Result>()
        assertTrue(result.failed); assertTrue("重新导出" in result.message); assertFalse(preview.prepared.directory.exists())
    }
    @Test fun changingPreviewRequiresAnotherConfirmation() = runTest(dispatcher) {
        val engine = FakeEngine().apply { failure = BackupException("PREVIEW_CHANGED") }; val model = model(engine)
        model.restoreSelected("local"); model.await<BackupUiState.ConfirmRestore>(); model.confirmRestore()
        model.await<BackupUiState.ConfirmRestore>(); assertEquals(1, engine.refreshes)
    }
    @Test fun activeCancellationRunsCleanupBeforeResult() = runTest(dispatcher) {
        val engine = FakeEngine().apply { block = true }; val model = model(engine)
        model.restoreSelected("local"); model.await<BackupUiState.ConfirmRestore>(); model.confirmRestore()
        engine.entered.await(); model.cancel()
        assertEquals("已取消", model.await<BackupUiState.Result>().title); assertTrue(engine.cleaned)
    }
    private inner class FakeEngine : BackupOperations {
        override var committed = false
        var output: File? = null
        var exports = 0; var restores = 0; var refreshes = 0
        var failure: Exception? = null
        var block = false; var cleaned = false
        val entered = CompletableDeferred<Unit>()
        override suspend fun export(progress: (String, Int) -> Unit): BackupExport {
            exports++; val dir = temporary.newFolder(); output = File(dir, "backup.zip").apply { writeText("fixture") }
            return BackupExport(output!!, emptyList())
        }
        override suspend fun inspect(input: InputStream, progress: (String, Int) -> Unit): RestorePreview {
            input.close()
            return RestorePreview(PreparedBackup(temporary.newFolder(), BackupContents("0.7.6", 1, emptyList())), emptyList(), emptyMap(), emptySet(), emptySet(), emptyList())
        }
        override suspend fun refresh(preview: RestorePreview): RestorePreview { refreshes++; return preview }
        override suspend fun restore(preview: RestorePreview, progress: (String, Int) -> Unit): List<String> {
            restores++; entered.complete(Unit)
            try { if (block) awaitCancellation(); failure?.let { throw it }; committed = true; return emptyList() }
            finally { cleaned = true }
        }
    }
}
