package io.toolbox.host.navigation

import android.content.ContentResolver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.HostDependencies
import io.toolbox.host.PermissionCenterViewModelFactory
import io.toolbox.host.RuntimeViewModelFactory
import io.toolbox.host.background.BackgroundTasksScreen
import io.toolbox.host.catalog.CatalogNavigationIntent
import io.toolbox.host.catalog.CatalogViewModel
import io.toolbox.host.help.DeveloperHelpScreen
import io.toolbox.host.importflow.ContentResolverPackageInputFactory
import io.toolbox.host.importflow.ImportViewModel
import io.toolbox.host.importflow.SelectedPackageSource
import io.toolbox.host.importflow.ToolBoxOpenDocument
import io.toolbox.host.permissions.PermissionCenterScreen
import io.toolbox.host.permissions.PermissionCenterViewModel
import io.toolbox.host.permissions.ToolPermissionsScreen
import io.toolbox.host.runtime.RuntimeViewModel
import io.toolbox.host.settings.SettingsScreen
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.host.ui.MainDestination
import io.toolbox.host.ui.PrimaryScreen
import io.toolbox.host.ui.RuntimeShellScreen
import io.toolbox.host.ui.ToolDetailScreen
import io.toolbox.host.ui.ToolManagerScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

private val PrimaryTabFadeTransition = navGraphicsTransition(
    scrim = { 0f },
) { scope ->
    alpha = 1f - kotlin.math.abs(scope.relativeDepth).coerceIn(0f, 1f)
}

@Composable
internal fun ToolBoxNavigation(
    dependencies: HostDependencies,
    viewModelStoreOwner: ViewModelStoreOwner,
    catalogViewModel: CatalogViewModel,
    importViewModel: ImportViewModel,
    settingsViewModel: SettingsViewModel,
    contentResolver: ContentResolver,
) {
    val backStack = rememberNavBackStack<ToolBoxRoute>(ToolManagerRoute)
    val catalogState by catalogViewModel.state.collectAsStateWithLifecycle()
    val importState by importViewModel.state.collectAsStateWithLifecycle()
    val toolsListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val packageInputFactory = remember(contentResolver) { ContentResolverPackageInputFactory(contentResolver) }
    val picker = rememberLauncherForActivityResult(ToolBoxOpenDocument.contract) { uri ->
        scope.launch {
            when (val source = packageInputFactory.fromPickerResult(uri)) {
                SelectedPackageSource.Cancelled -> Unit
                is SelectedPackageSource.Ready -> importViewModel.importPackage(source.input)
                is SelectedPackageSource.Rejected -> importViewModel.pickerRejected(source.message)
            }
        }
    }

    fun navigate(route: ToolBoxRoute) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun navigateMain(destination: MainDestination) {
        while (backStack.size > 1) backStack.removeLastOrNull()
        val route = when (destination) {
            MainDestination.Tools -> ToolManagerRoute
            MainDestination.Settings -> SettingsRoute
        }
        if (backStack.lastOrNull() != route) backStack.add(route)
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
        onBack = ::goBack,
        transition = NavTransitions.MiuixDefault,
        effects = NavDisplayEffects(
            cornerClipRadius = rememberNavSystemCornerRadius(),
            backdropColor = ToolBoxThemeTokens.colors.background,
            blockInputDuringTransition = true,
        ),
    ) {
        entry<ToolManagerRoute>(transition = PrimaryTabFadeTransition) {
            ToolManagerScreen(
                state = catalogState,
                importState = importState,
                listState = toolsListState,
                onAction = catalogViewModel::dispatch,
                onDestination = ::navigateMain,
                onImport = { picker.launch(ToolBoxOpenDocument.mimeTypes()) },
                onInstallExamples = importViewModel::installBundledExamples,
                onDismissImport = importViewModel::dismissMessage,
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
                onBackground = { navigate(BackgroundTasksRoute(it)) },
            )
        }
        entry<PermissionCenterRoute> { route ->
            val permissionViewModel = remember(route.toolId, dependencies) {
                ViewModelProvider(
                    viewModelStoreOwner,
                    PermissionCenterViewModelFactory(route.toolId, dependencies),
                ).get("permission:${route.toolId}", PermissionCenterViewModel::class.java)
            }
            PermissionCenterScreen(permissionViewModel, onBack = ::goBack)
        }
        entry<BackgroundTasksRoute> { route ->
            BackgroundTasksScreen(
                toolId = route.toolId,
                operations = dependencies.backgroundOperations,
                onBack = ::goBack,
            )
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
        entry<SettingsRoute>(transition = PrimaryTabFadeTransition) {
            PrimaryScreen(
                selected = MainDestination.Settings,
                onDestination = ::navigateMain,
                title = "设置",
                onImport = null,
            ) { padding ->
                SettingsScreen(
                    viewModel = settingsViewModel,
                    contentPadding = padding,
                    onToolPermissions = { navigate(ToolPermissionsRoute) },
                    onDeveloperHelp = { navigate(DeveloperHelpRoute) },
                )
            }
        }
        entry<ToolPermissionsRoute> {
            ToolPermissionsScreen(
                catalog = dependencies.repositories.catalog,
                onBack = ::goBack,
                onSelectTool = { navigate(PermissionCenterRoute(it)) },
            )
        }
        entry<DeveloperHelpRoute> {
            DeveloperHelpScreen(
                onBack = ::goBack,
                onInstallExamples = importViewModel::installBundledExamples,
            )
        }
    }
}
