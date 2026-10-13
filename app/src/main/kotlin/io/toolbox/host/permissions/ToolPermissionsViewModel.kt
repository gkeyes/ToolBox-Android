package io.toolbox.host.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.host.HostPackageOperations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class ToolPermissionsUiState(
    val tools: List<InstalledTool> = emptyList(),
    val toolsLoaded: Boolean = false,
)

/** Installed tools and their read-only permission summaries for the tool permissions list. */
internal class ToolPermissionsViewModel(
    catalog: CatalogRepository,
    packages: HostPackageOperations,
    grants: PermissionGrantRepository,
) : ViewModel() {
    private val summaryReader = ToolPermissionSummaryReader(catalog, packages, grants)

    val state: StateFlow<ToolPermissionsUiState> = catalog.observeTools()
        .map { ToolPermissionsUiState(tools = it, toolsLoaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ToolPermissionsUiState())

    /** Summary of one installed version; collected lazily by each visible row. */
    fun summary(tool: InstalledTool): Flow<ToolPermissionSummary> = summaryReader.observe(tool)
}

internal class ToolPermissionsViewModelFactory(
    private val catalog: CatalogRepository,
    private val packages: HostPackageOperations,
    private val grants: PermissionGrantRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(ToolPermissionsViewModel::class.java.isAssignableFrom(modelClass)) {
            "Unsupported tool permissions ViewModel: ${modelClass.name}"
        }
        return ToolPermissionsViewModel(catalog, packages, grants) as T
    }
}
