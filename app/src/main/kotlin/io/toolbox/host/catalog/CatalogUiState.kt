package io.toolbox.host.catalog

import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.SignatureState
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailureCode

data class CatalogTool(
    val toolId: String,
    val name: String,
    val signatureState: SignatureState,
    val activeVersionCode: Int?,
    val activeVersionName: String?,
    val bundleBytes: Long?,
    val launchState: LaunchState?,
    val lastOpenedAt: Long?,
    val categoryId: String?,
    val pinnedOrder: Int?,
)

enum class CatalogSort {
    PINNED_THEN_RECENT,
    RECENTLY_OPENED,
    NAME,
    INSTALLED_VERSION,
}

data class UninstallConfirmation(
    val toolId: String,
    val toolName: String,
)

sealed interface CatalogFeedback {
    data class Failure(
        val operation: CatalogOperation,
        val code: String,
        val message: String,
    ) : CatalogFeedback

    data class RecoveryPending(
        val operation: CatalogOperation,
        val code: LifecycleFailureCode,
        val message: String,
    ) : CatalogFeedback

    data class Completed(val message: String) : CatalogFeedback
}

enum class CatalogOperation {
    PIN,
    CATEGORY,
    OPEN,
    UNINSTALL,
    RECOVERY,
}

sealed interface CatalogNavigationIntent {
    data class RequestRuntimeLaunch(val toolId: String) : CatalogNavigationIntent
}

sealed interface CatalogAction {
    data class SetQuery(val query: String) : CatalogAction
    data class SetCategoryFilter(val categoryId: String?) : CatalogAction
    data class SetSort(val sort: CatalogSort) : CatalogAction
    data class SelectDetails(val toolId: String?) : CatalogAction
    data class TogglePinned(val toolId: String) : CatalogAction
    data class SetCategory(val toolId: String, val categoryId: String?) : CatalogAction
    data class RequestRuntimeLaunch(val toolId: String) : CatalogAction
    data class RequestUninstall(val toolId: String) : CatalogAction
    data object CancelUninstall : CatalogAction
    data object ConfirmUninstall : CatalogAction
    data object RecoverPendingMutation : CatalogAction
    data object DismissFeedback : CatalogAction
}

data class CatalogUiState(
    val isLoaded: Boolean = false,
    val tools: List<CatalogTool> = emptyList(),
    val query: String = "",
    val categoryFilter: String? = null,
    val sort: CatalogSort = CatalogSort.PINNED_THEN_RECENT,
    val selectedToolId: String? = null,
    val uninstallConfirmation: UninstallConfirmation? = null,
    val feedback: CatalogFeedback? = null,
) {
    val categories: List<String>
        get() = tools.mapNotNull(CatalogTool::categoryId).distinct().sorted()

    val selectedTool: CatalogTool?
        get() = tools.firstOrNull { it.toolId == selectedToolId }

    val visibleTools: List<CatalogTool>
        get() = tools.asSequence()
            .filter { tool -> categoryFilter == null || tool.categoryId == categoryFilter }
            .filter { tool -> tool.matches(query) }
            .sortedWith(sort.comparator)
            .toList()
}

private fun CatalogTool.matches(query: String): Boolean {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return true
    return sequenceOf(name, toolId, categoryId.orEmpty()).any { value ->
        value.contains(normalizedQuery, ignoreCase = true)
    }
}

private val CatalogSort.comparator: Comparator<CatalogTool>
    get() = when (this) {
        CatalogSort.PINNED_THEN_RECENT -> compareBy<CatalogTool> { it.pinnedOrder == null }
            .thenBy { it.pinnedOrder }
            .thenByDescending { it.lastOpenedAt ?: Long.MIN_VALUE }
            .thenBy { it.name }
            .thenBy { it.toolId }
        CatalogSort.RECENTLY_OPENED -> compareByDescending<CatalogTool> { it.lastOpenedAt ?: Long.MIN_VALUE }
            .thenBy { it.name }
            .thenBy { it.toolId }
        CatalogSort.NAME -> compareBy<CatalogTool> { it.name.lowercase() }
            .thenBy { it.toolId }
        CatalogSort.INSTALLED_VERSION -> compareByDescending<CatalogTool> { it.activeVersionCode ?: Int.MIN_VALUE }
            .thenBy { it.name }
            .thenBy { it.toolId }
    }
