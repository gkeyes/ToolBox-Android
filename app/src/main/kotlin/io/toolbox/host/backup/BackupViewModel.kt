package io.toolbox.host.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.tool.packagekit.backup.BackupException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object ExportConsent : BackupUiState
    data object ExportReady : BackupUiState
    data class Picking(val export: Boolean) : BackupUiState
    data class Progress(val label: String, val percent: Int, val cancelling: Boolean = false) : BackupUiState
    data class ConfirmRestore(val preview: RestorePreview) : BackupUiState
    data class Result(val title: String, val message: String, val warnings: List<String> = emptyList(), val failed: Boolean = false) : BackupUiState
}

internal class BackupViewModel(private val operations: BackupOperations, private val documents: BackupDocumentIO) : ViewModel() {
    private val mutableState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var exported: BackupExport? = null
    private var preview: RestorePreview? = null
    @Volatile private var publishedResult: BackupUiState.Result? = null
    private var restoring = false
    val busy get() = job != null

    fun requestExport() { if (!busy) mutableState.value = BackupUiState.ExportConsent }
    fun prepareExport() = runOperation {
        cleanup()
        exported = operations.export(::progress)
        BackupUiState.ExportReady
    }
    fun exportPickerStarted() { mutableState.value = BackupUiState.Picking(true) }
    fun restorePickerStarted() { if (!busy) mutableState.value = BackupUiState.Picking(false) }
    fun pickerFailed() = runOperation { throw BackupException("DESTINATION") }

    fun exportSelected(location: String?) {
        if (location == null) { cancel(); return }
        runOperation {
            val export = exported ?: throw BackupException("DESTINATION")
            try {
                documents.save(export.file, location, ::progress) {
                    publishedResult = BackupUiState.Result("备份已导出", "备份已写入所选位置，并通过完整性复核。请妥善保存这份未加密的应用数据。", export.warnings)
                }
                checkNotNull(publishedResult)
            } finally { withContext(NonCancellable) { withContext(Dispatchers.IO) { cleanupExport() } } }
        }
    }
    fun restoreSelected(location: String?) {
        if (location == null) { cancel(); return }
        runOperation {
            cleanup()
            val found = withContext(Dispatchers.IO) { documents.open(location).use { operations.inspect(it, ::progress) } }
            preview = found
            BackupUiState.ConfirmRestore(found)
        }
    }
    fun confirmRestore() = runOperation {
        val selected = preview ?: throw BackupException("MISSING")
        restoring = true
        try {
            val warnings = operations.restore(selected, ::progress)
            cleanup()
            BackupUiState.Result("恢复已完成", "已恢复宿主设置及 ${selected.recoverable.size} 个工具，跳过 ${selected.tools.size - selected.recoverable.size} 个不兼容工具。请重新打开工具检查数据。", warnings)
        } catch (failure: BackupException) {
            if (failure.code != "PREVIEW_CHANGED") throw failure
            val refreshed = operations.refresh(selected)
            preview = refreshed
            BackupUiState.ConfirmRestore(refreshed.copy(warnings = refreshed.warnings + backupMessage(failure)))
        }
    }
    fun cancel() {
        if (busy) {
            val current = mutableState.value as? BackupUiState.Progress
            mutableState.value = BackupUiState.Progress("正在安全取消，必要时回滚", current?.percent ?: 0, true)
            job?.cancel()
        } else runOperation {
            cleanup()
            BackupUiState.Result("已取消", "未执行恢复，临时文件已清理。")
        }
    }
    private fun progress(label: String, percent: Int) {
        val cancelling = (mutableState.value as? BackupUiState.Progress)?.cancelling == true
        mutableState.value = BackupUiState.Progress(label, percent.coerceIn(0, 100), cancelling)
    }
    private fun runOperation(action: suspend () -> BackupUiState) {
        if (busy) return
        publishedResult = null
        restoring = false
        mutableState.value = BackupUiState.Progress("正在准备", 0)
        // Publish actionable states only after IO/rollback is complete and admission is open.
        // LAZY assignment also works when Main.immediate executes a coroutine synchronously.
        val nextJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val next = try { action() }
            catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    cleanup()
                    publishedResult ?: if (restoring && operations.committed) {
                        BackupUiState.Result("恢复已完成", "取消请求晚于原子提交。数据已恢复，请重新打开工具确认。")
                    } else BackupUiState.Result("已取消", "操作已取消，恢复期间的临时改动已回滚。已停止的运行会话需手动重新打开。")
                }
            } catch (failure: Exception) {
                withContext(NonCancellable) { cleanup() }
                BackupUiState.Result("操作未完成", backupMessage(failure), failed = true)
            } finally { job = null }
            mutableState.value = next
        }
        job = nextJob
        nextJob.start()
    }
    private suspend fun cleanup() = withContext(Dispatchers.IO) {
        cleanupExport()
        preview?.prepared?.close()
        preview = null
    }
    private fun cleanupExport() { exported?.file?.parentFile?.deleteRecursively(); exported = null }
    override fun onCleared() {
        val running = job
        // Finite cleanup/rollback must outlive a screen which has been removed.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { running?.cancelAndJoin(); cleanup() }
    }
}
