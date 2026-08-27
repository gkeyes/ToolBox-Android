package io.toolbox.host.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object HostTestTags {
    const val BottomHome = "bottom_home"
    const val BottomTools = "bottom_tools"
    const val BottomSettings = "bottom_settings"
    const val BottomNavigationContainer = "bottom_navigation_container"
    const val ImportFab = "import_fab"
    const val PermissionCenter = "permission_center"
    const val RuntimeShell = "runtime_shell"
    const val CatalogEmptyState = "catalog_empty_state"
    const val CatalogList = "catalog_list"
    const val ToolCardPrefix = "tool_card:"
    const val CapabilityUnavailable = "capability_unavailable"
}

enum class MainDestination(val label: String, val symbol: String) {
    Home("首页", "⌂"),
    Tools("工具", "▦"),
    Settings("设置", "⚙"),
}

internal data class HostRouteLayout(
    val isCompact: Boolean,
    val horizontalContentPadding: Dp,
    val verticalContentPadding: Dp,
)

internal fun hostRouteLayoutFor(maxWidth: Dp): HostRouteLayout =
    if (maxWidth < 600.dp) {
        HostRouteLayout(
            isCompact = true,
            horizontalContentPadding = 20.dp,
            verticalContentPadding = 16.dp,
        )
    } else {
        HostRouteLayout(
            isCompact = false,
            horizontalContentPadding = 28.dp,
            verticalContentPadding = 16.dp,
        )
    }
