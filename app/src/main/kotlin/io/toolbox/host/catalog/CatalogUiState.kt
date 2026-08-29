package io.toolbox.host.catalog

import androidx.compose.runtime.Immutable
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.SignatureState
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailureCode

@Immutable
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

@Immutable
data class UninstallConfirmation(
    val toolId: String,
    val toolName: String,
)

@Immutable
sealed interface CatalogFeedback {
    @Immutable
    data class Failure(
        val operation: CatalogOperation,
        val code: String,
        val message: String,
    ) : CatalogFeedback

    @Immutable
    data class RecoveryPending(
        val operation: CatalogOperation,
        val code: LifecycleFailureCode,
        val message: String,
    ) : CatalogFeedback

    @Immutable
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

@Immutable
data class HomeScreenState(
    val isLoaded: Boolean = false,
    val totalToolCount: Int = 0,
    val pinnedTools: List<CatalogTool> = emptyList(),
    val recentTools: List<CatalogTool> = emptyList(),
    val feedback: CatalogFeedback? = null,
)

@Immutable
data class CatalogUiState(
    val isLoaded: Boolean = false,
    val tools: List<CatalogTool> = emptyList(),
    val visibleTools: List<CatalogTool> = tools,
    val categories: List<String> = catalogCategories(tools),
    val query: String = "",
    val categoryFilter: String? = null,
    val sort: CatalogSort = CatalogSort.PINNED_THEN_RECENT,
    val selectedToolId: String? = null,
    val uninstallConfirmation: UninstallConfirmation? = null,
    val feedback: CatalogFeedback? = null,
) {
    val selectedTool: CatalogTool?
        get() = tools.firstOrNull { it.toolId == selectedToolId }
}

internal fun catalogCategories(tools: List<CatalogTool>): List<String> =
    tools.mapNotNull(CatalogTool::categoryId).distinct().sorted()

internal fun visibleCatalogTools(
    tools: List<CatalogTool>,
    query: String,
    categoryFilter: String?,
    sort: CatalogSort,
): List<CatalogTool> = tools.asSequence()
    .filter { tool -> categoryFilter == null || tool.categoryId == categoryFilter }
    .filter { tool -> tool.matches(query) }
    .sortedWith(sort.comparator)
    .toList()

internal fun homeScreenState(
    tools: List<CatalogTool>,
    isLoaded: Boolean,
    feedback: CatalogFeedback?,
): HomeScreenState = HomeScreenState(
    isLoaded = isLoaded,
    totalToolCount = tools.size,
    pinnedTools = tools.filter { it.pinnedOrder != null }.sortedBy(CatalogTool::pinnedOrder),
    recentTools = tools
        .filter { it.pinnedOrder == null && it.lastOpenedAt != null }
        .sortedByDescending(CatalogTool::lastOpenedAt),
    feedback = feedback,
)

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
