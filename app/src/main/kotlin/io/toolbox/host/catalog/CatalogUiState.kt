package io.toolbox.host.catalog

import androidx.compose.runtime.Immutable
import io.toolbox.core.data.CatalogLayout
import io.toolbox.core.data.CatalogSort
import java.util.Locale

@Immutable
data class CatalogTool(
    val toolId: String,
    val name: String,
    val versionCode: Int,
    val versionName: String,
    val bundleBytes: Long,
    val lastOpenedAt: Long?,
    val installedAt: Long = 0L,
    val nameSortKey: String = name.lowercase(Locale.ROOT),
)

@Immutable
data class UninstallConfirmation(val toolId: String, val toolName: String)

@Immutable
sealed interface CatalogFeedback {
    data class Completed(val message: String) : CatalogFeedback
    data class Failure(val code: String, val message: String) : CatalogFeedback
}

sealed interface CatalogNavigationIntent {
    data class RequestRuntimeLaunch(val toolId: String) : CatalogNavigationIntent
}

@Immutable
sealed interface CatalogLayoutWriteStatus {
    data object Writing : CatalogLayoutWriteStatus
    data object Succeeded : CatalogLayoutWriteStatus
    data class Failed(val code: String, val message: String) : CatalogLayoutWriteStatus
}

sealed interface CatalogAction {
    data class SetSort(val sort: CatalogSort, val operationId: String? = null) : CatalogAction
    data class SetFavorite(val toolId: String, val selected: Boolean, val operationId: String? = null) : CatalogAction
    data class CreateGroup(val name: String, val operationId: String? = null) : CatalogAction
    data class SaveGroup(val groupId: String?, val name: String, val members: List<String>, val operationId: String? = null) : CatalogAction
    data class RenameGroup(val groupId: String, val name: String, val operationId: String? = null) : CatalogAction
    data class DeleteGroup(val groupId: String, val operationId: String? = null) : CatalogAction
    data class SetGroupMembership(val groupId: String, val toolId: String, val selected: Boolean, val operationId: String? = null) : CatalogAction
    data class SetGroupExpanded(val groupId: String, val expanded: Boolean, val operationId: String? = null) : CatalogAction
    data class MoveFavorite(val toolId: String, val offset: Int, val operationId: String? = null) : CatalogAction
    data class MoveGroup(val groupId: String, val offset: Int, val operationId: String? = null) : CatalogAction
    data class MoveMember(val groupId: String, val toolId: String, val offset: Int, val operationId: String? = null) : CatalogAction
    /** Detaches operation feedback without cancelling an already submitted layout write. */
    data class ForgetLayoutWrite(val operationId: String) : CatalogAction
    data class SetQuery(val query: String) : CatalogAction
    data class RequestRuntimeLaunch(val toolId: String) : CatalogAction
    data class RequestUninstall(val toolId: String) : CatalogAction
    data object CancelUninstall : CatalogAction
    data object ConfirmUninstall : CatalogAction
    data object DismissFeedback : CatalogAction
}

data class CatalogUiState(
    val isLoaded: Boolean = false,
    val loadFailed: Boolean = false,
    val layout: CatalogLayout = CatalogLayout(),
    val tools: List<CatalogTool> = emptyList(),
    val visibleTools: List<CatalogTool> = emptyList(),
    val recentTools: List<CatalogTool> = emptyList(),
    val query: String = "",
    val isSearching: Boolean = false,
    val uninstallConfirmation: UninstallConfirmation? = null,
    val feedback: CatalogFeedback? = null,
    val layoutWrites: Map<String, CatalogLayoutWriteStatus> = emptyMap(),
)

internal fun CatalogUiState.withCatalogTools(values: List<CatalogTool>): CatalogUiState =
    copy(
        tools = values,
        visibleTools = values.catalogSorted(layout.sort).filteredBy(query),
        recentTools = values.catalogRecent(),
        uninstallConfirmation = uninstallConfirmation?.takeIf { confirmation ->
            values.any { it.toolId == confirmation.toolId }
        },
    )

/** Mutable caches are confined to the single Default-dispatcher projection collector. */
internal class CatalogListProjection(
    private val sortTools: (List<CatalogTool>, CatalogSort) -> List<CatalogTool> = { tools, sort -> tools.catalogSorted(sort) },
    private val recentTools: (List<CatalogTool>) -> List<CatalogTool> = { it.catalogRecent() },
    private val filterTools: (List<CatalogTool>, String) -> List<CatalogTool> = { tools, query -> tools.filteredBy(query) },
) {
    private var previousTools: List<CatalogTool>? = null
    private var previousById = emptyMap<String, CatalogTool>()
    private var previousSort: CatalogSort? = null
    private var sorted = emptyList<CatalogTool>()
    private var recent = emptyList<CatalogTool>()
    private var filtered = emptyList<CatalogTool>()
    private var filteredSource: List<CatalogTool>? = null
    private var filteredQuery: String? = null

    fun project(tools: List<CatalogTool>, layout: CatalogLayout, query: String): CatalogUiState {
        val toolsChanged = previousTools != tools
        val sortChanged = previousSort != layout.sort
        if (toolsChanged) {
            val currentById = tools.associateBy { it.toolId }
            // Metadata and usage updates must refresh list elements without sorting by
            // unchanged keys. Each comparator's tie-breakers are included in this check.
            val sameOrder = !sortChanged && currentById.size == previousById.size && tools.all { tool ->
                previousById[tool.toolId]?.hasSameOrderKey(tool, layout.sort) == true
            }
            sorted = if (sameOrder) sorted.refreshedFrom(currentById) else sortTools(tools, layout.sort)
            val sameRecentOrder = previousTools != null &&
                tools.count { it.lastOpenedAt != null } == recent.size && tools.all { tool ->
                    tool.lastOpenedAt == null || previousById[tool.toolId]?.lastOpenedAt == tool.lastOpenedAt
                }
            recent = if (sameRecentOrder) recent.refreshedFrom(currentById) else recentTools(tools)
            previousTools = tools
            previousById = currentById
        } else if (sortChanged) {
            sorted = sortTools(tools, layout.sort)
        }
        previousSort = layout.sort

        val normalized = query.trim()
        if (filteredSource !== sorted || filteredQuery != normalized) {
            filtered = filterTools(sorted, normalized)
            filteredSource = sorted
            filteredQuery = normalized
        }
        return CatalogUiState(
            tools = previousTools ?: tools,
            visibleTools = filtered,
            recentTools = recent,
            layout = layout,
            query = query,
            isSearching = normalized.isNotEmpty(),
        )
    }
}

private fun CatalogTool.hasSameOrderKey(other: CatalogTool, sort: CatalogSort): Boolean = when (sort) {
    CatalogSort.INSTALLED -> installedAt == other.installedAt
    CatalogSort.LAST_OPENED -> lastOpenedAt == other.lastOpenedAt
    CatalogSort.NAME -> nameSortKey == other.nameSortKey && name == other.name
}

private fun List<CatalogTool>.refreshedFrom(currentById: Map<String, CatalogTool>): List<CatalogTool> =
    if (all { currentById[it.toolId] === it }) this else map { currentById.getValue(it.toolId) }

internal fun List<CatalogTool>.catalogRecent(): List<CatalogTool> =
    filter { it.lastOpenedAt != null }.catalogSorted(CatalogSort.LAST_OPENED)

internal fun List<CatalogTool>.filteredBy(query: String): List<CatalogTool> {
    val value = query.trim()
    if (value.isEmpty()) return this
    return filter { tool ->
        tool.name.contains(value, ignoreCase = true) || tool.toolId.contains(value, ignoreCase = true)
    }
}

internal fun List<CatalogTool>.catalogSorted(sort: CatalogSort): List<CatalogTool> = sortedWith(
    when (sort) {
        CatalogSort.INSTALLED -> compareByDescending<CatalogTool> { it.installedAt }.thenBy { it.toolId }
        CatalogSort.LAST_OPENED -> compareByDescending<CatalogTool> { it.lastOpenedAt }.thenBy { it.toolId }
        CatalogSort.NAME -> compareBy<CatalogTool> { it.nameSortKey }.thenBy { it.name }.thenBy { it.toolId }
    },
)
