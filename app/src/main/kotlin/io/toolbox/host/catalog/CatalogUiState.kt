package io.toolbox.host.catalog

import androidx.compose.runtime.Immutable

@Immutable
data class CatalogTool(
    val toolId: String,
    val name: String,
    val versionCode: Int,
    val versionName: String,
    val bundleBytes: Long,
    val lastOpenedAt: Long?,
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
    data class SetQuery(val query: String) : CatalogAction
    data class RequestRuntimeLaunch(val toolId: String) : CatalogAction
    data class RequestUninstall(val toolId: String) : CatalogAction
    data object CancelUninstall : CatalogAction
    data object ConfirmUninstall : CatalogAction
    data object DismissFeedback : CatalogAction
}

data class CatalogUiState(
    val isLoaded: Boolean = false,
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
        visibleTools = values.filteredBy(query),
        recentTools = values
            .asSequence()
            .filter { it.lastOpenedAt != null }
            .sortedByDescending { it.lastOpenedAt }
            .take(MAX_RECENT_TOOL_COUNT)
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
        visibleTools = tools.filteredBy(normalized),
    )
}

internal const val COMPACT_RECENT_TOOL_COUNT = 2
internal const val MAX_RECENT_TOOL_COUNT = 3

private fun List<CatalogTool>.filteredBy(query: String): List<CatalogTool> {
    val value = query.trim()
    if (value.isEmpty()) return this
    return filter { tool ->
        tool.name.contains(value, ignoreCase = true) || tool.toolId.contains(value, ignoreCase = true)
    }
}
