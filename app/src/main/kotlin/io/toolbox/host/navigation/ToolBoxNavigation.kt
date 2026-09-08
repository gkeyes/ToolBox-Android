package io.toolbox.host.navigation

import android.content.ContentResolver
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import io.toolbox.core.ui.component.ToolBoxGlassActivity
import io.toolbox.host.HostDependencies
import io.toolbox.host.HostFeatureViewModelFactory
import io.toolbox.host.PermissionCenterViewModelFactory
import io.toolbox.host.RuntimeViewModelFactory
import io.toolbox.host.HostTrace
import io.toolbox.host.background.BackgroundTasksScreen
import io.toolbox.host.background.BackgroundSafeguardsScreen
import io.toolbox.host.catalog.CatalogNavigationIntent
import io.toolbox.host.catalog.CatalogViewModel
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.RunningToolsViewModel
import io.toolbox.host.help.DeveloperHelpScreen
import io.toolbox.host.importflow.ContentResolverPackageInputFactory
import io.toolbox.host.importflow.ImportViewModel
import io.toolbox.host.importflow.SelectedPackageSource
import io.toolbox.host.importflow.ToolBoxOpenDocument
import io.toolbox.host.permissions.PermissionCenterScreen
import io.toolbox.host.permissions.PermissionCenterViewModel
import io.toolbox.host.permissions.ToolPermissionsScreen
import io.toolbox.host.runtime.RuntimeViewModel
import io.toolbox.host.settings.AppearanceScreen
import io.toolbox.host.settings.SettingsScreen
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.host.ui.MainDestination
import io.toolbox.host.ui.CatalogRunningTools
import io.toolbox.host.ui.collectAsStateWhileVisible
import io.toolbox.host.ui.HostRouteLayout
import io.toolbox.host.ui.PrimaryScreen
import io.toolbox.host.ui.RuntimeShellPreviewContent
import io.toolbox.host.ui.RuntimeShellScreen
import io.toolbox.host.ui.ToolManagerContent
import io.toolbox.host.ui.ToolDetailScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.runtime.NavProgrammaticEasing

private val RetainedPageMotion = tween<Float>(
    durationMillis = 180,
    easing = NavProgrammaticEasing,
)

@Composable
internal fun ToolBoxNavigation(
    dependencies: HostDependencies,
    viewModelStoreOwner: ViewModelStoreOwner,
    catalogViewModel: CatalogViewModel,
    importViewModel: ImportViewModel,
    settingsViewModel: SettingsViewModel,
    contentResolver: ContentResolver,
) {
    val primaryBackStack = rememberNavBackStack<ToolBoxRoute>(ToolManagerRoute)
    val secondaryBackStack = rememberNavBackStack<ToolBoxRoute>()
    val toolsListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val packageInputFactory = remember(contentResolver) { ContentResolverPackageInputFactory(contentResolver) }
    val picker = rememberLauncherForActivityResult(ToolBoxOpenDocument.contract) { uri ->
        coroutineScope.launch {
            when (val source = packageInputFactory.fromPickerResult(uri)) {
                SelectedPackageSource.Cancelled -> Unit
                is SelectedPackageSource.Ready -> importViewModel.importPackage(source.input)
                is SelectedPackageSource.Rejected -> importViewModel.pickerRejected(source.message)
            }
        }
    }

    fun navigate(route: ToolBoxRoute) {
        if (secondaryBackStack.lastOrNull() != route) secondaryBackStack.add(route)
    }

    fun navigateMain(destination: MainDestination) {
        secondaryBackStack.clear()
        while (primaryBackStack.size > 1) primaryBackStack.removeLastOrNull()
        val route = when (destination) {
            MainDestination.Tools -> ToolManagerRoute
            MainDestination.Settings -> SettingsRoute
        }
        if (primaryBackStack.lastOrNull() != route) {
            primaryBackStack.add(route)
        }
    }

    fun goBackPrimary() {
        if (primaryBackStack.size > 1) primaryBackStack.removeLastOrNull()
    }

    LaunchedEffect(catalogViewModel) {
        catalogViewModel.navigation.collect { intent ->
            when (intent) {
                is CatalogNavigationIntent.RequestRuntimeLaunch -> navigate(RuntimeRoute(intent.toolId))
            }
        }
    }

    val allSecondaryRoutes = secondaryBackStack.filterIsInstance<ToolBoxRoute>()
    val currentPrimaryRoute = primaryBackStack.lastOrNull()
    val runtimeRoute = allSecondaryRoutes.lastOrNull() as? RuntimeRoute
    val retainedRoutes = if (runtimeRoute == null) allSecondaryRoutes else allSecondaryRoutes.dropLast(1)
    val entryProgress = remember(runtimeRoute) { Animatable(if (runtimeRoute == null) 0f else 1f) }
    val sourceReturnProgress = remember(runtimeRoute) { Animatable(0f) }
    var runtimeLayerEnabled by remember(runtimeRoute) { mutableStateOf(false) }
    var entryCoverVisible by remember(runtimeRoute) { mutableStateOf(runtimeRoute != null) }
    var sourceAboveRuntime by remember(runtimeRoute) { mutableStateOf(false) }
    var runtimePresentationReady by remember(runtimeRoute) { mutableStateOf(false) }
    var runtimeLeaving by remember(runtimeRoute) { mutableStateOf(false) }
    var entryCoverMeasured by remember(runtimeRoute) { mutableStateOf(false) }
    val layoutDirection = LocalLayoutDirection.current
    val trailingDirection = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f

    BackHandler(
        enabled = retainedRoutes.isEmpty() && runtimeRoute == null && primaryBackStack.size > 1,
        onBack = ::goBackPrimary,
    )

    LaunchedEffect(runtimeRoute, entryCoverMeasured) {
        if (runtimeRoute != null && entryCoverMeasured) {
            HostTrace.bestEffortAsyncSection("tool.shell.enter") {
                withFrameNanos { }
                entryProgress.animateTo(0f, animationSpec = RetainedPageMotion)
            }
            runtimeLayerEnabled = true
        }
    }

    LaunchedEffect(runtimeRoute, runtimeLayerEnabled, runtimePresentationReady) {
        if (runtimeRoute != null && runtimeLayerEnabled && runtimePresentationReady && !runtimeLeaving) {
            withFrameNanos { }
            withFrameNanos { }
            entryCoverVisible = false
        }
    }

    val leaveRuntime: () -> Unit = {
        val activeRoute = runtimeRoute
        if (activeRoute != null && !runtimeLeaving) {
            runtimeLeaving = true
            coroutineScope.launch {
                if (runtimeLayerEnabled && !entryCoverVisible) {
                    sourceReturnProgress.snapTo(1f)
                    sourceAboveRuntime = true
                    withFrameNanos { }
                    sourceReturnProgress.animateTo(0f, animationSpec = RetainedPageMotion)
                } else {
                    sourceReturnProgress.snapTo(0f)
                    sourceAboveRuntime = true
                    withFrameNanos { }
                    entryProgress.animateTo(1f, animationSpec = RetainedPageMotion)
                }
                if (secondaryBackStack.lastOrNull() == activeRoute) {
                    secondaryBackStack.removeLastOrNull()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (runtimeRoute == null || sourceAboveRuntime) 2f else 0f)
                .graphicsLayer {
                    translationX = -trailingDirection * sourceReturnProgress.value * size.width
                }
                .then(
                    if (runtimeRoute != null && !sourceAboveRuntime) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
        ) {
            ToolBoxGlassActivity(
                active = retainedRoutes.isEmpty() && runtimeRoute == null,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (retainedRoutes.isEmpty() && runtimeRoute == null) {
                                Modifier
                            } else {
                                Modifier.clearAndSetSemantics { }
                            },
                        ),
                ) {
                    val selectedDestination = if (currentPrimaryRoute == SettingsRoute) {
                        MainDestination.Settings
                    } else {
                        MainDestination.Tools
                    }
                    PrimaryScreen(
                        selected = selectedDestination,
                        onDestination = ::navigateMain,
                        title = if (selectedDestination == MainDestination.Tools) "工具" else "设置",
                        onImport = if (selectedDestination == MainDestination.Tools) {
                            { picker.launch(ToolBoxOpenDocument.mimeTypes()) }
                        } else {
                            null
                        },
                    ) { padding, layout ->
                        when (currentPrimaryRoute) {
                            ToolManagerRoute, null -> ToolManagerRouteContent(
                                dependencies = dependencies,
                                viewModelStoreOwner = viewModelStoreOwner,
                                catalogViewModel = catalogViewModel,
                                importViewModel = importViewModel,
                                listState = toolsListState,
                                contentPadding = padding,
                                layout = layout,
                                // Freeze only while the settled runtime fully covers the base page.
                                // Resume before the source's return animation, not after route removal.
                                uiVisible = runtimeRoute == null || entryCoverVisible || sourceAboveRuntime,
                                onImport = { picker.launch(ToolBoxOpenDocument.mimeTypes()) },
                                onOpenDetails = { navigate(ToolDetailRoute(it)) },
                            )

                            SettingsRoute -> SettingsScreen(
                                viewModel = settingsViewModel,
                                contentPadding = padding,
                                onAppearance = { navigate(AppearanceRoute) },
                                onBackgroundSafeguards = { navigate(BackgroundSafeguardsRoute) },
                                onToolPermissions = { navigate(ToolPermissionsRoute) },
                                onDeveloperHelp = { navigate(DeveloperHelpRoute) },
                            )

                            else -> error("Route is not a primary destination: $currentPrimaryRoute")
                        }
                    }
                }
            }

            retainedRoutes.forEachIndexed { index, route ->
                key(route) {
                    RetainedSecondaryPage(
                        route = route,
                        isTop = runtimeRoute == null && index == retainedRoutes.lastIndex,
                        modifier = Modifier.zIndex(index + 1f),
                        onRemove = {
                            if (secondaryBackStack.lastOrNull() == route) {
                                secondaryBackStack.removeLastOrNull()
                            }
                        },
                    ) { requestBack, signalReady ->
                        SecondaryRouteContent(
                            route = route,
                            dependencies = dependencies,
                            viewModelStoreOwner = viewModelStoreOwner,
                            catalogViewModel = catalogViewModel,
                            importViewModel = importViewModel,
                            settingsViewModel = settingsViewModel,
                            onBack = requestBack,
                            onReady = signalReady,
                            onNavigate = ::navigate,
                        )
                    }
                }
            }
        }

        if (runtimeRoute != null && runtimeLayerEnabled) {
            key(runtimeRoute) {
                RuntimeSessionLayer(
                    route = runtimeRoute,
                    dependencies = dependencies,
                    onPresentationReady = { runtimePresentationReady = true },
                    onBack = leaveRuntime,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                        .then(
                            if (entryCoverVisible || sourceAboveRuntime) {
                                Modifier.clearAndSetSemantics { }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }

        if (runtimeRoute != null && entryCoverVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(3f)
                    .onGloballyPositioned { coordinates ->
                        if (coordinates.size.width > 0 && coordinates.size.height > 0) {
                            entryCoverMeasured = true
                        }
                    }
                    .graphicsLayer {
                        translationX = trailingDirection * entryProgress.value * size.width
                    },
            ) {
                RuntimeShellPreviewContent()
            }
        }

        if (runtimeRoute != null && (entryCoverVisible || sourceAboveRuntime)) {
            NavigationInputBlocker(Modifier.zIndex(4f))
        }
    }
}

@Composable
private fun RetainedSecondaryPage(
    route: ToolBoxRoute,
    isTop: Boolean,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit,
    content: @Composable (() -> Unit, () -> Unit) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val trailingDirection = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    val progress = remember(route) { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()
    var leaving by remember(route) { mutableStateOf(false) }
    var presentationReady by remember(route) { mutableStateOf(false) }
    var contentReady by remember(route) { mutableStateOf(false) }

    LaunchedEffect(route, presentationReady, contentReady) {
        if (!presentationReady || !contentReady) return@LaunchedEffect
        HostTrace.bestEffortAsyncSection("nav.enter") {
            withFrameNanos { }
            progress.animateTo(0f, animationSpec = RetainedPageMotion)
        }
    }

    val requestBack: () -> Unit = {
        if (isTop && !leaving) {
            leaving = true
            coroutineScope.launch {
                HostTrace.bestEffortAsyncSection("nav.return") {
                    progress.animateTo(1f, animationSpec = RetainedPageMotion)
                    onRemove()
                }
            }
        }
    }

    BackHandler(enabled = isTop, onBack = requestBack)
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                if (coordinates.size.width > 0 && coordinates.size.height > 0) {
                    presentationReady = true
                }
            }
            .graphicsLayer {
                translationX = trailingDirection * progress.value * size.width
            }
            .then(if (isTop) Modifier else Modifier.clearAndSetSemantics { }),
    ) {
        NavigationInputBlocker()
        ToolBoxGlassActivity(active = isTop && !leaving && progress.value <= 0.001f) {
            content(requestBack) { contentReady = true }
        }
    }
}

@Composable
private fun SecondaryRouteContent(
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

        is BackgroundTasksRoute -> BackgroundTasksScreen(
            toolId = route.toolId,
            operations = dependencies.backgroundOperations,
            runtimeSessions = dependencies.runtimeSessions,
            onBack = onBack,
            onReady = onReady,
        )

        ToolPermissionsRoute -> ToolPermissionsScreen(
            catalog = dependencies.repositories.catalog,
            onBack = onBack,
            onSelectTool = { onNavigate(PermissionCenterRoute(it)) },
            onReady = onReady,
        )

        AppearanceRoute -> AppearanceScreen(
            viewModel = settingsViewModel,
            onBack = onBack,
            onReady = onReady,
        )

        BackgroundSafeguardsRoute -> BackgroundSafeguardsScreen(
            viewModel = settingsViewModel,
            runtimeSessions = dependencies.runtimeSessions,
            onBack = onBack,
            onReady = onReady,
        )

        DeveloperHelpRoute -> DeveloperHelpScreen(
            onBack = onBack,
            onInstallExamples = importViewModel::installBundledExamples,
            onReady = onReady,
        )

        ToolManagerRoute,
        SettingsRoute,
        is RuntimeRoute,
        -> error("Route is not a retained secondary page: $route")
    }
}

@Composable
private fun ToolManagerRouteContent(
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
    val catalogState by catalogViewModel.state.collectAsStateWhileVisible(
        uiVisible || catalogViewModel.state.value.uninstallConfirmation != null,
    )
    val importState by importViewModel.state.collectAsStateWhileVisible(uiVisible)
    ToolManagerContent(
        state = catalogState,
        importState = importState,
        listState = listState,
        contentPadding = contentPadding,
        layout = layout,
        onAction = catalogViewModel::dispatch,
        onImport = onImport,
        onInstallExamples = importViewModel::installBundledExamples,
        onDismissImport = importViewModel::dismissMessage,
        onConfirmImport = importViewModel::confirmVersionReplacement,
        onCancelImport = importViewModel::cancelVersionReplacement,
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

@Composable
private fun RuntimeSessionLayer(
    route: RuntimeRoute,
    dependencies: HostDependencies,
    onPresentationReady: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val owner = remember(route) { RuntimeEntryViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    val viewModel = remember(route, dependencies, owner) {
        ViewModelProvider(
            owner,
            RuntimeViewModelFactory(route.toolId, dependencies),
        ).get("runtime:${route.toolId}", RuntimeViewModel::class.java)
    }
    Box(modifier) {
        RuntimeShellScreen(
            viewModel = viewModel,
            onBack = onBack,
            onPresentationReady = onPresentationReady,
        )
    }
}

@Composable
private fun NavigationInputBlocker(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    )
}

private class RuntimeEntryViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
