package io.toolbox.host.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.toolbox.host.ui.HomeScreen
import io.toolbox.host.ui.CapabilityUnavailableScreen
import io.toolbox.host.ui.HostCapability
import io.toolbox.host.ui.MainDestination
import io.toolbox.host.ui.ProductionHostState
import io.toolbox.host.ui.SettingsScreen
import io.toolbox.host.ui.ToolManagerScreen

@Composable
fun ToolBoxNavigation() {
    val backStack = rememberNavBackStack(HomeRoute)
    val hostState = remember { ProductionHostState.freshInstall() }

    fun navigate(route: NavKey) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun navigateMain(destination: MainDestination) {
        navigate(
            when (destination) {
                MainDestination.Home -> HomeRoute
                MainDestination.Tools -> ToolManagerRoute
                MainDestination.Settings -> SettingsRoute
            },
        )
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    NavDisplay(
        backStack = backStack,
        onBack = ::goBack,
        entryProvider = entryProvider {
            entry<HomeRoute> {
                HomeScreen(
                    model = hostState.home,
                    onDestination = ::navigateMain,
                    onImport = { navigate(CapabilityUnavailableRoute(HostCapability.ImportTools)) },
                    onLaunchTool = { navigate(CapabilityUnavailableRoute(HostCapability.Runtime)) },
                )
            }
            entry<ToolManagerRoute> {
                ToolManagerScreen(
                    model = hostState.toolManager,
                    onDestination = ::navigateMain,
                    onImport = { navigate(CapabilityUnavailableRoute(HostCapability.ImportTools)) },
                    onLaunchTool = { navigate(CapabilityUnavailableRoute(HostCapability.Runtime)) },
                )
            }
            entry<CapabilityUnavailableRoute> { route ->
                CapabilityUnavailableScreen(capability = route.capability, onBack = ::goBack)
            }
            entry<SettingsRoute> {
                SettingsScreen(
                    onDestination = ::navigateMain,
                )
            }
        },
    )
}
