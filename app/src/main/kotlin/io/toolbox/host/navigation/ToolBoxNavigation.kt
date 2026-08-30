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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.HostDependencies
import io.toolbox.host.PermissionCenterViewModelFactory
import io.toolbox.host.RuntimeViewModelFactory
import io.toolbox.host.background.BackgroundTasksScreen
import io.toolbox.host.catalog.CatalogNavigationIntent
import io.toolbox.host.catalog.CatalogUiState
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
import io.toolbox.host.ui.RuntimeShellPreviewContent
import io.toolbox.host.ui.RuntimeShellScreen
import io.toolbox.host.ui.ToolDetailScreen
import io.toolbox.host.ui.ToolManagerScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.runtime.NavProgrammaticEasing
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

private val PrimaryTabMotion = NavMotion(
    programmatic = NavSettleSpec.Tween(
        durationMillis = 160,
        easing = NavProgrammaticEasing,
    ),
)

private val PrimaryTabFadeTransition = navGraphicsTransition(
    motion = PrimaryTabMotion,
    scrim = { 0f },
) { scope ->
    alpha = 1f - kotlin.math.abs(scope.relativeDepth).coerceIn(0f, 1f)
}

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
    val catalogState by catalogViewModel.state.collectAsStateWithLifecycle()
    val importState by importViewModel.state.collectAsStateWithLifecycle()
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
        if (primaryBackStack.lastOrNull() != route) primaryBackStack.add(route)
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
    val runtimeRoute = allSecondaryRoutes.lastOrNull() as? RuntimeRoute
    val retainedRoutes = if (runtimeRoute == null) allSecondaryRoutes else allSecondaryRoutes.dropLast(1)
    val runtimeTitle = runtimeRoute?.let { route ->
        catalogState.tools.firstOrNull { it.toolId == route.toolId }?.name
    } ?: "工具"
    val entryProgress = remember(runtimeRoute) { Animatable(if (runtimeRoute == null) 0f else 1f) }
    val sourceReturnProgress = remember(runtimeRoute) { Animatable(0f) }
    var runtimeLayerEnabled by remember(runtimeRoute) { mutableStateOf(false) }
    var entryCoverVisible by remember(runtimeRoute) { mutableStateOf(runtimeRoute != null) }
    var sourceAboveRuntime by remember(runtimeRoute) { mutableStateOf(false) }
    var runtimePresentationReady by remember(runtimeRoute) { mutableStateOf(false) }
    var runtimeLeaving by remember(runtimeRoute) { mutableStateOf(false) }
    val layoutDirection = LocalLayoutDirection.current
    val trailingDirection = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f

    LaunchedEffect(runtimeRoute) {
        if (runtimeRoute != null) {
            withFrameNanos { }
            entryProgress.animateTo(0f, animationSpec = RetainedPageMotion)
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
            NavDisplay(
                backStack = primaryBackStack,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (retainedRoutes.isEmpty() && runtimeRoute == null) {
                            Modifier
                        } else {
                            Modifier.clearAndSetSemantics { }
                        },
                    ),
                onBack = ::goBackPrimary,
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
                    ) { requestBack ->
                        SecondaryRouteContent(
                            route = route,
                            dependencies = dependencies,
                            viewModelStoreOwner = viewModelStoreOwner,
                            catalogViewModel = catalogViewModel,
                            catalogState = catalogState,
                            importViewModel = importViewModel,
                            onBack = requestBack,
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
                        .zIndex(1f),
                )
            }
        }

        if (runtimeRoute != null && entryCoverVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(3f)
                    .graphicsLayer {
                        translationX = trailingDirection * entryProgress.value * size.width
                    },
            ) {
                RuntimeShellPreviewContent(
                    title = runtimeTitle,
                    onBack = leaveRuntime,
                )
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
    content: @Composable (() -> Unit) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val trailingDirection = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    val progress = remember(route) { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()
    var leaving by remember(route) { mutableStateOf(false) }

    LaunchedEffect(route) {
        withFrameNanos { }
        progress.animateTo(0f, animationSpec = RetainedPageMotion)
    }

    val requestBack: () -> Unit = {
        if (isTop && !leaving) {
            leaving = true
            coroutineScope.launch {
                progress.animateTo(1f, animationSpec = RetainedPageMotion)
                onRemove()
            }
        }
    }

    BackHandler(enabled = isTop, onBack = requestBack)
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = trailingDirection * progress.value * size.width
            }
            .then(if (isTop) Modifier else Modifier.clearAndSetSemantics { }),
    ) {
        NavigationInputBlocker()
        content(requestBack)
    }
}

@Composable
private fun SecondaryRouteContent(
    route: ToolBoxRoute,
    dependencies: HostDependencies,
    viewModelStoreOwner: ViewModelStoreOwner,
    catalogViewModel: CatalogViewModel,
    catalogState: CatalogUiState,
    importViewModel: ImportViewModel,
    onBack: () -> Unit,
    onNavigate: (ToolBoxRoute) -> Unit,
) {
    when (route) {
        is ToolDetailRoute -> ToolDetailScreen(
            toolId = route.toolId,
            state = catalogState,
            onAction = catalogViewModel::dispatch,
            onBack = onBack,
            onPermissions = { onNavigate(PermissionCenterRoute(it)) },
            onBackground = { onNavigate(BackgroundTasksRoute(it)) },
        )

        is PermissionCenterRoute -> {
            val permissionViewModel = remember(route.toolId, dependencies) {
                ViewModelProvider(
                    viewModelStoreOwner,
                    PermissionCenterViewModelFactory(route.toolId, dependencies),
                ).get("permission:${route.toolId}", PermissionCenterViewModel::class.java)
            }
            PermissionCenterScreen(permissionViewModel, onBack = onBack)
        }

        is BackgroundTasksRoute -> BackgroundTasksScreen(
            toolId = route.toolId,
            operations = dependencies.backgroundOperations,
            onBack = onBack,
        )

        ToolPermissionsRoute -> ToolPermissionsScreen(
            catalog = dependencies.repositories.catalog,
            onBack = onBack,
            onSelectTool = { onNavigate(PermissionCenterRoute(it)) },
        )

        DeveloperHelpRoute -> DeveloperHelpScreen(
            onBack = onBack,
            onInstallExamples = importViewModel::installBundledExamples,
        )

        ToolManagerRoute,
        SettingsRoute,
        is RuntimeRoute,
        -> error("Route is not a retained secondary page: $route")
    }
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
