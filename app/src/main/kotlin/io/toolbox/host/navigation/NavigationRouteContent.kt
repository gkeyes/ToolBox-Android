package io.toolbox.host.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import io.toolbox.host.HostDependencies
import io.toolbox.host.HostFeatureViewModelFactory
import io.toolbox.host.PermissionCenterViewModelFactory
import io.toolbox.host.background.AndroidBackgroundSystemStateSource
import io.toolbox.host.background.BackgroundSafeguardsScreen
import io.toolbox.host.background.BackgroundSafeguardsViewModel
import io.toolbox.host.background.BackgroundSafeguardsViewModelFactory
import io.toolbox.host.background.BackgroundTasksScreen
import io.toolbox.host.background.BackgroundTasksViewModel
import io.toolbox.host.background.BackgroundTasksViewModelFactory
import io.toolbox.host.catalog.CatalogViewModel
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.RunningToolsViewModel
import io.toolbox.host.help.DeveloperHelpScreen
import io.toolbox.host.importflow.ImportViewModel
import io.toolbox.host.permissions.PermissionCenterScreen
import io.toolbox.host.permissions.PermissionCenterViewModel
import io.toolbox.host.permissions.ToolPermissionsScreen
import io.toolbox.host.permissions.ToolPermissionsViewModel
import io.toolbox.host.permissions.ToolPermissionsViewModelFactory
import io.toolbox.host.settings.AppearanceScreen
import io.toolbox.host.settings.AboutScreen
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.host.ui.CatalogRunningTools
import io.toolbox.host.ui.collectAsStateWhileVisible
import io.toolbox.host.ui.HostRouteLayout
import io.toolbox.host.ui.ToolManagerContent
import io.toolbox.host.ui.ToolDetailScreen

@Composable
internal fun SecondaryRouteContent(
    route: ToolBoxRoute,
    dependencies: HostDependencies,
    viewModelStoreOwner: ViewModelStoreOwner,
    catalogViewModel: CatalogViewModel,
    importViewModel: ImportViewModel,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    onReady: () -> Unit,
    onNavigate: (ToolBoxRoute) -> Unit,
) {
    when (route) {
        AboutRoute -> AboutScreen(onBack, onReady)
        BackupRestoreRoute -> {
            val owner = rememberViewModelStoreOwner(parent = viewModelStoreOwner)
            val backup = remember(dependencies, owner) {
                ViewModelProvider(owner, HostFeatureViewModelFactory(dependencies))
                    .get("host.backup", io.toolbox.host.backup.BackupViewModel::class.java)
            }
            io.toolbox.host.backup.BackupScreen(backup, onBack, onReady)
        }
        UpdateRoute -> {
            val owner = rememberViewModelStoreOwner(parent = viewModelStoreOwner)
            val updates = remember(dependencies, owner) {
                ViewModelProvider(owner, HostFeatureViewModelFactory(dependencies))
                    .get("host.updates", io.toolbox.host.update.UpdateViewModel::class.java)
            }
            io.toolbox.host.update.UpdateScreen(updates, onBack, onReady)
        }

        is ToolDetailRoute -> ToolDetailRouteContent(
            toolId = route.toolId,
            catalogViewModel = catalogViewModel,
            onBack = onBack,
            onReady = onReady,
            onPermissions = { onNavigate(PermissionCenterRoute(it)) },
            onBackground = { onNavigate(BackgroundTasksRoute(it)) },
        )

        is PermissionCenterRoute -> {
            val permissionOwner = rememberViewModelStoreOwner(parent = viewModelStoreOwner)
            val permissionViewModel = remember(route.toolId, dependencies, permissionOwner) {
                ViewModelProvider(
                    permissionOwner,
                    PermissionCenterViewModelFactory(route.toolId, dependencies),
                ).get("permission:${route.toolId}", PermissionCenterViewModel::class.java)
            }
            PermissionCenterScreen(permissionViewModel, onBack = onBack, onReady = onReady)
        }

        is BackgroundTasksRoute -> {
            val owner = rememberViewModelStoreOwner(parent = viewModelStoreOwner)
            val tasks = remember(route.toolId, dependencies, owner) {
                ViewModelProvider(
                    owner,
                    BackgroundTasksViewModelFactory(
                        toolId = route.toolId,
                        operations = dependencies.backgroundOperations,
                        sessions = dependencies.runtimeSessions.sessions,
                        stopSession = dependencies.runtimeSessions::stopSession,
                    ),
                ).get("background-tasks:${route.toolId}", BackgroundTasksViewModel::class.java)
            }
            BackgroundTasksScreen(tasks, onBack = onBack, onReady = onReady)
        }

        ToolPermissionsRoute -> {
            val owner = rememberViewModelStoreOwner(parent = viewModelStoreOwner)
            val permissions = remember(dependencies, owner) {
                ViewModelProvider(
                    owner,
                    ToolPermissionsViewModelFactory(
                        catalog = dependencies.repositories.catalog,
                        packages = dependencies.packageOperations,
                        grants = dependencies.repositories.grants,
                    ),
                ).get("host.tool-permissions", ToolPermissionsViewModel::class.java)
            }
            ToolPermissionsScreen(
                viewModel = permissions,
                onBack = onBack,
                onSelectTool = { onNavigate(PermissionCenterRoute(it)) },
                onReady = onReady,
            )
        }

        AppearanceRoute -> AppearanceScreen(
            viewModel = settingsViewModel,
            onBack = onBack,
            onReady = onReady,
        )

        BackgroundSafeguardsRoute -> BackgroundSafeguardsScreen(
            viewModel = settingsViewModel,
            safeguards = rememberBackgroundSafeguardsViewModel(viewModelStoreOwner),
            runningTools = remember(dependencies, viewModelStoreOwner) {
                ViewModelProvider(viewModelStoreOwner, HostFeatureViewModelFactory(dependencies))
                    .get("host.safeguards-running-tools", RunningToolsViewModel::class.java)
            },
            onBack = onBack,
            onReady = onReady,
        )

        DeveloperHelpRoute -> DeveloperHelpScreen(
            onBack = onBack,
            onInstallExamples = importViewModel::installBundledExamples,
            onReady = onReady,
        )

        HomeRoute,
        ToolManagerRoute,
        SettingsRoute,
        is RuntimeRoute,
        -> error("Route is not a retained secondary page: $route")
    }
}

/** Scoped to one visit of the page, like the state the page used to remember itself. */
@Composable
private fun rememberBackgroundSafeguardsViewModel(parent: ViewModelStoreOwner): BackgroundSafeguardsViewModel {
    val context = LocalContext.current.applicationContext
    val owner = rememberViewModelStoreOwner(parent = parent)
    return remember(context, owner) {
        ViewModelProvider(owner, BackgroundSafeguardsViewModelFactory(AndroidBackgroundSystemStateSource(context)))
            .get("host.background-safeguards", BackgroundSafeguardsViewModel::class.java)
    }
}

@Composable
internal fun ToolManagerRouteContent(
    home: Boolean,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    creatingGroup: Boolean,
    onDismissCreateGroup: () -> Unit,
    dependencies: HostDependencies,
    viewModelStoreOwner: ViewModelStoreOwner,
    catalogViewModel: CatalogViewModel,
    importViewModel: ImportViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    layout: HostRouteLayout,
    uiVisible: Boolean,
    onImport: () -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    val runningToolsViewModel = remember(dependencies, viewModelStoreOwner) {
        ViewModelProvider(viewModelStoreOwner, HostFeatureViewModelFactory(dependencies))
            .get("host.running-tools", RunningToolsViewModel::class.java)
    }
    // A modal can outlive its base surface (for example, an external shortcut).
    // Keep its invalidation live rather than freezing a destructive confirmation.
    val catalogState by catalogViewModel.state.collectAsStateWhileVisible(uiVisible) {
        it.uninstallConfirmation != null
    }
    val importState by importViewModel.state.collectAsStateWhileVisible(uiVisible)
    ToolManagerContent(
        home = home,
        editing = editing,
        onEditingChange = onEditingChange,
        creatingGroup = creatingGroup,
        onDismissCreateGroup = onDismissCreateGroup,
        state = catalogState,
        importState = importState,
        listState = listState,
        contentPadding = contentPadding,
        onAction = catalogViewModel::dispatch,
        onImport = onImport,
        onInstallExamples = importViewModel::installBundledExamples,
        onDismissImport = importViewModel::dismissMessage,
        onExpireImportSuccess = importViewModel::expireSuccess,
        onConfirmImport = importViewModel::confirmVersionReplacement,
        onCancelImport = importViewModel::cancelVersionReplacement,
        onCancelActiveImport = importViewModel::cancelActiveImport,
        onOpenDetails = onOpenDetails,
        runningTools = {
            CatalogRunningTools(
                viewModel = runningToolsViewModel,
                tools = catalogState.tools,
                uiVisible = uiVisible,
                onOpen = { catalogViewModel.dispatch(CatalogAction.RequestRuntimeLaunch(it)) },
            )
        },
    )
}

@Composable
private fun ToolDetailRouteContent(
    toolId: String,
    catalogViewModel: CatalogViewModel,
    onBack: () -> Unit,
    onReady: () -> Unit,
    onPermissions: (String) -> Unit,
    onBackground: (String) -> Unit,
) {
    val catalogState by catalogViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(catalogState.isLoaded) {
        if (catalogState.isLoaded) onReady()
    }
    ToolDetailScreen(
        toolId = toolId,
        state = catalogState,
        onAction = catalogViewModel::dispatch,
        onBack = onBack,
        onPermissions = onPermissions,
        onBackground = onBackground,
    )
}
