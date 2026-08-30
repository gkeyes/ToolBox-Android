package io.toolbox.host.importflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.host.HostExampleInstallResult
import io.toolbox.host.HostImportResult
import io.toolbox.host.HostPackageOperations
import io.toolbox.tool.packagekit.PackageInput
import kotlinx.coroutines.CancellationException
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
)

internal class ImportViewModel(
    private val operations: HostPackageOperations,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()

    fun importPackage(input: PackageInput) {
        if (mutableState.value.working) return
        runImport {
            when (val result = operations.importPackage(input)) {
                is HostImportResult.Installed -> ImportUiState(
                    message = "${result.toolName} 已安装",
                    succeeded = true,
                )
                is HostImportResult.Failed -> ImportUiState(message = result.message)
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
        mutableState.value = ImportUiState(message = message)
    }

    fun dismissMessage() {
        mutableState.value = ImportUiState()
    }

    private fun runImport(block: suspend () -> ImportUiState) {
        viewModelScope.launch {
            mutableState.value = ImportUiState(working = true)
            mutableState.value = try {
                withContext(Dispatchers.IO) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ImportUiState(message = "安装未完成，请重新选择工具包。")
            }
        }
    }
}
