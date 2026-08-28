package io.toolbox.host.ui

import androidx.compose.runtime.Immutable

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>

    data object Empty : UiState<Nothing>

    data class Content<T>(val value: T) : UiState<T>

    data class Error(
        val code: String,
        val message: String,
    ) : UiState<Nothing>
}

@Immutable
data class ToolCardModel(
    val toolId: String,
    val title: String,
    val metadata: String,
    val symbol: String,
    val trust: ToolTrust = ToolTrust.Trusted,
)

enum class ToolTrust {
    Trusted,
    NeedsReview,
    HighRisk,
}

@Immutable
data class HostCatalogScreenModel(
    val state: UiState<List<ToolCardModel>>,
) {
    val tools: List<ToolCardModel>
        get() = (state as? UiState.Content)?.value.orEmpty()

    val installedToolCount: Int
        get() = tools.size
}

@Immutable
data class HostScreenModels(
    val home: HostCatalogScreenModel,
    val toolManager: HostCatalogScreenModel,
)

object ProductionHostState {
    fun freshInstall(): HostScreenModels = HostScreenModels(
        home = HostCatalogScreenModel(state = UiState.Empty),
        toolManager = HostCatalogScreenModel(state = UiState.Empty),
    )
}
