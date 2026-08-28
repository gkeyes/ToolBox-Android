package io.toolbox.host.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute : NavKey

@Serializable
data object ToolManagerRoute : NavKey

@Serializable
data class ToolDetailRoute(val toolId: String) : NavKey

@Serializable
data object ImportReviewRoute : NavKey

@Serializable
data class PermissionCenterRoute(val toolId: String) : NavKey

@Serializable
data class RuntimeRoute(val toolId: String) : NavKey

@Serializable
data object SettingsRoute : NavKey
