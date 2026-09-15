package io.toolbox.host.importflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.host.HostExampleInstallResult
import io.toolbox.host.HostImportCancellationResult
import io.toolbox.host.HostImportConfirmation
import io.toolbox.host.HostImportResult
import io.toolbox.host.HostPackageOperations
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.lifecycle.PackageImportControl
import io.toolbox.tool.packagekit.lifecycle.PackageImportPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ImportUiState(
    val working: Boolean = false,
    val message: String? = null,
    val succeeded: Boolean = false,
    val confirmation: HostImportConfirmation? = null,
    val importPhase: PackageImportPhase? = null,
)

internal class ImportViewModel(
    private val operations: HostPackageOperations,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()
    private var activeControl: PackageImportControl? = null

    fun importPackage(input: PackageInput) {
        if (mutableState.value.working) return
        val control = PackageImportControl()
        runImport(control) {
            operations.importPackage(input, control).toUiState()
        }
    }

    fun confirmVersionReplacement() {
        val confirmation = mutableState.value.confirmation ?: return
        if (mutableState.value.working) return
        val control = PackageImportControl()
        runImport(control) { operations.confirmImport(confirmation.id, control).toUiState() }
    }

    fun cancelActiveImport() {
        if (activeControl?.requestCancel() == true) {
            mutableState.value = mutableState.value.copy(importPhase = PackageImportPhase.CANCELLING)
        }
    }

    fun cancelVersionReplacement() {
        val confirmation = mutableState.value.confirmation ?: return
        mutableState.value = ImportUiState()
        viewModelScope.launch {
            val result = try {
                withContext(ioDispatcher) { operations.cancelImport(confirmation.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                HostImportCancellationResult.Failed("CANCEL_FAILED", "临时安装文件未能清理，请稍后重试。")
            }
            if (result is HostImportCancellationResult.Failed && mutableState.value == ImportUiState()) {
                mutableState.value = ImportUiState(message = result.message)
            }
        }
    }

    fun installBundledExamples() {
        if (mutableState.value.working) return
        runImport {
            when (val result = operations.installBundledExamples()) {
                is HostExampleInstallResult.Installed -> ImportUiState(
                    message = "已安装 ${result.count} 个范例",
                    succeeded = true,
                )
                is HostExampleInstallResult.Failed -> ImportUiState(message = result.message)
            }
        }
    }

    fun pickerRejected(message: String) {
        if (mutableState.value.working) return
        mutableState.value = ImportUiState(message = message)
    }

    fun dismissMessage() {
        if (mutableState.value.working) return
        mutableState.value = ImportUiState()
    }

    private fun runImport(control: PackageImportControl? = null, block: suspend () -> ImportUiState) {
        activeControl = control
        mutableState.value = ImportUiState(working = true, importPhase = control?.phase?.value)
        viewModelScope.launch {
            val phaseObserver = control?.let {
                launch {
                    it.phase.collect { phase ->
                        mutableState.value = mutableState.value.copy(importPhase = phase)
                    }
                }
            }
            val nextState = try {
                withContext(ioDispatcher) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ImportUiState(message = "安装未完成，请重新选择工具包。")
            } finally {
                phaseObserver?.cancel()
                activeControl = null
            }
            mutableState.value = nextState
        }
    }

    private fun HostImportResult.toUiState(): ImportUiState = when (this) {
        HostImportResult.Cancelled -> ImportUiState(message = "已取消安装")
        is HostImportResult.Installed -> ImportUiState(
            message = "$toolName 已安装",
            succeeded = true,
        )
        is HostImportResult.ConfirmationRequired -> ImportUiState(confirmation = confirmation)
        is HostImportResult.Failed -> ImportUiState(message = message)
    }
}
