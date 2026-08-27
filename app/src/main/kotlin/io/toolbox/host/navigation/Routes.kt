package io.toolbox.host.navigation

import androidx.navigation3.runtime.NavKey
import io.toolbox.host.ui.HostCapability
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute : NavKey

@Serializable
data object ToolManagerRoute : NavKey

@Serializable
data class CapabilityUnavailableRoute(val capability: HostCapability) : NavKey

@Serializable
data object SettingsRoute : NavKey
