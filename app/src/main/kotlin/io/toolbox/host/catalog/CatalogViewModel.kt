package io.toolbox.host.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogLayoutRepository
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.CatalogLayout
import io.toolbox.core.data.CatalogGroup
import java.util.UUID
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogOrganizationRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataResult
import io.toolbox.host.HostDeleteResult
import io.toolbox.host.HostPackageOperations
import io.toolbox.host.HostTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    private val settings: HostSettingsRepository? = null,
    private val layoutRepository: CatalogLayoutRepository? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()

    private val queries = MutableStateFlow("")
    private class LayoutWrite(
        val operationId: String?,
        val change: (CatalogLayout, Set<String>) -> CatalogLayout,
    )
    private val layoutChanges = Channel<LayoutWrite>(Channel.UNLIMITED)
    private val trackedLayoutWrites = mutableMapOf<String, LayoutWrite>()
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
            combine(catalog.observeCatalogProjection().map { entries -> entries.map(CatalogEntry::toCatalogTool) }, settings?.settings ?: flowOf(HostSettings()), queries) { tools, host, query ->
                CatalogUiState(layout = host.catalogLayout, query = query, isSearching = query.trim().isNotEmpty())
                    .withCatalogTools(tools)
            }.flowOn(Dispatchers.Default)
                .catch {
                    update { state ->
                        state.copy(
                            isLoaded = true,
                            feedback = CatalogFeedback.Failure("CATALOG_UNAVAILABLE", "工具列表暂时无法读取。"),
                        )
                    }
                }
                .collect { projection ->
                    if (projection.query != queries.value) return@collect
                    update { current -> current.copy(
                        tools = projection.tools, visibleTools = projection.visibleTools,
                        recentTools = projection.recentTools, layout = projection.layout,
                        query = projection.query, isSearching = projection.isSearching, isLoaded = true,
                        uninstallConfirmation = current.uninstallConfirmation?.takeIf { confirmation ->
                            projection.tools.any { it.toolId == confirmation.toolId }
                        },
                    ) }
                    pendingRuntimeLaunchToolId?.let { toolId ->
                        pendingRuntimeLaunchToolId = null
                        if (projection.tools.any { it.toolId == toolId }) openInstalled(toolId)
                    }
                }
        }
        viewModelScope.launch {
            for (write in layoutChanges) {
                try {
                    val status = if (layoutRepository?.update(write.change) is DataResult.Success) {
                        CatalogLayoutWriteStatus.Succeeded
                    } else {
                        CatalogLayoutWriteStatus.Failed("CATALOG_LAYOUT_WRITE", "布局未保存，请重试。")
                    }
                    completeLayoutWrite(write, status)
                } catch (cancelled: CancellationException) {
                    completeLayoutWrite(write, CatalogLayoutWriteStatus.Failed("CATALOG_LAYOUT_CANCELLED", "保存已取消。"))
                    throw cancelled
                } catch (_: Exception) {
                    completeLayoutWrite(write, CatalogLayoutWriteStatus.Failed("CATALOG_LAYOUT_WRITE", "布局未保存，请重试。"))
                }
            }
        }
    }

    fun dispatch(action: CatalogAction) {
        when (action) {
            is CatalogAction.SetQuery -> {
                queries.value = action.query
                // Text edits are synchronous; filtering and collation stay on Default.
                update { it.copy(query = action.query, isSearching = action.query.isNotBlank()) }
            }
            is CatalogAction.SetSort -> changeLayout(action.operationId) { layout, _ -> layout.copy(sort = action.sort) }
            is CatalogAction.SetFavorite -> changeLayout(action.operationId) { layout, installed ->
                if (action.toolId in installed || !action.selected) layout.favorite(action.toolId, action.selected) else layout
            }
            is CatalogAction.SaveGroup -> if (action.name.isNotBlank()) {
                val id = action.groupId ?: UUID.randomUUID().toString()
                changeLayout(action.operationId) { layout, installed ->
                    val existing = layout.groups.firstOrNull { it.id == id }
                    val next = CatalogGroup(id, action.name.trim(), action.members.filter(installed::contains).distinct(), existing?.expanded ?: true)
                    when {
                        existing != null -> layout.copy(groups = layout.groups.map { if (it.id == id) next else it })
                        action.groupId == null -> layout.copy(groups = layout.groups + next)
                        // Legacy callers ignore a stale edit; tracked editors need a failed save.
                        else -> if (action.operationId == null) layout else error("CATALOG_GROUP_MISSING")
                    }
                }
            } else rejectGroupName(action.operationId)
            is CatalogAction.CreateGroup -> if (action.name.isNotBlank()) {
                val id = UUID.randomUUID().toString()
                changeLayout(action.operationId) { layout, _ -> layout.copy(groups = layout.groups + CatalogGroup(id, action.name.trim())) }
            } else rejectGroupName(action.operationId)
            is CatalogAction.RenameGroup -> if (action.name.isNotBlank()) changeLayout(action.operationId) { layout, _ ->
                layout.copy(groups = layout.groups.map { if (it.id == action.groupId) it.copy(name = action.name.trim()) else it })
            } else rejectGroupName(action.operationId)
            is CatalogAction.DeleteGroup -> changeLayout(action.operationId) { layout, _ -> layout.copy(groups = layout.groups.filterNot { it.id == action.groupId }) }
            is CatalogAction.SetGroupMembership -> changeLayout(action.operationId) { layout, installed ->
                if (action.toolId in installed || !action.selected) layout.groupMembership(action.groupId, action.toolId, action.selected) else layout
            }
            is CatalogAction.SetGroupExpanded -> changeLayout(action.operationId) { layout, _ ->
                layout.copy(groups = layout.groups.map { if (it.id == action.groupId) it.copy(expanded = action.expanded) else it })
            }
            is CatalogAction.MoveFavorite -> changeLayout(action.operationId) { layout, _ -> layout.moveFavorite(action.toolId, action.offset) }
            is CatalogAction.MoveGroup -> changeLayout(action.operationId) { layout, _ -> layout.moveGroup(action.groupId, action.offset) }
            is CatalogAction.MoveMember -> changeLayout(action.operationId) { layout, _ -> layout.moveMember(action.groupId, action.toolId, action.offset) }
            is CatalogAction.ForgetLayoutWrite -> {
                trackedLayoutWrites.remove(action.operationId)
                update { it.copy(layoutWrites = it.layoutWrites - action.operationId) }
            }
            is CatalogAction.RequestRuntimeLaunch -> open(action.toolId)
            is CatalogAction.RequestUninstall -> requestUninstall(action.toolId)
            CatalogAction.CancelUninstall -> update { it.copy(uninstallConfirmation = null) }
            CatalogAction.ConfirmUninstall -> confirmUninstall()
            CatalogAction.DismissFeedback -> update { it.copy(feedback = null) }
        }
    }

    private fun changeLayout(operationId: String?, change: (CatalogLayout, Set<String>) -> CatalogLayout) {
        if (operationId != null && state.value.layoutWrites[operationId] == CatalogLayoutWriteStatus.Writing) return
        val write = LayoutWrite(operationId, change)
        if (operationId != null) {
            trackedLayoutWrites[operationId] = write
            update { it.copy(layoutWrites = it.layoutWrites + (operationId to CatalogLayoutWriteStatus.Writing)) }
        }
        if (!state.value.isLoaded) {
            if (operationId != null) completeLayoutWrite(write,
                CatalogLayoutWriteStatus.Failed("CATALOG_UNAVAILABLE", "工具列表尚未读取完成，请稍后重试。"))
        } else if (layoutChanges.trySend(write).isFailure) {
            completeLayoutWrite(write, CatalogLayoutWriteStatus.Failed("CATALOG_LAYOUT_WRITE", "布局未保存，请重试。"))
        }
    }

    private fun rejectGroupName(operationId: String?) {
        if (operationId == null || state.value.layoutWrites[operationId] == CatalogLayoutWriteStatus.Writing) return
        trackedLayoutWrites.remove(operationId)
        update { it.copy(layoutWrites = it.layoutWrites +
            (operationId to CatalogLayoutWriteStatus.Failed("CATALOG_GROUP_NAME", "请输入分组名称。"))) }
    }

    private fun completeLayoutWrite(write: LayoutWrite, status: CatalogLayoutWriteStatus) {
        val operationId = write.operationId
        if (operationId == null) {
            if (status is CatalogLayoutWriteStatus.Failed && status.code != "CATALOG_LAYOUT_CANCELLED") {
                showFailure(status.code, "收藏或分组未保存，请重试。")
            }
            return
        }
        // Forget detaches the editor; a retry with the same ID owns a different submission.
        if (trackedLayoutWrites[operationId] !== write) return
        trackedLayoutWrites.remove(operationId)
        update { it.copy(layoutWrites = it.layoutWrites + (operationId to status)) }
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
    installedAt = installedAt,
    nameSortKey = catalogNameSortKey(name),
)

/** Android's ICU transliterator handles Han names without a bundled dictionary or network access. */
internal fun catalogNameSortKey(name: String): String =
    checkNotNull(catalogTransliterator.get()).transliterate(name).replace(catalogNameWhitespace, "")

private val catalogTransliterator = ThreadLocal.withInitial { android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower") }

private val catalogNameWhitespace = Regex("\\s+")
