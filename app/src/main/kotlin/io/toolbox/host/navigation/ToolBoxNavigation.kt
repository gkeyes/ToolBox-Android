package io.toolbox.host.navigation

import android.content.ContentResolver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.toolbox.host.HostDependencies
import io.toolbox.host.PermissionCenterViewModelFactory
import io.toolbox.host.RuntimeViewModelFactory
import io.toolbox.host.catalog.CatalogNavigationIntent
import io.toolbox.host.catalog.CatalogViewModel
import io.toolbox.host.importflow.ContentResolverPackageInputFactory
import io.toolbox.host.importflow.ImportReviewScreen
import io.toolbox.host.importflow.ImportReviewViewModel
import io.toolbox.host.importflow.SelectedPackageSource
import io.toolbox.host.importflow.ToolBoxOpenDocument
import io.toolbox.host.permissions.PermissionCenterScreen
import io.toolbox.host.permissions.PermissionCenterViewModel
import io.toolbox.host.runtime.RuntimeViewModel
import io.toolbox.host.settings.SettingsScreen
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.host.ui.HomeScreen
import io.toolbox.host.ui.MainDestination
import io.toolbox.host.ui.PrimaryScreen
import io.toolbox.host.ui.RuntimeShellScreen
import io.toolbox.host.ui.ToolDetailScreen
import io.toolbox.host.ui.ToolManagerScreen
import kotlinx.coroutines.launch

@Composable
internal fun ToolBoxNavigation(
    dependencies: HostDependencies,
    viewModelStoreOwner: ViewModelStoreOwner,
    catalogViewModel: CatalogViewModel,
    importReviewViewModel: ImportReviewViewModel,
    settingsViewModel: SettingsViewModel,
    contentResolver: ContentResolver,
) {
    val backStack = rememberNavBackStack(HomeRoute)
    val catalogState by catalogViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val packageInputFactory = remember(contentResolver) { ContentResolverPackageInputFactory(contentResolver) }
    val picker = rememberLauncherForActivityResult(ToolBoxOpenDocument.contract) { uri ->
        scope.launch {
            when (val source = packageInputFactory.fromPickerResult(uri)) {
                SelectedPackageSource.Cancelled -> importReviewViewModel.pickerCancelled()
                is SelectedPackageSource.Ready -> importReviewViewModel.inspect(source.input)
                is SelectedPackageSource.Rejected -> importReviewViewModel.pickerRejected(source.message)
            }
        }
    }

    fun navigate(route: NavKey) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun navigateMain(destination: MainDestination) {
        while (backStack.size > 1) backStack.removeLastOrNull()
        val route = when (destination) {
            MainDestination.Home -> HomeRoute
            MainDestination.Tools -> ToolManagerRoute
            MainDestination.Settings -> SettingsRoute
        }
        if (route != HomeRoute) backStack.add(route)
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    LaunchedEffect(catalogViewModel) {
        catalogViewModel.navigation.collect { intent ->
            when (intent) {
                is CatalogNavigationIntent.RequestRuntimeLaunch -> navigate(RuntimeRoute(intent.toolId))
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = {
            dispatchHostBack(
                currentRoute = backStack.lastOrNull(),
                onImportReviewBack = importReviewViewModel::cancelAndExit,
                onDefaultBack = ::goBack,
            )
        },
        entryProvider = entryProvider {
            entry<HomeRoute> {
                HomeScreen(
                    state = catalogState,
                    onAction = catalogViewModel::dispatch,
                    onDestination = ::navigateMain,
                    onImport = { navigate(ImportReviewRoute) },
                    onOpenDetails = { navigate(ToolDetailRoute(it)) },
                )
            }
            entry<ToolManagerRoute> {
                ToolManagerScreen(
                    state = catalogState,
                    onAction = catalogViewModel::dispatch,
                    onDestination = ::navigateMain,
                    onImport = { navigate(ImportReviewRoute) },
                    onOpenDetails = { navigate(ToolDetailRoute(it)) },
                )
            }
            entry<ToolDetailRoute> { route ->
                ToolDetailScreen(
                    toolId = route.toolId,
                    state = catalogState,
                    onAction = catalogViewModel::dispatch,
                    onBack = ::goBack,
                    onPermissions = { navigate(PermissionCenterRoute(it)) },
                )
            }
            entry<ImportReviewRoute> {
                ImportReviewScreen(
                    viewModel = importReviewViewModel,
                    onBack = ::goBack,
                    onPickerRequest = { picker.launch(ToolBoxOpenDocument.mimeTypes()) },
                )
            }
            entry<PermissionCenterRoute> { route ->
                val permissionViewModel = remember(route.toolId, dependencies) {
                    ViewModelProvider(
                        viewModelStoreOwner,
                        PermissionCenterViewModelFactory(route.toolId, dependencies.repositories),
                    ).get("permission:${route.toolId}", PermissionCenterViewModel::class.java)
                }
                PermissionCenterScreen(permissionViewModel, onBack = ::goBack)
            }
            entry<RuntimeRoute> { route ->
                val runtimeViewModel = remember(route.toolId, dependencies) {
                    ViewModelProvider(
                        viewModelStoreOwner,
                        RuntimeViewModelFactory(route.toolId, dependencies),
                    ).get("runtime:${route.toolId}", RuntimeViewModel::class.java)
                }
                RuntimeShellScreen(runtimeViewModel, onBack = ::goBack)
            }
            entry<SettingsRoute> {
                PrimaryScreen(
                    selected = MainDestination.Settings,
                    onDestination = ::navigateMain,
                    title = "设置",
                    onImport = null,
                ) { padding ->
                    SettingsScreen(settingsViewModel, contentPadding = padding)
                }
            }
        },
    )
}

internal fun dispatchHostBack(
    currentRoute: NavKey?,
    onImportReviewBack: () -> Unit,
    onDefaultBack: () -> Unit,
) {
    if (currentRoute == ImportReviewRoute) {
        onImportReviewBack()
    } else {
        onDefaultBack()
    }
}
