package io.toolbox.host.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogOrganizationRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataResult
import io.toolbox.host.HostDeleteResult
import io.toolbox.host.HostPackageOperations
import io.toolbox.host.HostTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CatalogViewModel(
    private val catalog: CatalogRepository,
    private val organization: CatalogOrganizationRepository,
    private val packageOperations: HostPackageOperations,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()

    private val queuedRuntimeLaunches = mutableSetOf<String>()
    private val recordOpenedMutex = Mutex()
    private val mutableNavigation = Channel<CatalogNavigationIntent>(Channel.BUFFERED)
    val navigation = mutableNavigation.receiveAsFlow().filter { intent ->
        when (intent) {
            is CatalogNavigationIntent.RequestRuntimeLaunch -> {
                queuedRuntimeLaunches.remove(intent.toolId)
                // A delayed collector must not navigate to a tool already removed from the catalog.
                state.value.tools.any { it.toolId == intent.toolId }
            }
        }
    }
    private var pendingRuntimeLaunchToolId: String? = null

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
                    val tools = HostTrace.bestEffortSection("host.catalog.publish") {
                        entries.map(CatalogEntry::toCatalogTool).also { tools ->
                            update { state -> state.withCatalogTools(tools).copy(isLoaded = true) }
                        }
                    }
                    pendingRuntimeLaunchToolId?.let { toolId ->
                        pendingRuntimeLaunchToolId = null
                        if (tools.any { it.toolId == toolId }) openInstalled(toolId)
                    }
                }
        }
    }

    fun dispatch(action: CatalogAction) {
        when (action) {
            is CatalogAction.SetQuery -> update { it.withCatalogQuery(action.query) }
            is CatalogAction.RequestRuntimeLaunch -> open(action.toolId)
            is CatalogAction.RequestUninstall -> requestUninstall(action.toolId)
            CatalogAction.CancelUninstall -> update { it.copy(uninstallConfirmation = null) }
            CatalogAction.ConfirmUninstall -> confirmUninstall()
            CatalogAction.DismissFeedback -> update { it.copy(feedback = null) }
        }
    }

    private fun open(toolId: String) {
        if (!state.value.isLoaded) {
            pendingRuntimeLaunchToolId = toolId
            return
        }
        if (state.value.tools.none { it.toolId == toolId }) return
        openInstalled(toolId)
    }

    private fun openInstalled(toolId: String) {
        // Coalesce taps until navigation consumes the request, not until statistics finish.
        if (!queuedRuntimeLaunches.add(toolId)) return
        val requestedAt = now()
        viewModelScope.launch {
            mutableNavigation.send(CatalogNavigationIntent.RequestRuntimeLaunch(toolId))
            // lastOpenedAt means an accepted open request, not successful WebView readiness.
            // Runtime preparation still performs the authoritative installation/security checks.
            try {
                val result = HostTrace.bestEffortAsyncSection("tool.recordOpened") {
                    // A slower earlier write must not overwrite a later request's timestamp.
                    recordOpenedMutex.withLock { organization.recordOpened(toolId, requestedAt) }
                }
                when (result) {
                    is DataResult.Success -> update { current ->
                        if ((current.feedback as? CatalogFeedback.Failure)?.code == "RECENT_UPDATE_FAILED") {
                            current.copy(feedback = null)
                        } else {
                            current
                        }
                    }
                    is DataResult.Failure -> showRecentUpdateFailure()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showRecentUpdateFailure()
            }
        }
    }

    private fun showRecentUpdateFailure() {
        showFailure("RECENT_UPDATE_FAILED", "最近使用记录未保存，可稍后重新打开工具重试。")
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
