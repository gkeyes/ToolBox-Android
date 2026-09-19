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
    private val layoutChanges = Channel<(CatalogLayout, Set<String>) -> CatalogLayout>(Channel.UNLIMITED)
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
            for (change in layoutChanges) {
                try {
                    if (layoutRepository?.update(change) !is DataResult.Success) {
                        showFailure("CATALOG_LAYOUT_WRITE", "收藏或分组未保存，请重试。")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { showFailure("CATALOG_LAYOUT_WRITE", "收藏或分组未保存，请重试。") }
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
            is CatalogAction.SetSort -> changeLayout { layout, _ -> layout.copy(sort = action.sort) }
            is CatalogAction.SetFavorite -> changeLayout { layout, installed ->
                if (action.toolId in installed || !action.selected) layout.favorite(action.toolId, action.selected) else layout
            }
            is CatalogAction.SaveGroup -> if (action.name.isNotBlank()) {
                val id = action.groupId ?: UUID.randomUUID().toString()
                changeLayout { layout, installed ->
                    val existing = layout.groups.firstOrNull { it.id == id }
                    val next = CatalogGroup(id, action.name.trim(), action.members.filter(installed::contains).distinct(), existing?.expanded ?: true)
                    when {
                        existing != null -> layout.copy(groups = layout.groups.map { if (it.id == id) next else it })
                        action.groupId == null -> layout.copy(groups = layout.groups + next)
                        else -> layout // A deleted group is not resurrected by a stale editor.
                    }
                }
            }
            is CatalogAction.CreateGroup -> if (action.name.isNotBlank()) {
                val id = UUID.randomUUID().toString()
                changeLayout { layout, _ -> layout.copy(groups = layout.groups + CatalogGroup(id, action.name.trim())) }
            }
            is CatalogAction.RenameGroup -> if (action.name.isNotBlank()) changeLayout { layout, _ ->
                layout.copy(groups = layout.groups.map { if (it.id == action.groupId) it.copy(name = action.name.trim()) else it })
            }
            is CatalogAction.DeleteGroup -> changeLayout { layout, _ -> layout.copy(groups = layout.groups.filterNot { it.id == action.groupId }) }
            is CatalogAction.SetGroupMembership -> changeLayout { layout, installed ->
                if (action.toolId in installed || !action.selected) layout.groupMembership(action.groupId, action.toolId, action.selected) else layout
            }
            is CatalogAction.SetGroupExpanded -> changeLayout { layout, _ ->
                layout.copy(groups = layout.groups.map { if (it.id == action.groupId) it.copy(expanded = action.expanded) else it })
            }
            is CatalogAction.MoveFavorite -> changeLayout { layout, _ -> layout.moveFavorite(action.toolId, action.offset) }
            is CatalogAction.MoveGroup -> changeLayout { layout, _ -> layout.moveGroup(action.groupId, action.offset) }
            is CatalogAction.MoveMember -> changeLayout { layout, _ -> layout.moveMember(action.groupId, action.toolId, action.offset) }
            is CatalogAction.RequestRuntimeLaunch -> open(action.toolId)
            is CatalogAction.RequestUninstall -> requestUninstall(action.toolId)
            CatalogAction.CancelUninstall -> update { it.copy(uninstallConfirmation = null) }
            CatalogAction.ConfirmUninstall -> confirmUninstall()
            CatalogAction.DismissFeedback -> update { it.copy(feedback = null) }
        }
    }

    private fun changeLayout(change: (CatalogLayout, Set<String>) -> CatalogLayout) {
        if (state.value.isLoaded) layoutChanges.trySend(change)
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
