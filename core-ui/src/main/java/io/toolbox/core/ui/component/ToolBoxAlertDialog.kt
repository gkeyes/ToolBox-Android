package io.toolbox.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

private const val AlertBlurBehindRadiusPx = 48

/**
 * Centered iOS 27 alert: a floating 34dp continuous panel with a title, an optional message,
 * optional extra content, and capsule buttons (side by side when both labels are short, stacked
 * otherwise). The confirm button turns red for [destructive] actions. Outside taps do not dismiss
 * an alert; Back and the dismiss button call [onDismissRequest] / [onDismiss].
 *
 * Use it for short confirmations; forms and longer choices stay in [ToolBoxModalDialog].
 */
@Composable
fun ToolBoxAlertDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissLabel: String? = "取消",
    onDismiss: () -> Unit = onDismissRequest,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    confirmTestTag: String? = null,
    dismissTestTag: String? = null,
    icon: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val colors = ToolBoxThemeTokens.colors
        val materials = ToolBoxThemeTokens.materials
        val glass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass
        val liveBlur = glass && materials.realBlurEnabled
        val dark = colors.surface.luminance() < 0.5f
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window, liveBlur) {
            if (window == null) return@DisposableEffect onDispose { }
            val previousDim = window.attributes.dimAmount
            val previousFlags = window.attributes.flags
            val previousBlur = window.attributes.blurBehindRadius
            window.setDimAmount(0f)
            if (liveBlur) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = AlertBlurBehindRadiusPx }
            }
            onDispose {
                window.setDimAmount(previousDim)
                if (liveBlur) {
                    if ((previousFlags and android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND) == 0) {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    }
                    window.attributes = window.attributes.apply { blurBehindRadius = previousBlur }
                }
            }
        }
        // iOS alerts settle in from slightly larger than final size while the dim fades in.
        val appear = remember { Animatable(0f) }
        LaunchedEffect(appear) { appear.animateTo(1f, ToolBoxMotion.DialogEnter) }
        val shape = toolBoxShape(ToolBoxThemeTokens.radii.dialog)
        val fill = when {
            liveBlur -> if (dark) Color(0xCC2C2C2E) else Color.White.copy(alpha = 0.82f)
            else -> colors.raised
        }
        val scrim = if (liveBlur) colors.scrim.copy(alpha = colors.scrim.alpha * 0.7f) else colors.scrim
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
                    .background(scrim),
            )
            Column(
                modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 40.dp, vertical = 24.dp)
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val progress = appear.value
                        val scale = 1.12f - 0.12f * progress
                        scaleX = scale
                        scaleY = scale
                        alpha = progress.coerceIn(0f, 1f)
                    }
                    .shadow(
                        elevation = 24.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.28f),
                        spotColor = Color.Black.copy(alpha = 0.28f),
                    )
                    .clip(shape)
                    .background(fill)
                    .border(0.5.dp, materials.glassBorder, shape)
                    .semantics { paneTitle = title }
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 26.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon?.let {
                    it()
                    Spacer(Modifier.height(18.dp))
                }
                ToolBoxText(
                    title,
                    style = ToolBoxThemeTokens.textStyles.headline.copy(
                        fontSize = 19.sp,
                        lineHeight = 24.sp,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                    ),
                )
                message?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(6.dp))
                    ToolBoxText(
                        it,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = ToolBoxThemeTokens.textStyles.metadata.copy(
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                content?.let {
                    Spacer(Modifier.height(14.dp))
                    Column(Modifier.fillMaxWidth(), content = it)
                }
                Spacer(Modifier.height(18.dp))
                ToolBoxAlertButtons(
                    confirmLabel = confirmLabel,
                    onConfirm = onConfirm,
                    dismissLabel = dismissLabel,
                    onDismiss = onDismiss,
                    destructive = destructive,
                    confirmEnabled = confirmEnabled,
                    confirmTestTag = confirmTestTag,
                    dismissTestTag = dismissTestTag,
                )
            }
        }
    }
}

@Composable
private fun ToolBoxAlertButtons(
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    onDismiss: () -> Unit,
    destructive: Boolean,
    confirmEnabled: Boolean,
    confirmTestTag: String?,
    dismissTestTag: String?,
) {
    val colors = ToolBoxThemeTokens.colors
    val dark = colors.surface.luminance() < 0.5f
    val neutralFill = Color(0xFF767680).copy(alpha = if (dark) 0.24f else 0.12f)
    val confirmFill = if (destructive) colors.danger else colors.primary
    val confirmContent = if (destructive) colors.onDanger else colors.onPrimary
    val sideBySide = dismissLabel != null &&
        LocalDensity.current.fontScale < 1.3f &&
        confirmLabel.length <= 6 && dismissLabel.length <= 6
    @Composable
    fun Confirm(buttonModifier: Modifier) = ToolBoxAlertCapsuleButton(
        label = confirmLabel,
        onClick = onConfirm,
        container = confirmFill,
        content = confirmContent,
        enabled = confirmEnabled,
        testTag = confirmTestTag,
        modifier = buttonModifier,
    )

    @Composable
    fun Dismiss(label: String, buttonModifier: Modifier) = ToolBoxAlertCapsuleButton(
        label = label,
        onClick = onDismiss,
        container = neutralFill,
        content = colors.textPrimary,
        enabled = true,
        testTag = dismissTestTag,
        modifier = buttonModifier,
    )

    if (sideBySide && dismissLabel != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dismiss(dismissLabel, Modifier.weight(1f))
            Confirm(Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Confirm(Modifier.fillMaxWidth())
            if (dismissLabel != null) Dismiss(dismissLabel, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ToolBoxAlertCapsuleButton(
    label: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    enabled: Boolean,
    testTag: String?,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = 50.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .toolBoxPressFeedback(interactionSource, enabled = enabled, shape = CircleShape, scale = 0.96f)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = container.alpha * 0.45f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        ToolBoxText(
            label,
            style = ToolBoxThemeTokens.textStyles.headline.copy(
                color = if (enabled) content else content.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
