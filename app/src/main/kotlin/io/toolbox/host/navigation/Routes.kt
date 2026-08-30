package io.toolbox.host.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface ToolBoxRoute : NavKey

@Serializable
data object ToolManagerRoute : ToolBoxRoute

@Serializable
data class ToolDetailRoute(val toolId: String) : ToolBoxRoute

@Serializable
data class PermissionCenterRoute(val toolId: String) : ToolBoxRoute

@Serializable
data class RuntimeRoute(val toolId: String) : ToolBoxRoute

@Serializable
data object SettingsRoute : ToolBoxRoute

@Serializable
data class BackgroundTasksRoute(val toolId: String) : ToolBoxRoute

@Serializable
data object ToolPermissionsRoute : ToolBoxRoute

@Serializable
data object DeveloperHelpRoute : ToolBoxRoute
