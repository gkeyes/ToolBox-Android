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

@Immutable
data class CatalogUiState(
    val isLoaded: Boolean = false,
    val tools: List<CatalogTool> = emptyList(),
    val query: String = "",
    val uninstallConfirmation: UninstallConfirmation? = null,
    val feedback: CatalogFeedback? = null,
) {
    val visibleTools: List<CatalogTool>
        get() {
            val value = query.trim()
            if (value.isEmpty()) return tools
            return tools.filter { tool ->
                tool.name.contains(value, ignoreCase = true) || tool.toolId.contains(value, ignoreCase = true)
            }
        }
}
