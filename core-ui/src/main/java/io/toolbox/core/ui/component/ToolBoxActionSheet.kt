package io.toolbox.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
        val scope = rememberCoroutineScope()
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
                    Box(
                        Modifier.fillMaxWidth().height(48.dp)
                            .draggable(
                                state = rememberDraggableState { dragOffset = (dragOffset + it).coerceAtLeast(0f) },
                                orientation = Orientation.Vertical,
                                onDragStarted = { settle.stop() },
                                onDragStopped = { velocity ->
                                    if (dragOffset >= dismissDistance || velocity >= dismissVelocity) onDismissRequest()
                                    else scope.launch {
                                        settle.snapTo(dragOffset)
                                        settle.animateTo(0f) { dragOffset = value }
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(colors.textSecondary.copy(alpha = 0.32f)))
                    }
                    Box(Modifier.fillMaxWidth()) { content() }
                }
            }
        }
    }
}
