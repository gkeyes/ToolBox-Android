package io.toolbox.host.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.toolbox.host.ui.HomeScreen
import io.toolbox.host.ui.ImportReviewScreen
import io.toolbox.host.ui.MainDestination
import io.toolbox.host.ui.PermissionCenterScreen
import io.toolbox.host.ui.RuntimeShellScreen
import io.toolbox.host.ui.SettingsScreen
import io.toolbox.host.ui.ToolManagerScreen

@Composable
fun ToolBoxNavigation() {
    val backStack = rememberNavBackStack(HomeRoute)

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
                    onDestination = ::navigateMain,
                    onImport = { navigate(ImportReviewRoute("phase1-static-review")) },
                    onLaunchTool = { navigate(RuntimeRoute("io.toolbox.positioncalculator")) },
                )
            }
            entry<ToolManagerRoute> {
                ToolManagerScreen(
                    onDestination = ::navigateMain,
                    onImport = { navigate(ImportReviewRoute("phase1-static-review")) },
                    onLaunchTool = { navigate(RuntimeRoute("io.toolbox.positioncalculator")) },
                )
            }
            entry<ImportReviewRoute> { ImportReviewScreen(onBack = ::goBack) }
            entry<PermissionCenterRoute> { PermissionCenterScreen(onBack = ::goBack) }
            entry<SettingsRoute> {
                SettingsScreen(
                    onDestination = ::navigateMain,
                    onPermissionCenter = { navigate(PermissionCenterRoute) },
                )
            }
            entry<RuntimeRoute> {
                RuntimeShellScreen(
                    onBack = ::goBack,
                    onPermissionCenter = { navigate(PermissionCenterRoute) },
                )
            }
        },
    )
}
