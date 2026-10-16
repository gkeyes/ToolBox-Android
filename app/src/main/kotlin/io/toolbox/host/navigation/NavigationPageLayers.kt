package io.toolbox.host.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.activity.compose.PredictiveBackHandler
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import io.toolbox.core.ui.component.ToolBoxGlassActivity
import io.toolbox.host.HostDependencies
import io.toolbox.host.RuntimeViewModelFactory
import io.toolbox.host.HostTrace
import io.toolbox.host.runtime.RuntimeViewModel
import io.toolbox.host.ui.RuntimeShellScreen
import kotlinx.coroutines.launch

@Composable
internal fun RetainedSecondaryPage(
    route: ToolBoxRoute,
    progress: Animatable<Float, AnimationVector1D>,
    coveredBy: Animatable<Float, AnimationVector1D>?,
    isTop: Boolean,
    hiddenByRuntime: Boolean,
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
    // The page's glass chrome stays live for as long as any of the page can be seen: while it
    // slides in or out, follows predictive back, or sits under a page sliding over it. It pauses
    // only off screen or fully covered, so the bars never swap material mid-slide. Derived, so a
    // running transition recomposes only on those edges, not every frame.
    val glassVisible by remember(progress, coveredBy) {
        derivedStateOf { !pageOffScreen(progress.value) && !pageFullyCovered(coveredBy?.value) }
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
        ToolBoxGlassActivity(active = glassVisible && !hiddenByRuntime) {
            content(requestBack) { contentReady = true }
        }
    }
}

@Composable
internal fun RuntimeSessionLayer(
    route: RuntimeRoute,
    dependencies: HostDependencies,
    onPresentationReady: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    toolName: String? = null,
    reopenRevision: Int = 0,
    onReopenHandled: () -> Unit = {},
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
    // The tool was opened again while Back was taking it away: undo the exit Back started (a save
    // still running, or the sealed bridge and spent exit confirmation). Only revisions after this
    // view model was made count.
    RuntimeReopenEffect(viewModel, reopenRevision) {
        viewModel.reopen()
        onReopenHandled()
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
internal fun NavigationInputBlocker(modifier: Modifier = Modifier) {
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

/** Calls [onReopen] for each [revision] after the first one seen for [key]. */
@Composable
internal fun RuntimeReopenEffect(key: Any, revision: Int, onReopen: () -> Unit) {
    val seenRevision = remember(key) { revision }
    val currentOnReopen by rememberUpdatedState(onReopen)
    LaunchedEffect(key, revision) {
        if (revision != seenRevision) currentOnReopen()
    }
}
