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

sealed interface CatalogAction {
    data class SetSort(val sort: CatalogSort) : CatalogAction
    data class SetFavorite(val toolId: String, val selected: Boolean) : CatalogAction
    data class CreateGroup(val name: String) : CatalogAction
    data class SaveGroup(val groupId: String?, val name: String, val members: List<String>) : CatalogAction
    data class RenameGroup(val groupId: String, val name: String) : CatalogAction
    data class DeleteGroup(val groupId: String) : CatalogAction
    data class SetGroupMembership(val groupId: String, val toolId: String, val selected: Boolean) : CatalogAction
    data class SetGroupExpanded(val groupId: String, val expanded: Boolean) : CatalogAction
    data class MoveFavorite(val toolId: String, val offset: Int) : CatalogAction
    data class MoveGroup(val groupId: String, val offset: Int) : CatalogAction
    data class MoveMember(val groupId: String, val toolId: String, val offset: Int) : CatalogAction
    data class SetQuery(val query: String) : CatalogAction
    data class RequestRuntimeLaunch(val toolId: String) : CatalogAction
    data class RequestUninstall(val toolId: String) : CatalogAction
    data object CancelUninstall : CatalogAction
    data object ConfirmUninstall : CatalogAction
    data object DismissFeedback : CatalogAction
}

data class CatalogUiState(
    val isLoaded: Boolean = false,
    val layout: CatalogLayout = CatalogLayout(),
    val tools: List<CatalogTool> = emptyList(),
    val visibleTools: List<CatalogTool> = emptyList(),
    val recentTools: List<CatalogTool> = emptyList(),
    val query: String = "",
    val isSearching: Boolean = false,
    val uninstallConfirmation: UninstallConfirmation? = null,
    val feedback: CatalogFeedback? = null,
)

internal fun CatalogUiState.withCatalogTools(values: List<CatalogTool>): CatalogUiState =
    copy(
        tools = values,
        visibleTools = values.catalogSorted(layout.sort).filteredBy(query),
        recentTools = values
            .asSequence()
            .filter { it.lastOpenedAt != null }
            .sortedWith(compareByDescending<CatalogTool> { it.lastOpenedAt }.thenBy { it.toolId })
            .toList(),
        uninstallConfirmation = uninstallConfirmation?.takeIf { confirmation ->
            values.any { it.toolId == confirmation.toolId }
        },
    )

internal fun CatalogUiState.withCatalogQuery(value: String): CatalogUiState {
    val normalized = value.trim()
    return copy(
        query = value,
        isSearching = normalized.isNotEmpty(),
        visibleTools = tools.catalogSorted(layout.sort).filteredBy(normalized),
    )
}

private fun List<CatalogTool>.filteredBy(query: String): List<CatalogTool> {
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
