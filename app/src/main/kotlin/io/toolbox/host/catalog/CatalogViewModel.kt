package io.toolbox.host.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogOrganizationRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataResult
import io.toolbox.host.HostDeleteResult
import io.toolbox.host.HostPackageOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

internal class CatalogViewModel(
    private val catalog: CatalogRepository,
    private val organization: CatalogOrganizationRepository,
    private val packageOperations: HostPackageOperations,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()

    private val mutableNavigation = MutableSharedFlow<CatalogNavigationIntent>(extraBufferCapacity = 1)
    val navigation: SharedFlow<CatalogNavigationIntent> = mutableNavigation.asSharedFlow()

    init {
        viewModelScope.launch {
            catalog.observeCatalogProjection()
                .catch {
                    update { state ->
                        state.copy(
                            isLoaded = true,
                            feedback = CatalogFeedback.Failure("CATALOG_UNAVAILABLE", "工具列表暂时无法读取。"),
                        )
                    }
                }
                .collect { entries ->
                    update { state ->
                        val tools = entries.map(CatalogEntry::toCatalogTool)
                        state.copy(
                            isLoaded = true,
                            tools = tools,
                            uninstallConfirmation = state.uninstallConfirmation?.takeIf { confirmation ->
                                tools.any { it.toolId == confirmation.toolId }
                            },
                        )
                    }
                }
        }
    }

    fun dispatch(action: CatalogAction) {
        when (action) {
            is CatalogAction.SetQuery -> update { it.copy(query = action.query) }
            is CatalogAction.RequestRuntimeLaunch -> open(action.toolId)
            is CatalogAction.RequestUninstall -> requestUninstall(action.toolId)
            CatalogAction.CancelUninstall -> update { it.copy(uninstallConfirmation = null) }
            CatalogAction.ConfirmUninstall -> confirmUninstall()
            CatalogAction.DismissFeedback -> update { it.copy(feedback = null) }
        }
    }

    private fun open(toolId: String) {
        if (state.value.tools.none { it.toolId == toolId }) return
        viewModelScope.launch {
            when (organization.recordOpened(toolId, now())) {
                is DataResult.Success -> mutableNavigation.emit(CatalogNavigationIntent.RequestRuntimeLaunch(toolId))
                is DataResult.Failure -> showFailure("OPEN_FAILED", "工具暂时无法打开。")
            }
        }
    }

    private fun requestUninstall(toolId: String) {
        val tool = state.value.tools.firstOrNull { it.toolId == toolId } ?: return
        update { it.copy(uninstallConfirmation = UninstallConfirmation(tool.toolId, tool.name)) }
    }

    private fun confirmUninstall() {
        val confirmation = state.value.uninstallConfirmation ?: return
        update { it.copy(uninstallConfirmation = null) }
        viewModelScope.launch {
            try {
                when (val result = packageOperations.deleteTool(confirmation.toolId)) {
                    HostDeleteResult.Deleted,
                    HostDeleteResult.AlreadyAbsent,
                    -> update { it.copy(feedback = CatalogFeedback.Completed("${confirmation.toolName} 已删除")) }
                    is HostDeleteResult.Failed -> showFailure(result.code, result.message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFailure("DELETE_FAILED", "删除未完成，请重试。")
            }
        }
    }

    private fun showFailure(code: String, message: String) {
        update { it.copy(feedback = CatalogFeedback.Failure(code, message)) }
    }

    private fun update(transform: (CatalogUiState) -> CatalogUiState) {
        mutableState.value = transform(mutableState.value)
    }
}

private fun CatalogEntry.toCatalogTool() = CatalogTool(
    toolId = toolId,
    name = name,
    versionCode = versionCode,
    versionName = version,
    bundleBytes = bundleBytes,
    lastOpenedAt = lastOpenedAt,
)
