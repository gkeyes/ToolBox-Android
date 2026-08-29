package io.toolbox.host.runtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.SecurityProfile
import io.toolbox.host.HostTrace
import io.toolbox.tool.runtime.PreparedToolRuntime
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RuntimeUiState {
    data object Loading : RuntimeUiState
    data class Ready(
        val runtime: PreparedToolRuntime,
        val creationPermit: RuntimeCreationPermit,
        val entryLoaded: Boolean,
        val statusMessage: String,
    ) : RuntimeUiState
    data class Error(
        val code: String,
        val message: String,
    ) : RuntimeUiState
}

class RuntimeViewModel(
    private val toolId: String,
    private val catalog: CatalogRepository,
    private val lifecycle: CatalogLifecycleRepository,
    private val preparer: ToolRuntimePreparer,
    private val runtimeProfileManager: RuntimePermitProvider,
) : ViewModel() {
    private val retryGeneration = MutableStateFlow(0)
    private val mutableState = MutableStateFlow<RuntimeUiState>(RuntimeUiState.Loading)
    val state: StateFlow<RuntimeUiState> = mutableState.asStateFlow()
    private var stabilizationRequested: Pair<String, Int>? = null

    init {
        viewModelScope.launch {
            combine(
                catalog.observeTool(toolId),
                catalog.observeVersions(toolId),
                retryGeneration,
            ) { tool, versions, _ -> tool to versions }
                .catch {
                    emit(null to emptyList())
                }
                .collectLatest { (tool, versions) ->
                    val previous = mutableState.value
                    val activeCode = tool?.activeVersionCode
                    val sameRuntime = previous is RuntimeUiState.Ready &&
                        previous.runtime.toolId == toolId &&
                        previous.runtime.versionCode == activeCode
                    if (!sameRuntime) {
                        (previous as? RuntimeUiState.Ready)?.creationPermit?.close()
                        mutableState.value = RuntimeUiState.Loading
                    }
                    val result = try {
                        withContext(Dispatchers.IO) {
                            HostTrace.bestEffortSection("runtime.prepare") {
                                preparer.prepare(toolId, tool, versions)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        (previous as? RuntimeUiState.Ready)?.creationPermit?.close()
                        mutableState.value = RuntimeUiState.Error(
                            code = "RUNTIME_PREPARATION_FAILED",
                            message = "工具运行环境准备失败，请重试。",
                        )
                        return@collectLatest
                    }
                    mutableState.value = when (result) {
                        is RuntimePreparationResult.Prepared -> {
                            val creationPermit = if (sameRuntime) {
                                previous.creationPermit
                            } else {
                                when (
                                    val permitResult = runtimeProfileManager.acquireRuntimePermit(
                                        toolId = toolId,
                                        awaitExistingRuntimeRelease = previous is RuntimeUiState.Ready ||
                                            previous is RuntimeUiState.Error,
                                    )
                                ) {
                                    is RuntimeCreationPermitResult.Ready -> permitResult.permit
                                    is RuntimeCreationPermitResult.Rejected -> {
                                        mutableState.value = permitResult.reason.toRuntimeError()
                                        return@collectLatest
                                    }
                                }
                            }
                            RuntimeUiState.Ready(
                                runtime = result.runtime,
                                creationPermit = creationPermit,
                                entryLoaded = sameRuntime && previous.entryLoaded,
                                statusMessage = result.runtime.securityProfile.compactLabel(),
                            )
                        }
                        is RuntimePreparationResult.Failed -> {
                            (previous as? RuntimeUiState.Ready)?.creationPermit?.close()
                            RuntimeUiState.Error(code = result.code.name, message = result.message)
                        }
                    }
                }
        }
    }

    fun retry() {
        stabilizationRequested = null
        retryGeneration.update(Int::inc)
    }

    fun mainEntryLoaded(runtime: PreparedToolRuntime) {
        mutableState.update { current ->
            if (current is RuntimeUiState.Ready && current.runtime.matches(runtime)) {
                current.copy(
                    entryLoaded = true,
                    statusMessage = if (current.runtime.launchState == LaunchState.PENDING) {
                        "已加载，等待确认可用"
                    } else {
                        "已加载"
                    },
                )
            } else {
                current
            }
        }
    }

    fun confirmReadyVersion() {
        val current = mutableState.value as? RuntimeUiState.Ready ?: return
        val runtime = current.runtime
        if (!current.entryLoaded || runtime.launchState != LaunchState.PENDING) return
        val versionKey = runtime.toolId to runtime.versionCode
        if (stabilizationRequested == versionKey) return
        stabilizationRequested = versionKey
        viewModelScope.launch {
            try {
                when (lifecycle.markActiveVersionStable(runtime.toolId, runtime.versionCode)) {
                    is DataResult.Success -> Unit
                    is DataResult.Failure -> mutableState.update { current ->
                        if (stabilizationRequested == versionKey) stabilizationRequested = null
                        if (current is RuntimeUiState.Ready && current.runtime.versionCode == runtime.versionCode) {
                            current.copy(statusMessage = "确认失败，稳定状态待重试")
                        } else {
                            current
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (stabilizationRequested == versionKey) stabilizationRequested = null
                mutableState.update { current ->
                    if (current is RuntimeUiState.Ready && current.runtime.versionCode == runtime.versionCode) {
                        current.copy(statusMessage = "确认失败，稳定状态待重试")
                    } else {
                        current
                    }
                }
            }
        }
    }

    private fun SecurityProfile.compactLabel(): String = when (this) {
        SecurityProfile.STRICT -> "严格策略"
        SecurityProfile.COMPAT -> "兼容策略"
    }

    private fun RuntimeDataCleanupResult.toRuntimeError(): RuntimeUiState.Error = when (this) {
        RuntimeDataCleanupResult.InUse -> RuntimeUiState.Error(
            code = "RUNTIME_PROFILE_BUSY",
            message = "工具运行环境正在清理，请稍后重试。",
        )
        RuntimeDataCleanupResult.ProviderUnsupported -> RuntimeUiState.Error(
            code = "RUNTIME_PROVIDER_UNSUPPORTED",
            message = "当前系统 WebView 无法安全隔离或清理工具数据，请更新后重试。",
        )
        RuntimeDataCleanupResult.RecoveryDeferred -> RuntimeUiState.Error(
            code = "RUNTIME_PROFILE_RECOVERY_DEFERRED",
            message = "历史专用运行数据仍待系统 WebView 清理，请更新后重试。",
        )
        RuntimeDataCleanupResult.Failed,
        RuntimeDataCleanupResult.Cleared,
        RuntimeDataCleanupResult.AlreadyAbsent,
        -> RuntimeUiState.Error(
            code = "RUNTIME_PROFILE_UNAVAILABLE",
            message = "工具运行数据准备失败，请重试。",
        )
    }

    fun mainEntryFailed(message: String) {
        stabilizationRequested = null
        (mutableState.value as? RuntimeUiState.Ready)?.creationPermit?.close()
        mutableState.value = RuntimeUiState.Error("ENTRY_LOAD_FAILED", message)
    }

    fun rendererGone() {
        stabilizationRequested = null
        (mutableState.value as? RuntimeUiState.Ready)?.creationPermit?.close()
        mutableState.value = RuntimeUiState.Error(
            code = "RENDERER_GONE",
            message = "工具渲染进程已退出，点击重试可重新打开。",
        )
    }

    fun runtimeCreationFailed(message: String) {
        stabilizationRequested = null
        (mutableState.value as? RuntimeUiState.Ready)?.creationPermit?.close()
        mutableState.value = RuntimeUiState.Error(
            code = "RUNTIME_WEBVIEW_CREATION_FAILED",
            message = message,
        )
    }

    private fun PreparedToolRuntime.matches(other: PreparedToolRuntime): Boolean =
        toolId == other.toolId && versionCode == other.versionCode

    override fun onCleared() {
        (mutableState.value as? RuntimeUiState.Ready)?.creationPermit?.close()
    }
}
