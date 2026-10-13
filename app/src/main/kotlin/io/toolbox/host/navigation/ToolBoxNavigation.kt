package io.toolbox.host.navigation

import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import android.content.ContentResolver
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.runtime.SideEffect
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
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
import io.toolbox.core.ui.component.ToolBoxMotion
import io.toolbox.host.HostDependencies
import io.toolbox.host.HostFeatureViewModelFactory
import io.toolbox.host.PermissionCenterViewModelFactory
import io.toolbox.host.RuntimeViewModelFactory
import io.toolbox.host.HostTrace
import io.toolbox.host.background.AndroidBackgroundSystemStateSource
import io.toolbox.host.background.BackgroundSafeguardsScreen
import io.toolbox.host.background.BackgroundSafeguardsViewModel
import io.toolbox.host.background.BackgroundSafeguardsViewModelFactory
import io.toolbox.host.background.BackgroundTasksScreen
import io.toolbox.host.background.BackgroundTasksViewModel
import io.toolbox.host.background.BackgroundTasksViewModelFactory
import io.toolbox.host.catalog.CatalogNavigationIntent
import io.toolbox.host.catalog.CatalogViewModel
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.RunningToolsViewModel
import io.toolbox.host.help.DeveloperHelpScreen
import io.toolbox.host.importflow.ImportViewModel
import io.toolbox.host.importflow.rememberPackageImportPicker
import io.toolbox.host.permissions.PermissionCenterScreen
import io.toolbox.host.permissions.PermissionCenterViewModel
import io.toolbox.host.permissions.ToolPermissionsScreen
import io.toolbox.host.permissions.ToolPermissionsViewModel
import io.toolbox.host.permissions.ToolPermissionsViewModelFactory
import io.toolbox.host.runtime.RuntimeViewModel
import io.toolbox.host.settings.AppearanceScreen
import io.toolbox.host.settings.AboutScreen
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

// Full-width travel needs a finer settle threshold than the default 1% to avoid a final jump.
private val RetainedPageMotion = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = 0.0005f)

/** Fraction of the page width the predictive-back gesture may drag the page before commit. */
private const val PredictiveBackTravel = 1f
/** UINavigationController geometry: the covered page moves back by 30% and dims slightly. */
private const val IosParallax = 0.3f
private const val IosDim = 0.12f
private val PushShadowElevation = 18.dp

/** Shared-axis travel, read inside graphics layers (Density receiver). */
private val androidx.compose.ui.graphics.GraphicsLayerScope.PageShiftPx: Float
    get() = ToolBoxMotion.PageShift.toPx()

@Composable
internal fun ToolBoxNavigation(
    dependencies: HostDependencies,
    viewModelStoreOwner: ViewModelStoreOwner,
    catalogViewModel: CatalogViewModel,
    importViewModel: ImportViewModel,
    settingsViewModel: SettingsViewModel,
    contentResolver: ContentResolver,
) {
    val primaryBackStack = rememberNavBackStack<ToolBoxRoute>(HomeRoute)
    val secondaryBackStack = rememberNavBackStack<ToolBoxRoute>()
    val toolsListState = rememberLazyListState()
    val homeListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    var homeEditing by rememberSaveable { mutableStateOf(false) }
    var homeCreatingGroup by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val onPickPackage = rememberPackageImportPicker(importViewModel, contentResolver)

    fun navigate(route: ToolBoxRoute) {
        if (secondaryBackStack.lastOrNull() != route) secondaryBackStack.add(route)
    }

    fun navigateMain(destination: MainDestination) {
        homeEditing = false
        homeCreatingGroup = false
        secondaryBackStack.clear()
        while (primaryBackStack.size > 1) primaryBackStack.removeLastOrNull()
        val route = when (destination) {
            MainDestination.Home -> HomeRoute
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
    // Presentation identity only: reuse the cached catalog without keeping the covered page subscribed.
    val runtimeTitle = remember(catalogViewModel, runtimeRoute?.toolId) {
        runtimeRoute?.toolId?.let { id ->
            catalogViewModel.state.value.tools.firstOrNull { it.toolId == id }?.name
        }
    }
    val retainedRoutes = if (runtimeRoute == null) allSecondaryRoutes else allSecondaryRoutes.dropLast(1)
    // Each retained page's push progress (1 = hidden, 0 = shown) is hoisted so the page beneath
    // it can fade out as it fades in (fade-through); two top bars never overlap mid-transition.
    val pageProgress = remember { HashMap<ToolBoxRoute, Animatable<Float, AnimationVector1D>>() }
    fun progressFor(route: ToolBoxRoute) = pageProgress.getOrPut(route) { Animatable(1f) }
    SideEffect { pageProgress.keys.retainAll(retainedRoutes.toSet()) }
    val firstRetainedProgress = retainedRoutes.firstOrNull()?.let(::progressFor)
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
            // Shown only at the midpoint of a fade-through; matches every page's base colour.
            .background(ToolBoxThemeTokens.colors.background)
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (runtimeRoute == null || sourceAboveRuntime) 2f else 0f)
                .graphicsLayer {
                    val enteringDepth = if (
                        runtimeRoute != null && entryCoverVisible && !sourceAboveRuntime
                    ) {
                        // Fade through: the source is gone before the runtime cover appears.
                        1f - ToolBoxMotion.fadeThroughOutgoingAlpha(1f - entryProgress.value.coerceIn(0f, 1f))
                    } else {
                        0f
                    }
                    // This layer also hosts the retained secondary pages, so only the runtime
                    // transition may fade it. Covering by a secondary page fades the primary
                    // screen alone (inner box below), never the pages stacked above it.
                    val runtimeDepth = maxOf(enteringDepth, sourceReturnProgress.value).coerceIn(0f, 1f)
                    translationX = -trailingDirection * runtimeDepth * PageShiftPx
                    alpha = 1f - runtimeDepth
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
                        .graphicsLayer {
                            // iOS push: the primary screen parallaxes back by a third under the
                            // first secondary page and dims instead of fading out.
                            val coverage = firstRetainedProgress?.let { 1f - it.value.coerceIn(0f, 1f) } ?: 0f
                            translationX = -trailingDirection * coverage * IosParallax * size.width
                        }
                        .drawWithContent {
                            drawContent()
                            val coverage = firstRetainedProgress?.let { 1f - it.value.coerceIn(0f, 1f) } ?: 0f
                            if (coverage > 0f) drawRect(Color.Black.copy(alpha = IosDim * coverage))
                        }
                        .then(
                            if (retainedRoutes.isEmpty() && runtimeRoute == null) {
                                Modifier
                            } else {
                                Modifier.clearAndSetSemantics { }
                            },
                        ),
                ) {
                    val selectedDestination = when (currentPrimaryRoute) {
                        SettingsRoute -> MainDestination.Settings
                        ToolManagerRoute -> MainDestination.Tools
                        else -> MainDestination.Home
                    }
                    PrimaryScreen(
                        selected = selectedDestination,
                        onDestination = ::navigateMain,
                        title = selectedDestination.label,
                        onImport = if (selectedDestination != MainDestination.Settings) {
                            onPickPackage
                        } else {
                            null
                        },
                        organizeEditing = selectedDestination == MainDestination.Home && homeEditing,
                        onOrganize = if (selectedDestination == MainDestination.Home) ({ homeEditing = !homeEditing }) else null,
                        onCreateGroup = if (selectedDestination == MainDestination.Home && homeEditing) ({ homeCreatingGroup = true }) else null,
                        listStates = mapOf(
                            MainDestination.Home to homeListState,
                            MainDestination.Tools to toolsListState,
                            MainDestination.Settings to settingsListState,
                        ),
                    ) { destination, padding, layout ->
                        when (destination) {
                            MainDestination.Home, MainDestination.Tools -> ToolManagerRouteContent(
                                home = destination == MainDestination.Home,
                                editing = homeEditing,
                                onEditingChange = { homeEditing = it },
                                creatingGroup = homeCreatingGroup,
                                onDismissCreateGroup = { homeCreatingGroup = false },
                                dependencies = dependencies,
                                viewModelStoreOwner = viewModelStoreOwner,
                                catalogViewModel = catalogViewModel,
                                importViewModel = importViewModel,
                                listState = if (destination == MainDestination.Home) homeListState else toolsListState,
                                contentPadding = padding,
                                layout = layout,
                                // Freeze only while the settled runtime fully covers the base page.
                                // Resume before the source's return animation, not after route removal.
                                uiVisible = destination == selectedDestination && (runtimeRoute == null || entryCoverVisible || sourceAboveRuntime),
                                onImport = onPickPackage,
                                onOpenDetails = { navigate(ToolDetailRoute(it)) },
                            )

                            MainDestination.Settings -> SettingsScreen(
                                viewModel = settingsViewModel,
                                listState = settingsListState,
                                contentPadding = padding,
                                onAppearance = { navigate(AppearanceRoute) },
                                onBackgroundSafeguards = { navigate(BackgroundSafeguardsRoute) },
                                onToolPermissions = { navigate(ToolPermissionsRoute) },
                                onDeveloperHelp = { navigate(DeveloperHelpRoute) },
                                onAbout = { navigate(AboutRoute) },
                                onBackupRestore = { navigate(BackupRestoreRoute) },
                                onUpdates = { navigate(UpdateRoute) },
                            )
                        }
                    }
                }
            }

            retainedRoutes.forEachIndexed { index, route ->
                key(route) {
                    RetainedSecondaryPage(
                        route = route,
                        progress = progressFor(route),
                        coveredBy = retainedRoutes.getOrNull(index + 1)?.let(::progressFor),
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
                    toolName = runtimeTitle,
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
                        val motion = entryProgress.value.coerceIn(0f, 1f)
                        translationX = trailingDirection * motion * PageShiftPx
                        alpha = ToolBoxMotion.fadeThroughIncomingAlpha(1f - motion)
                    },
            ) {
                RuntimeShellPreviewContent(toolName = runtimeTitle)
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
    progress: Animatable<Float, AnimationVector1D>,
    coveredBy: Animatable<Float, AnimationVector1D>?,
    isTop: Boolean,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit,
    content: @Composable (() -> Unit, () -> Unit) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val trailingDirection = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
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

    // Predictive back: the page follows the system back gesture and either springs away on
    // commit or springs back on cancel. A plain Back press delivers no progress events and
    // takes the same commit path as the on-screen back button.
    PredictiveBackHandler(enabled = isTop && !leaving) { events ->
        try {
            events.collect { event ->
                progress.snapTo(PredictiveBackTravel * event.progress)
            }
            requestBack()
        } catch (cancelled: CancellationException) {
            coroutineScope.launch { progress.animateTo(0f, animationSpec = RetainedPageMotion) }
            throw cancelled
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                if (coordinates.size.width > 0 && coordinates.size.height > 0) {
                    presentationReady = true
                }
            }
            .graphicsLayer {
                // iOS push: the page slides the full width over the one beneath, which parallaxes
                // back by a third and dims. Predictive back drags the page with the finger.
                val motion = progress.value.coerceIn(0f, 1f)
                val coverage = coveredBy?.let { 1f - it.value.coerceIn(0f, 1f) } ?: 0f
                translationX = trailingDirection * (motion - coverage * IosParallax) * size.width
                shadowElevation = if (motion > 0.001f && motion < 0.999f) PushShadowElevation.toPx() else 0f
                clip = false
            }
            .drawWithContent {
                drawContent()
                val coverage = coveredBy?.let { 1f - it.value.coerceIn(0f, 1f) } ?: 0f
                if (coverage > 0f) drawRect(Color.Black.copy(alpha = IosDim * coverage))
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
private fun ToolManagerRouteContent(
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

@Composable
private fun RuntimeSessionLayer(
    route: RuntimeRoute,
    dependencies: HostDependencies,
    onPresentationReady: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    toolName: String? = null,
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
            toolName = toolName,
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
