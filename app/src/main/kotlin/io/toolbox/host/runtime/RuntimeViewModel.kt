package io.toolbox.host.runtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogRepository
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RuntimeBridgeProvider
import io.toolbox.tool.runtime.RuntimeCreationPermit
import io.toolbox.tool.runtime.RuntimeCreationPermitResult
import io.toolbox.tool.runtime.RuntimeDataCleanupResult
import io.toolbox.tool.runtime.RuntimePermitProvider
import io.toolbox.tool.runtime.RuntimePreparationResult
import io.toolbox.tool.runtime.ToolRuntimePreparer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface RuntimeUiState {
    data object Loading : RuntimeUiState

    data class Ready(
        val runtime: PreparedToolRuntime,
        val creationPermit: RuntimeCreationPermit,
    ) : RuntimeUiState

    data class Error(
        val code: String,
        val message: String,
    ) : RuntimeUiState
}

internal class RuntimeViewModel(
    private val toolId: String,
    private val catalog: CatalogRepository,
    private val preparer: ToolRuntimePreparer,
    private val runtimePermitProvider: RuntimePermitProvider,
    internal val bridgeProvider: RuntimeBridgeProvider,
) : ViewModel() {
    private val retryGeneration = MutableStateFlow(0)
    private val mutableState = MutableStateFlow<RuntimeUiState>(RuntimeUiState.Loading)
    val state: StateFlow<RuntimeUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(catalog.observeTool(toolId), retryGeneration) { tool, _ -> tool }
                .catch { emit(null) }
                .collectLatest { tool ->
                    val previous = mutableState.value
                    val prepared = try {
                        withContext(Dispatchers.IO) { preparer.prepare(toolId, tool) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        release(previous)
                        mutableState.value = RuntimeUiState.Error(
                            code = "RUNTIME_PREPARATION_FAILED",
                            message = "工具运行环境准备失败，请重试。",
                        )
                        return@collectLatest
                    }

                    when (prepared) {
                        is RuntimePreparationResult.Prepared -> {
                            val existing = previous as? RuntimeUiState.Ready
                            if (existing?.runtime?.matches(prepared.runtime) == true) {
                                mutableState.value = existing
                                return@collectLatest
                            }
                            release(previous)
                            mutableState.value = RuntimeUiState.Loading
                            when (
                                val permit = runtimePermitProvider.acquireRuntimePermit(
                                    toolId = toolId,
                                    awaitExistingRuntimeRelease = existing != null,
                                )
                            ) {
                                is RuntimeCreationPermitResult.Ready -> {
                                    mutableState.value = RuntimeUiState.Ready(prepared.runtime, permit.permit)
                                }

                                is RuntimeCreationPermitResult.Rejected -> {
                                    mutableState.value = permit.reason.toRuntimeError()
                                }
                            }
                        }

                        is RuntimePreparationResult.Failed -> {
                            release(previous)
                            mutableState.value = RuntimeUiState.Error(prepared.code.name, prepared.message)
                        }
                    }
                }
        }
    }

    fun retry() {
        release(mutableState.value)
        mutableState.value = RuntimeUiState.Loading
        retryGeneration.update(Int::inc)
    }

    fun mainEntryFailed(message: String) {
        release(mutableState.value)
        mutableState.value = RuntimeUiState.Error("ENTRY_LOAD_FAILED", message)
    }

    fun rendererGone() {
        release(mutableState.value)
        mutableState.value = RuntimeUiState.Error(
            code = "RENDERER_GONE",
            message = "工具渲染进程已退出，点击重试可重新打开。",
        )
    }

    fun runtimeCreationFailed(message: String) {
        release(mutableState.value)
        mutableState.value = RuntimeUiState.Error(
            code = "RUNTIME_WEBVIEW_CREATION_FAILED",
            message = message,
        )
    }

    private fun release(state: RuntimeUiState) {
        (state as? RuntimeUiState.Ready)?.creationPermit?.close()
    }

    private fun PreparedToolRuntime.matches(other: PreparedToolRuntime): Boolean =
        toolId == other.toolId && versionCode == other.versionCode && bundleRoot == other.bundleRoot

    private fun RuntimeDataCleanupResult.toRuntimeError(): RuntimeUiState.Error = when (this) {
        RuntimeDataCleanupResult.InUse -> RuntimeUiState.Error(
            code = "RUNTIME_PROFILE_BUSY",
            message = "工具运行环境正在切换，请稍后重试。",
        )

        RuntimeDataCleanupResult.ProviderUnsupported -> RuntimeUiState.Error(
            code = "RUNTIME_PROVIDER_UNSUPPORTED",
            message = "当前系统 WebView 不支持所需的隔离能力，请更新后重试。",
        )

        RuntimeDataCleanupResult.RecoveryDeferred,
        RuntimeDataCleanupResult.Failed,
        RuntimeDataCleanupResult.Cleared,
        RuntimeDataCleanupResult.AlreadyAbsent,
        -> RuntimeUiState.Error(
            code = "RUNTIME_PROFILE_UNAVAILABLE",
            message = "工具运行环境暂时不可用，请重试。",
        )
    }

    override fun onCleared() {
        release(mutableState.value)
    }
}
