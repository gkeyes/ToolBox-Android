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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LocalSheetHeaderDrag = staticCompositionLocalOf<Modifier> { Modifier }

/** A modal window: taps cannot reach the catalog behind the sheet. */
@Composable
fun ToolBoxActionSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    onBackRequest: () -> Unit = onDismissRequest,
    content: @Composable () -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val glass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass
    val shape = RoundedCornerShape(topStart = if (glass) 28.dp else 24.dp, topEnd = if (glass) 28.dp else 24.dp)
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
            window.setDimAmount(0f)
            onDispose { window.setDimAmount(previousDim) }
        }
        val density = LocalDensity.current
        val dismissDistance = with(density) { 64.dp.toPx() }
        val dismissVelocity = with(density) { 800.dp.toPx() }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val settle = remember { Animatable(0f) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val dismiss by rememberUpdatedState(onDismissRequest)
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
                        } else {
                            settleJob = scope.launch {
                                settle.snapTo(dragOffset)
                                settle.animateTo(0f) { dragOffset = value }
                            }
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
            Box(Modifier.matchParentSize().background(MiuixTheme.colorScheme.windowDimming)
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
                        .offset { IntOffset(0, dragOffset.roundToInt()) }
                        .clip(shape)
                        .background(if (glass) ToolBoxThemeTokens.materials.glassFallback else colors.surface)
                        .then(if (glass) Modifier.border(0.75.dp, ToolBoxThemeTokens.materials.glassBorder, shape) else Modifier)
                        .pointerInput(Unit) { detectTapGestures { /* Consume taps on the sheet, not its scrim. */ } }
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                        .padding(horizontal = 20.dp).padding(bottom = 12.dp)
                        .semantics { paneTitle = title; dismiss { onDismissRequest(); true } },
                ) {
                    CompositionLocalProvider(
                        LocalSheetHeaderDrag provides headerDrag,
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
            Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(ToolBoxThemeTokens.colors.textSecondary.copy(alpha = 0.32f)))
        }
        content()
    }
}
