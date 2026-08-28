package io.toolbox.host.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogOrganizationRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailure
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailureCode
import io.toolbox.tool.packagekit.lifecycle.RecoveryLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.ToolPackageLifecycle
import io.toolbox.tool.packagekit.lifecycle.UninstallLifecycleResult
import io.toolbox.tool.runtime.RuntimeDataCleaner
import io.toolbox.tool.runtime.RuntimeDataCleanupExecution
import io.toolbox.tool.runtime.RuntimeDataCleanupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CatalogViewModel(
    private val catalog: CatalogRepository,
    private val organization: CatalogOrganizationRepository,
    private val packageLifecycle: ToolPackageLifecycle,
    private val runtimeDataCleaner: RuntimeDataCleaner,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()

    private val mutableNavigation = MutableSharedFlow<CatalogNavigationIntent>(extraBufferCapacity = 1)
    val navigation: SharedFlow<CatalogNavigationIntent> = mutableNavigation.asSharedFlow()

    init {
        viewModelScope.launch {
            observeCatalog()
                .catch {
                    updateState {
                        it.copy(
                            isLoaded = true,
                            feedback = CatalogFeedback.Failure(
                                operation = CatalogOperation.RECOVERY,
                                code = "CATALOG_UNAVAILABLE",
                                message = "已安装工具暂时无法读取，请稍后重试。",
                            ),
                        )
                    }
                    emit(emptyList())
                }
                .collect { tools ->
                    updateState { current ->
                        val availableIds = tools.mapTo(hashSetOf(), CatalogTool::toolId)
                        current.copy(
                            isLoaded = true,
                            tools = tools,
                            selectedToolId = current.selectedToolId?.takeIf(availableIds::contains),
                            uninstallConfirmation = current.uninstallConfirmation
                                ?.takeIf { it.toolId in availableIds },
                        )
                    }
                }
        }
    }

    fun dispatch(action: CatalogAction) {
        when (action) {
            is CatalogAction.SetQuery -> updateState { it.copy(query = action.query) }
            is CatalogAction.SetCategoryFilter -> updateState { it.copy(categoryFilter = action.categoryId) }
            is CatalogAction.SetSort -> updateState { it.copy(sort = action.sort) }
            is CatalogAction.SelectDetails -> selectDetails(action.toolId)
            is CatalogAction.TogglePinned -> togglePinned(action.toolId)
            is CatalogAction.SetCategory -> setCategory(action.toolId, action.categoryId)
            is CatalogAction.RequestRuntimeLaunch -> requestRuntimeLaunch(action.toolId)
            is CatalogAction.RequestUninstall -> requestUninstall(action.toolId)
            CatalogAction.CancelUninstall -> updateState { it.copy(uninstallConfirmation = null) }
            CatalogAction.ConfirmUninstall -> confirmUninstall()
            CatalogAction.RecoverPendingMutation -> recoverPendingMutation()
            CatalogAction.DismissFeedback -> updateState { it.copy(feedback = null) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCatalog(): Flow<List<CatalogTool>> = catalog.observeTools().flatMapLatest { installedTools ->
        if (installedTools.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                installedTools.map { tool ->
                    catalog.observeVersions(tool.metadata.id).mapToCatalogTool(tool)
                },
            ) { tools -> tools.toList() }
        }
    }

    private fun Flow<List<ToolVersion>>.mapToCatalogTool(tool: InstalledTool): Flow<CatalogTool> =
        map { versions ->
            val activeVersion = versions.firstOrNull { it.versionCode == tool.activeVersionCode }
            CatalogTool(
                toolId = tool.metadata.id,
                name = tool.metadata.name,
                signatureState = tool.metadata.signatureState,
                activeVersionCode = activeVersion?.versionCode,
                activeVersionName = activeVersion?.version,
                bundleBytes = activeVersion?.bundleBytes,
                launchState = activeVersion?.launchState,
                lastOpenedAt = tool.lastOpenedAt,
                categoryId = tool.metadata.categoryId,
                pinnedOrder = tool.metadata.pinnedOrder,
            )
        }

    private fun selectDetails(toolId: String?) {
        updateState { current ->
            current.copy(selectedToolId = toolId?.takeIf { candidate -> current.tools.any { it.toolId == candidate } })
        }
    }

    private fun togglePinned(toolId: String) = launchOperation(CatalogOperation.PIN) {
        val tool = state.value.tools.firstOrNull { it.toolId == toolId }
            ?: return@launchOperation showMissingTool(CatalogOperation.PIN)
        val targetOrder = if (tool.pinnedOrder == null) {
            state.value.tools.mapNotNull(CatalogTool::pinnedOrder).maxOrNull()?.plus(1) ?: 0
        } else {
            null
        }
        when (val result = organization.setPinnedOrder(toolId, targetOrder)) {
            is DataResult.Success -> Unit
            is DataResult.Failure -> showDataFailure(CatalogOperation.PIN, result)
        }
    }

    private fun setCategory(toolId: String, categoryId: String?) = launchOperation(CatalogOperation.CATEGORY) {
        if (state.value.tools.none { it.toolId == toolId }) {
            return@launchOperation showMissingTool(CatalogOperation.CATEGORY)
        }
        when (val result = organization.setCategory(toolId, categoryId)) {
            is DataResult.Success -> Unit
            is DataResult.Failure -> showDataFailure(CatalogOperation.CATEGORY, result)
        }
    }

    private fun requestRuntimeLaunch(toolId: String) = launchOperation(CatalogOperation.OPEN) {
        if (state.value.tools.none { it.toolId == toolId }) {
            return@launchOperation showMissingTool(CatalogOperation.OPEN)
        }
        when (val result = organization.recordOpened(toolId, now())) {
            is DataResult.Success -> mutableNavigation.emit(CatalogNavigationIntent.RequestRuntimeLaunch(toolId))
            is DataResult.Failure -> showDataFailure(CatalogOperation.OPEN, result)
        }
    }

    private fun requestUninstall(toolId: String) {
        val tool = state.value.tools.firstOrNull { it.toolId == toolId }
        if (tool == null) {
            showMissingTool(CatalogOperation.UNINSTALL)
        } else {
            updateState { it.copy(uninstallConfirmation = UninstallConfirmation(tool.toolId, tool.name)) }
        }
    }

    private fun confirmUninstall() {
        val confirmation = state.value.uninstallConfirmation ?: return
        updateState { it.copy(uninstallConfirmation = null) }
        launchOperation(CatalogOperation.UNINSTALL) {
            when (
                val execution = runtimeDataCleaner.clearThenRun(confirmation.toolId) {
                    packageLifecycle.uninstall(confirmation.toolId)
                }
            ) {
                is RuntimeDataCleanupExecution.Rejected -> when (execution.reason) {
                    RuntimeDataCleanupResult.InUse -> return@launchOperation showRuntimeCleanupFailure(
                    code = "RUNTIME_IN_USE",
                    message = "工具仍在运行，关闭运行页面后再卸载。",
                )
                    RuntimeDataCleanupResult.ProviderUnsupported -> return@launchOperation showRuntimeCleanupFailure(
                    code = "RUNTIME_PROVIDER_UNSUPPORTED",
                    message = "当前 WebView 版本无法安全清理工具数据，请更新系统 WebView 后重试。",
                )
                    RuntimeDataCleanupResult.RecoveryDeferred -> return@launchOperation showRuntimeCleanupFailure(
                    code = "RUNTIME_PROFILE_RECOVERY_DEFERRED",
                    message = "历史专用运行数据正等待受支持的系统 WebView 清理，当前未卸载工具。",
                )
                    RuntimeDataCleanupResult.Failed,
                    RuntimeDataCleanupResult.Cleared,
                    RuntimeDataCleanupResult.AlreadyAbsent,
                    -> return@launchOperation showRuntimeCleanupFailure(
                    code = "RUNTIME_DATA_CLEAR_FAILED",
                    message = "工具运行数据暂时无法清理，未卸载工具，请重试。",
                )
                }
                is RuntimeDataCleanupExecution.Completed -> when (val result = execution.value) {
                    is UninstallLifecycleResult.Uninstalled,
                    is UninstallLifecycleResult.AlreadyAbsent,
                    -> updateState {
                        it.copy(feedback = CatalogFeedback.Completed("已提交卸载 ${confirmation.toolName}；工具列表会随目录同步更新。"))
                    }
                    is UninstallLifecycleResult.CommittedRecoveryPending -> showRecoveryPending(
                        CatalogOperation.UNINSTALL,
                        result.reason,
                    )
                    is UninstallLifecycleResult.Failed -> showLifecycleFailure(CatalogOperation.UNINSTALL, result.reason)
                }
            }
        }
    }

    private fun showRuntimeCleanupFailure(code: String, message: String) {
        updateState {
            it.copy(
                feedback = CatalogFeedback.Failure(
                    operation = CatalogOperation.UNINSTALL,
                    code = code,
                    message = message,
                ),
            )
        }
    }

    private fun recoverPendingMutation() = launchOperation(CatalogOperation.RECOVERY) {
        when (val result = packageLifecycle.recover()) {
            RecoveryLifecycleResult.Recovered -> updateState {
                it.copy(feedback = CatalogFeedback.Completed("包目录恢复已完成，工具列表会随目录同步更新。"))
            }
            is RecoveryLifecycleResult.Pending -> showRecoveryPending(CatalogOperation.RECOVERY, result.reason)
        }
    }

    private fun launchOperation(operation: CatalogOperation, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateState {
                    it.copy(
                        feedback = CatalogFeedback.Failure(
                            operation = operation,
                            code = "UNEXPECTED_FAILURE",
                            message = "操作未完成，请重试。",
                        ),
                    )
                }
            }
        }
    }

    private fun showMissingTool(operation: CatalogOperation) {
        updateState {
            it.copy(
                feedback = CatalogFeedback.Failure(
                    operation = operation,
                    code = "TOOL_NOT_FOUND",
                    message = "该工具已不在已安装目录中。",
                ),
            )
        }
    }

    private fun showDataFailure(operation: CatalogOperation, failure: DataResult.Failure) {
        updateState {
            it.copy(
                feedback = CatalogFeedback.Failure(
                    operation = operation,
                    code = failure.code(),
                    message = failure.message(),
                ),
            )
        }
    }

    private fun showLifecycleFailure(operation: CatalogOperation, failure: LifecycleFailure) {
        updateState {
            it.copy(
                feedback = CatalogFeedback.Failure(
                    operation = operation,
                    code = failure.code.name,
                    message = failure.userMessage(),
                ),
            )
        }
    }

    private fun showRecoveryPending(operation: CatalogOperation, failure: LifecycleFailure) {
        updateState {
            it.copy(
                feedback = CatalogFeedback.RecoveryPending(
                    operation = operation,
                    code = failure.code,
                    message = failure.userMessage(),
                ),
            )
        }
    }

    private fun updateState(transform: (CatalogUiState) -> CatalogUiState) {
        mutableState.update(transform)
    }
}

private fun DataResult.Failure.code(): String = when (this) {
    is DataResult.Failure.InvalidInput -> "INVALID_INPUT"
    is DataResult.Failure.DuplicateVersion -> "DUPLICATE_VERSION"
    is DataResult.Failure.DuplicateSourceSession -> "DUPLICATE_SOURCE_SESSION"
    is DataResult.Failure.NonMonotonicVersion -> "NON_MONOTONIC_VERSION"
    is DataResult.Failure.SignatureContinuityViolation -> "SIGNATURE_CONTINUITY"
    is DataResult.Failure.UnsignedPersistentGrant -> "UNSIGNED_PERSISTENT_GRANT"
    is DataResult.Failure.LifecycleConflict -> "LIFECYCLE_CONFLICT"
    is DataResult.Failure.NotFound -> "NOT_FOUND"
    is DataResult.Failure.QuotaExceeded -> "QUOTA_EXCEEDED"
    is DataResult.Failure.StorageFailure -> "STORAGE_FAILURE"
}

private fun DataResult.Failure.message(): String = when (this) {
    is DataResult.Failure.InvalidInput -> "输入不符合允许范围。"
    is DataResult.Failure.NotFound -> "该工具已不在已安装目录中。"
    is DataResult.Failure.StorageFailure -> "目录存储暂时不可用，请稍后重试。"
    else -> "目录状态已变化，请刷新后重试。"
}

private fun LifecycleFailure.userMessage(): String = when (code) {
    LifecycleFailureCode.BUSY -> "另一个包操作正在进行，请稍后重试。"
    LifecycleFailureCode.RECOVERY_REQUIRED -> "包目录需要恢复；请先完成恢复。"
    LifecycleFailureCode.INSPECTION_REJECTED -> "审核会话已失效，无法继续此操作。"
    LifecycleFailureCode.GRANT_PLAN_INVALID -> "授权计划已失效，请重新审核。"
    LifecycleFailureCode.CATALOG_REJECTED -> "目录拒绝了此操作，请刷新后重试。"
    LifecycleFailureCode.FILE_COLLISION -> "目标版本目录已存在，未覆盖现有工具。"
    LifecycleFailureCode.FILE_INTEGRITY_MISMATCH -> "已审核的工具文件发生变化，操作已阻止。"
    LifecycleFailureCode.STORAGE_FAILURE -> "私有存储暂时不可用，请稍后重试。"
}
