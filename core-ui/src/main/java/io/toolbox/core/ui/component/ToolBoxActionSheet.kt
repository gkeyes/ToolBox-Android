package io.toolbox.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.view.ViewTreeObserver
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

private val LocalSheetHeaderDrag = staticCompositionLocalOf<Modifier> { Modifier }

/**
 * True inside a glass overlay sheet: grouped cards there use the translucent
 * [io.toolbox.core.ui.theme.ToolBoxMaterialTokens.sheetCard] fill so the sheet material still reads.
 */
internal val LocalToolBoxInGlassSheet = staticCompositionLocalOf { false }

/**
 * False where another overlay window sits between the page and a sheet (a sheet opened over the
 * group folder): the sheet can only copy the page window, so it keeps the opaque panel there.
 */
val LocalToolBoxFrostedSheets = staticCompositionLocalOf { true }

/** Sheets float this far from the screen edges (iOS 26/27). */
private val SheetInset = 12.dp

/** The page must stop redrawing this long before the sheet retakes its copy of it. */
private const val BackdropSettleMs = 160L

/** Blur of the page copy behind a frosted sheet. */
private val SheetBlur = 28.dp

/** A modal window: taps cannot reach the catalog behind the sheet. */
@Composable
fun ToolBoxActionSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    onBackRequest: () -> Unit = onDismissRequest,
    containerColor: Color? = null,
    frostedBackdrop: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val sheetRadius = ToolBoxThemeTokens.radii.sheet
    // iOS 27: sheets float inset from the screen edges with every corner rounded.
    val shape = toolBoxShape(sheetRadius)
    val materials = ToolBoxThemeTokens.materials
    val dark = colors.surface.luminance() < 0.5f
    // The sheet window cannot sample the page behind, so it blurs a copy taken on open.
    val hostView = LocalView.current
    // Pass frostedBackdrop = false over live content (a playing web page): the copy would freeze it.
    val frosted = containerColor == null && frostedBackdrop && LocalToolBoxFrostedSheets.current &&
        materials.realBlurEnabled
    var backdrop by remember { mutableStateOf<WindowBackdrop?>(null) }
    // Opaque until the copy lands, then eases into the lighter glass fill.
    val frostAmount = remember { Animatable(0f) }
    val configuration = LocalConfiguration.current
    if (frosted) LaunchedEffect(hostView, configuration.screenWidthDp, configuration.screenHeightDp) {
        // A resize (rotation, multi-window) makes the old copy the wrong size: drop it and go
        // opaque at once, then retake it once the page has laid out at the new size.
        backdrop = null
        frostAmount.snapTo(0f)
        withFrameNanos { }
        withFrameNanos { }
        // The page can change under an open sheet (a favourite or group toggled from it): retake
        // the copy once the page window has stopped redrawing. A page that keeps animating (a
        // spinner) keeps its last copy rather than being copied every frame. The listener is in
        // place before the first copy, so a change landing during it is not missed.
        val redraws = Channel<Unit>(Channel.CONFLATED)
        var copying = false
        var drewWhileCopying = false
        var recopied = false
        val listener = ViewTreeObserver.OnDrawListener {
            if (copying) drewWhileCopying = true else redraws.trySend(Unit)
        }
        hostView.viewTreeObserver.addOnDrawListener(listener)
        suspend fun copyPage() {
            drewWhileCopying = false
            copying = true
            val fresh = try { captureWindowBackdrop(hostView) } finally { copying = false }
            if (fresh != null) {
                backdrop = fresh
                if (frostAmount.targetValue < 1f) launch { frostAmount.animateTo(1f) }
            }
            // A draw during the copy may be the page's last change: copy once more. Only once
            // in a row, since a copy can itself trigger a draw.
            recopied = if (drewWhileCopying && !recopied) { redraws.trySend(Unit); true } else false
        }
        try {
            copyPage()
            while (true) {
                redraws.receive()
                while (withTimeoutOrNull(BackdropSettleMs) { redraws.receive() } != null) { }
                copyPage()
            }
        } finally {
            hostView.viewTreeObserver.removeOnDrawListener(listener)
        }
    }
    val frost = if (frosted) frostAmount.value else 0f
    // WindowBottomSheet has its own back handler and one shared dismissal callback.
    // A full-window Dialog gives Back a single route, independent of scrim/drag
    // dismissal, so an inner confirmation can return to its editor reliably.
    Dialog(
        onDismissRequest = onBackRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        DisposableEffect(window) {
            val previousDim = window.attributes.dimAmount
            val previousAnimations = window.attributes.windowAnimations
            window.setDimAmount(0f)
            // No window blur: FLAG_BLUR_BEHIND / setBackgroundBlurRadius blur everything under this
            // full-screen window, but the page behind an iOS 26 sheet stays sharp. The sheet is its
            // own window, so it cannot sample the app's recorded ToolBoxGlassState layer either;
            // its liquid glass refracts a copy of the page instead (snapshotGlass).
            // Entry is driven by Compose springs; the exit is a window animation so it also
            // plays when the caller removes the sheet directly (a selection, a guard, Back).
            window.setWindowAnimations(io.toolbox.core.ui.R.style.ToolBox_SheetWindowAnimation)
            onDispose {
                window.setDimAmount(previousDim)
                window.setWindowAnimations(previousAnimations)
            }
        }
        val density = LocalDensity.current
        val dismissDistance = with(density) { 64.dp.toPx() }
        val dismissVelocity = with(density) { 800.dp.toPx() }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val settle = remember { Animatable(0f) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val dismiss by rememberUpdatedState(onDismissRequest)
        // Springs up from below on first show; the scrim fades with it. With animations
        // disabled the spring completes on the first frame, so nothing waits on it.
        val entrance = remember { Animatable(0f) }
        val scrimAlpha = remember { Animatable(0f) }
        LaunchedEffect(entrance) {
            launch { scrimAlpha.animateTo(1f, ToolBoxMotion.ScrimFade) }
            entrance.animateTo(1f, ToolBoxMotion.SheetEnter)
        }
        val backRequest by rememberUpdatedState(onBackRequest)
        // Predictive back: the sheet follows the gesture down, then either leaves through the
        // back route or springs back. A plain Back press takes the same commit path.
        PredictiveBackHandler { events ->
            try {
                events.collect { event -> dragOffset = event.progress * dismissDistance * 1.5f }
                backRequest()
                settleJob = scope.launch {
                    settle.snapTo(dragOffset)
                    settle.animateTo(0f, ToolBoxMotion.spatial()) { dragOffset = value }
                }
            } catch (cancelled: CancellationException) {
                settleJob = scope.launch {
                    settle.snapTo(dragOffset)
                    settle.animateTo(0f, ToolBoxMotion.spatial()) { dragOffset = value }
                }
                throw cancelled
            }
        }
        val headerDrag = Modifier.pointerInput(dismissDistance, dismissVelocity) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    // Upward swipes must reach the list, including when a long title fills the sheet.
                    if (overSlop > 0f) {
                        settleJob?.cancel()
                        change.consume()
                        dragOffset += overSlop
                    }
                }
                if (start != null) {
                    try {
                        tracker.addPosition(start.uptimeMillis, start.position)
                        val released = verticalDrag(start.id) { change ->
                            dragOffset = (dragOffset + change.positionChange().y).coerceAtLeast(0f)
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                        }
                        if (released && (dragOffset >= dismissDistance || tracker.calculateVelocity().y >= dismissVelocity)) {
                            dismiss()
                        }
                        // A save in progress or an unsaved-draft guard can keep this
                        // sheet open. Restore its position even when dismissal was requested.
                        // Actual dismissal disposes this scope and cancels the animation.
                        settleJob = scope.launch {
                            settle.snapTo(dragOffset)
                            settle.animateTo(0f) { dragOffset = value }
                        }
                    } catch (cancelled: CancellationException) {
                        // Back can replace this header while the same sheet stays open.
                        settleJob?.cancel()
                        dragOffset = 0f
                        throw cancelled
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            // A light scrim (0.18 / 0.42): the page behind stays crisp and readable.
            val scrim = colors.scrim
            Box(Modifier.matchParentSize()
                .graphicsLayer { alpha = scrimAlpha.value.coerceIn(0f, 1f) }
                .background(scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    role = Role.Button, onClick = onDismissRequest,
                ).semantics { contentDescription = "关闭面板" })
            Box(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .imePadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    modifier.widthIn(max = 560.dp).fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                        .padding(horizontal = SheetInset).padding(bottom = SheetInset)
                        .offset { IntOffset(0, dragOffset.roundToInt()) }
                        .graphicsLayer {
                            // Never overshoot upward: that would open a gap under the sheet.
                            translationY = ((1f - entrance.value) * size.height).coerceAtLeast(0f)
                        }
                        .shadow(
                            elevation = 24.dp,
                            shape = shape,
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = if (dark) 0.6f else 0.22f),
                            spotColor = Color.Black.copy(alpha = if (dark) 0.6f else 0.22f),
                        )
                        .clip(shape)
                        .then(
                            backdrop?.takeIf { frosted }?.let {
                                // Liquid glass over the page copy; the fill eases from opaque as it lands.
                                Modifier.snapshotGlass(
                                    it, sheetRadius, SheetBlur,
                                    fill = { lerp(materials.overlayFallback, materials.overlayFill, frostAmount.value) },
                                ) { entrance.value; dragOffset }
                            } ?: Modifier.background(
                                containerColor ?: lerp(materials.overlayFallback, materials.overlayFill, frost),
                            ),
                        )
                        .border(0.5.dp, materials.overlayBorder, shape)
                        .pointerInput(Unit) { detectTapGestures { /* Consume taps on the sheet, not its scrim. */ } }
                        .padding(horizontal = 20.dp).padding(bottom = 12.dp)
                        .semantics { paneTitle = title; dismiss { onDismissRequest(); true } },
                ) {
                    CompositionLocalProvider(
                        LocalSheetHeaderDrag provides headerDrag,
                        // Opaque custom containers (browser sheets) keep their opaque cards.
                        LocalToolBoxInGlassSheet provides (containerColor == null),
                    ) {
                        Box(Modifier.fillMaxWidth()) { content() }
                    }
                }
            }
        }
    }
}

/** The handle and title share a drag target; the title's controls keep their own taps. */
@Composable
fun ToolBoxActionSheetHeader(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().then(LocalSheetHeaderDrag.current)) {
        Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(2.5.dp))
                .background(ToolBoxThemeTokens.colors.textSecondary.copy(alpha = 0.32f)))
        }
        content()
    }
}
