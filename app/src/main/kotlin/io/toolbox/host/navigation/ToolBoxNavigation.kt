package io.toolbox.host.navigation

import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import android.content.ContentResolver
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModelStoreOwner
import io.toolbox.core.ui.component.ToolBoxGlassActivity
import io.toolbox.core.ui.component.ToolBoxMotion
import io.toolbox.host.HostDependencies
import io.toolbox.host.RuntimeViewModelFactory
import io.toolbox.host.catalog.CatalogNavigationIntent
import io.toolbox.host.catalog.CatalogViewModel
import io.toolbox.host.importflow.ImportViewModel
import io.toolbox.host.importflow.rememberPackageImportPicker
import io.toolbox.host.settings.SettingsScreen
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.host.ui.MainDestination
import io.toolbox.host.ui.PrimaryScreen
import io.toolbox.host.ui.RuntimeShellPreviewContent
import kotlinx.coroutines.Job
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack

// Full-width travel needs a finer settle threshold than the default 1% to avoid a final jump.
internal val RetainedPageMotion = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = 0.0005f)

/** Fraction of the page width the predictive-back gesture may drag the page before commit. */
internal const val PredictiveBackTravel = 1f
/** UINavigationController geometry: the covered page moves back by 30% and dims slightly. */
internal const val IosParallax = 0.3f
internal const val IosDim = 0.12f
internal val PushShadowElevation = 18.dp

// Glass thresholds shared by the primary screen and the retained pages: glass stays live while
// any part of its layer can be seen and pauses only once the layer is fully hidden.
internal const val GlassHiddenEdge = 0.001f

/** True once the page above (whose push progress is [coveringProgress]) has fully slid in. */
internal fun pageFullyCovered(coveringProgress: Float?): Boolean =
    coveringProgress != null && coveringProgress <= GlassHiddenEdge

/** True once a retained page's own push progress has carried it fully off screen. */
internal fun pageOffScreen(progress: Float): Boolean = progress >= 1f - GlassHiddenEdge

/**
 * One tool's runtime transition: the fade-through into the tool, the return to the source, and
 * the flags that gate each step. A new holder is made per route; [rememberRuntimeTransition]
 * seeds it from the holder it replaces so a tool switch continues instead of replaying.
 */
@Stable
internal class RuntimeTransition(
    val route: RuntimeRoute?,
    initialEntry: Float,
    /** The replaced tool was returning, so this tool's cover sits beneath the fading source. */
    val coverBeneathSource: Boolean,
    /**
     * The activity was recreated (rotation) mid-return: the source shows on top at once and the
     * caller finishes the return by removing the route, instead of the tool landing settled.
     */
    val returnInterrupted: Boolean = false,
    /**
     * The replaced tool was returning while its cover still showed (Back while loading), with the
     * source opaque beneath it: the source stays opaque under this tool's cover until the entry
     * finishes, instead of jumping to the fade-through alpha.
     */
    val sourceHeldUnderCover: Boolean = false,
    /** Survives recreation: true while this tool is returning to its source. */
    private val savedLeaving: MutableState<Boolean> = mutableStateOf(false),
) {

    /** 1 = source fully shown, 0 = cover (then tool) fully shown. */
    val entryProgress = Animatable(initialEntry)
    /** 1 = tool fully shown under the returning source, 0 = source fully shown. */
    val sourceReturnProgress = Animatable(0f)
    var runtimeLayerEnabled by mutableStateOf(false)
    var entryCoverVisible by mutableStateOf(route != null && !returnInterrupted)
    var sourceAboveRuntime by mutableStateOf(returnInterrupted || sourceHeldUnderCover)
    var runtimePresentationReady by mutableStateOf(false)
    private var leaving by mutableStateOf(returnInterrupted)
    var runtimeLeaving: Boolean
        get() = leaving
        set(value) {
            leaving = value
            savedLeaving.value = value
        }
    var entryCoverMeasured by mutableStateOf(false)
    /** The running return (or the animation cancelling it), so a reopen or Back can stop it. */
    var returnJob: Job? = null
    /**
     * The tool was reopened while Back may still be saving for its exit; a return that save then
     * starts is skipped, and the runtime cancels the exit. Cleared once the runtime handled it.
     */
    var reopenRequested = false

    /** Returning from a settled tool: the source fades in over the tool, not over the cover. */
    val returningFromSettled: Boolean
        get() = sourceAboveRuntime && !entryCoverVisible

    /** The source sits opaque beneath a cover that is still showing (returning while loading). */
    val sourceUnderCover: Boolean
        get() = sourceAboveRuntime && entryCoverVisible

    /**
     * Whether the source layer is out of the semantics tree: whenever the tool is in front of it,
     * including while it is held opaque under a cover that is coming in (not going out).
     */
    val sourceSemanticsHidden: Boolean
        get() = route != null && (!sourceAboveRuntime || (entryCoverVisible && !runtimeLeaving))

    /**
     * How far the source layer has gone (0 = fully shown, 1 = gone); its alpha is 1 minus this.
     * During the fade-through entry the source fades out first; while it sits above the tool it
     * follows [sourceReturnProgress].
     */
    fun sourceDepth(): Float {
        val enteringDepth = if (route != null && entryCoverVisible && !sourceAboveRuntime) {
            1f - ToolBoxMotion.fadeThroughOutgoingAlpha(1f - entryProgress.value.coerceIn(0f, 1f))
        } else {
            0f
        }
        return maxOf(enteringDepth, sourceReturnProgress.value).coerceIn(0f, 1f)
    }

    /** Starts cancelling the return; false when this tool is not returning. */
    fun beginReturnCancel(): Boolean {
        if (route == null || !runtimeLeaving) return false
        runtimeLeaving = false
        return true
    }

    /** Brings the tool back the way the return took it away, then drops the source below it. */
    suspend fun animateReturnCancel(spec: AnimationSpec<Float> = RetainedPageMotion) {
        if (entryCoverVisible) {
            entryProgress.animateTo(0f, animationSpec = spec)
            // Back may have cut the entry short before it enabled the tool.
            runtimeLayerEnabled = true
        } else {
            sourceReturnProgress.animateTo(1f, animationSpec = spec)
        }
        sourceAboveRuntime = false
        sourceReturnProgress.snapTo(0f)
    }

    /** True once the transition fully hides the source layer (primary screen and retained pages). */
    val sourceHidden: Boolean
        get() = sourceLayerHiddenByRuntime(
            runtimeOpen = route != null,
            sourceAboveRuntime = sourceAboveRuntime,
            sourceReturnProgress = sourceReturnProgress.value,
            entryCoverVisible = entryCoverVisible,
            entryProgress = entryProgress.value,
        )

    /** Where this transition stands, as the entry progress a replacing tool should start from. */
    fun entryEquivalent(): Float = runtimeEntryEquivalent(
        sourceAboveRuntime = returningFromSettled,
        sourceReturnProgress = sourceReturnProgress.value,
        entryProgress = entryProgress.value,
    )
}

/**
 * The transition for [runtimeRoute], recreated when the route changes. A tool that replaces
 * another starts where the replaced tool's transition stood; a tool restored after the activity
 * was recreated (rotation) starts settled instead of replaying its entry over the source.
 */
@Composable
internal fun rememberRuntimeTransition(runtimeRoute: RuntimeRoute?, generation: Int = 0): RuntimeTransition {
    val previous = remember { arrayOfNulls<RuntimeTransition>(1) }
    // Saved per tool, so it survives recreation; reset whenever a different tool opens.
    var shownBefore by rememberSaveable(runtimeRoute?.toolId) { mutableStateOf(false) }
    val savedLeaving = rememberSaveable(runtimeRoute?.toolId) { mutableStateOf(false) }
    // Keyed on the push generation too: the same tool pushed again right after its return popped
    // it (before a recomposition saw the pop) needs a fresh holder, not the leaving one.
    val transition = remember(runtimeRoute, generation) {
        val replaced = previous[0]?.takeIf { runtimeRoute != null }
        RuntimeTransition(
            route = runtimeRoute,
            initialEntry = runtimeEntryStart(
                opening = runtimeRoute != null,
                carriedEntry = replaced?.entryEquivalent() ?: if (shownBefore) 0f else null,
            ),
            // Only a settled tool sits beneath the returning source; a cover still fading in
            // (Back while loading) hands its own entry progress on and stays on top.
            coverBeneathSource = replaced?.returningFromSettled == true,
            sourceHeldUnderCover = replaced?.sourceUnderCover == true,
            // A switch opens normally; only a restored tool resumes its saved return.
            returnInterrupted = runtimeRoute != null && replaced == null && savedLeaving.value,
            savedLeaving = savedLeaving,
        )
    }
    SideEffect {
        previous[0] = transition.takeIf { runtimeRoute != null }
        if (runtimeRoute != null) shownBefore = true
        savedLeaving.value = transition.runtimeLeaving
    }
    return transition
}

/** Whether [transition] fully hides the source layer, derived so only that edge recomposes. */
@Composable
internal fun rememberSourceHidden(transition: RuntimeTransition): State<Boolean> =
    remember(transition) { derivedStateOf { transition.sourceHidden } }

/**
 * The primary screen's glass is live unless [transition] hides its layer or the first secondary
 * page fully covers it. Keyed on the transition, which is recreated per route: a stale capture
 * would keep reading the replaced tool's state.
 */
@Composable
internal fun rememberPrimaryGlassLive(
    transition: RuntimeTransition,
    firstRetainedProgress: Animatable<Float, AnimationVector1D>?,
): State<Boolean> = remember(transition, firstRetainedProgress) {
    derivedStateOf { !transition.sourceHidden && !pageFullyCovered(firstRetainedProgress?.value) }
}

/**
 * The outgoing tool's transition expressed as entry progress (1 = source fully shown, 0 = cover
 * fully shown), so the incoming tool shows the source layer at the same alpha. While returning,
 * the source alpha is 1 - [sourceReturnProgress]; the entry progress with that alpha is
 * 1 - FadeThroughSplit * [sourceReturnProgress].
 */
internal fun runtimeEntryEquivalent(
    sourceAboveRuntime: Boolean,
    sourceReturnProgress: Float,
    entryProgress: Float,
): Float =
    if (sourceAboveRuntime) {
        1f - ToolBoxMotion.FadeThroughSplit * sourceReturnProgress.coerceIn(0f, 1f)
    } else {
        entryProgress.coerceIn(0f, 1f)
    }

/** What opening a route does when that tool is already on top of the stack. */
internal enum class RuntimeReopen {
    /** Not the tool on top: push the route. */
    None,
    /** The tool is returning: cancel the return and keep it. */
    CancelReturn,
    /** The tool is on top: cancel an exit Back may still be saving for (no-op otherwise). */
    CancelExit,
}

internal fun runtimeReopen(route: ToolBoxRoute, top: RuntimeRoute?, leaving: Boolean): RuntimeReopen = when {
    top == null || route != top -> RuntimeReopen.None
    leaving -> RuntimeReopen.CancelReturn
    else -> RuntimeReopen.CancelExit
}

/** Entry progress a tool starts at: 1 for a fresh open, or where the replaced tool stood. */
internal fun runtimeEntryStart(opening: Boolean, carriedEntry: Float?): Float = when {
    !opening -> 0f
    carriedEntry != null -> carriedEntry.coerceIn(0f, 1f)
    else -> 1f
}

/** True once the runtime transition has fully hidden the source layer (primary + retained pages). */
internal fun sourceLayerHiddenByRuntime(
    runtimeOpen: Boolean,
    sourceAboveRuntime: Boolean,
    sourceReturnProgress: Float,
    entryCoverVisible: Boolean,
    entryProgress: Float,
): Boolean = when {
    !runtimeOpen -> false
    sourceAboveRuntime -> sourceReturnProgress >= 1f - GlassHiddenEdge
    entryCoverVisible ->
        ToolBoxMotion.fadeThroughOutgoingAlpha(1f - entryProgress.coerceIn(0f, 1f)) <= GlassHiddenEdge
    else -> true
}

/**
 * Pushes [route] onto the secondary stack. Only the top page can be a running tool, so opening a
 * tool (for example from a notification or shortcut while another tool is open) replaces the
 * tool page already on the stack instead of stacking a second one beneath it.
 */
/** Pushes [route] unless it is already on top; true when it was pushed. */
internal fun MutableList<NavKey>.pushSecondary(route: ToolBoxRoute): Boolean {
    if (lastOrNull() == route) return false
    if (route is RuntimeRoute) removeAll { it is RuntimeRoute }
    add(route)
    return true
}

/** Pages drawn beneath the top: never a tool page, which only renders as the top layer. */
internal fun retainedSecondaryRoutes(routes: List<ToolBoxRoute>): List<ToolBoxRoute> =
    routes.filterNot { it is RuntimeRoute }

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
    val onPickPackage = rememberPackageImportPicker(importViewModel, contentResolver)

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

    val allSecondaryRoutes = secondaryBackStack.filterIsInstance<ToolBoxRoute>()
    val currentPrimaryRoute = primaryBackStack.lastOrNull()
    val runtimeRoute = allSecondaryRoutes.lastOrNull() as? RuntimeRoute
    // Presentation identity only: reuse the cached catalog without keeping the covered page subscribed.
    val runtimeTitle = remember(catalogViewModel, runtimeRoute?.toolId) {
        runtimeRoute?.toolId?.let { id ->
            catalogViewModel.state.value.tools.firstOrNull { it.toolId == id }?.name
        }
    }
    val retainedRoutes = retainedSecondaryRoutes(allSecondaryRoutes)
    // Each retained page's push progress (1 = hidden, 0 = shown) is hoisted so the page beneath
    // it can fade out as it fades in (fade-through); two top bars never overlap mid-transition.
    val pageProgress = remember { HashMap<ToolBoxRoute, Animatable<Float, AnimationVector1D>>() }
    fun progressFor(route: ToolBoxRoute) = pageProgress.getOrPut(route) { Animatable(1f) }
    SideEffect { pageProgress.keys.retainAll(retainedRoutes.toSet()) }
    val firstRetainedProgress = retainedRoutes.firstOrNull()?.let(::progressFor)
    val navigator = rememberRuntimeNavigator(secondaryBackStack, runtimeRoute)
    val transition = navigator.transition
    val entryProgress = transition.entryProgress
    var entryCoverVisible by transition::entryCoverVisible
    var sourceAboveRuntime by transition::sourceAboveRuntime
    var entryCoverMeasured by transition::entryCoverMeasured
    val layoutDirection = LocalLayoutDirection.current
    val trailingDirection = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f

    BackHandler(
        enabled = retainedRoutes.isEmpty() && runtimeRoute == null && primaryBackStack.size > 1,
        onBack = ::goBackPrimary,
    )

    fun navigate(route: ToolBoxRoute) = navigator.navigate(route)

    val currentNavigate by rememberUpdatedState<(ToolBoxRoute) -> Unit>(::navigate)
    LaunchedEffect(catalogViewModel) {
        catalogViewModel.navigation.collect { intent ->
            when (intent) {
                is CatalogNavigationIntent.RequestRuntimeLaunch -> currentNavigate(RuntimeRoute(intent.toolId))
            }
        }
    }

    // The primary screen's glass (tab bar, top bar) stays live for as long as any of it can be
    // seen, through the runtime fade-through and under a sliding secondary page, and pauses only
    // once the screen is fully hidden: a stand-in swapped in mid-transition reads as the bar
    // changing material. Derived, so only the hidden edge recomposes the glass readers.
    val sourceHiddenByRuntime by rememberSourceHidden(transition)
    val primaryGlassLive by rememberPrimaryGlassLive(transition, firstRetainedProgress)

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
                    // This layer also hosts the retained secondary pages, so only the runtime
                    // transition may fade it. Covering by a secondary page fades the primary
                    // screen alone (inner box below), never the pages stacked above it.
                    val runtimeDepth = transition.sourceDepth()
                    translationX = -trailingDirection * runtimeDepth * PageShiftPx
                    alpha = 1f - runtimeDepth
                }
                .then(
                    if (transition.sourceSemanticsHidden) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
        ) {
            ToolBoxGlassActivity(active = primaryGlassLive) {
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
                        onCreateGroup = if (selectedDestination == MainDestination.Home) ({ homeCreatingGroup = true }) else null,
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
                        hiddenByRuntime = sourceHiddenByRuntime,
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

        NavigatorRuntimeLayer(
            navigator = navigator,
            transition = transition,
            viewModelFactory = { route -> RuntimeViewModelFactory(route.toolId, dependencies) },
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

        if (runtimeRoute != null && entryCoverVisible) {
            // Keyed per transition: a tool switch (or the same tool pushed again) gives the new
            // cover a fresh node, so its first placement reports entryCoverMeasured.
            key(transition) {
                // After a switch during a return the source is still half-transparent; the new
                // cover fills in opaque beneath it (where the replaced tool was) until the
                // source has faded out, instead of leaving the bare background showing through.
                val coverBeneath = transition.coverBeneathSource && !sourceHiddenByRuntime
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (coverBeneath) -1f else 3f)
                        .onGloballyPositioned { coordinates ->
                            if (coordinates.size.width > 0 && coordinates.size.height > 0) {
                                entryCoverMeasured = true
                            }
                        }
                        .graphicsLayer {
                            val motion = entryProgress.value.coerceIn(0f, 1f)
                            if (transition.coverBeneathSource) {
                                // Stands in for the replaced tool, which sat settled: no slide.
                                translationX = 0f
                                alpha = 1f
                            } else {
                                translationX = trailingDirection * motion * PageShiftPx
                                alpha = ToolBoxMotion.fadeThroughIncomingAlpha(1f - motion)
                            }
                        },
                ) {
                    RuntimeShellPreviewContent(toolName = runtimeTitle)
                }
            }
        }

        if (runtimeRoute != null && (entryCoverVisible || sourceAboveRuntime)) {
            NavigationInputBlocker(Modifier.zIndex(4f))
        }
    }
}
