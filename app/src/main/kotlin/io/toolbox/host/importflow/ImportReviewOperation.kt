package io.toolbox.host.importflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class ImportAsyncOperation { INSPECT, STARTUP_RECOVERY, RESUME, INSTALL, CANCEL }

internal fun CoroutineScope.launchImportOperation(
    operation: ImportAsyncOperation,
    currentState: () -> ImportReviewUiState,
    updateState: (ImportReviewUiState) -> Unit,
    block: suspend () -> Unit,
) = launch {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        updateState(currentState().afterUnexpectedFailure(operation))
    }
}

private fun ImportReviewUiState.afterUnexpectedFailure(operation: ImportAsyncOperation): ImportReviewUiState =
    when (operation) {
        ImportAsyncOperation.INSPECT -> ImportReviewUiState(
            nextRequestToken = nextRequestToken,
            selectedName = selectedName,
            error = ImportReviewError.Message("工具包检查意外中断，请重新选择文件。"),
        )
        ImportAsyncOperation.STARTUP_RECOVERY,
        ImportAsyncOperation.RESUME,
        -> ImportReviewUiState(
            nextRequestToken = nextRequestToken,
            error = ImportReviewError.Message("审核恢复意外中断，请稍后重试。"),
        )
        ImportAsyncOperation.INSTALL -> copy(
            phase = ImportReviewPhase.REVIEW,
            reviewConfirmed = false,
            error = ImportReviewError.Message("安装意外中断，请重新确认后重试。"),
        )
        ImportAsyncOperation.CANCEL -> copy(
            phase = ImportReviewPhase.REVIEW,
            cancelRetryAvailable = true,
            error = ImportReviewError.Message("审核会话清理意外中断，请重试取消。"),
        )
    }
