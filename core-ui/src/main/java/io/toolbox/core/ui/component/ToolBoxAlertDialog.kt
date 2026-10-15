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
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.core.ui.theme.ProvideToolBoxGlassText
import io.toolbox.core.ui.theme.onGlass

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
    // Read outside the Dialog: this is the page's view, which the alert glass copies.
    val glass = rememberToolBoxWindowGlass(LocalView.current)
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
        val dark = colors.surface.luminance() < 0.5f
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            if (window == null) return@DisposableEffect onDispose { }
            val previousDim = window.attributes.dimAmount
            window.setDimAmount(0f)
            // No window blur: it would blur the whole page, which stays sharp behind an iOS 26
            // alert. The alert window cannot sample the app's recorded glass layer, so its liquid
            // glass refracts a copy of the page instead.
            onDispose { window.setDimAmount(previousDim) }
        }
        // iOS alerts settle in from slightly larger than final size while the dim fades in.
        val appear = remember { Animatable(0f) }
        LaunchedEffect(appear) { appear.animateTo(1f, ToolBoxMotion.DialogEnter) }
        val radius = ToolBoxThemeTokens.radii.dialog
        val shape = toolBoxShape(radius)
        val scrim = colors.scrim
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
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .widthIn(min = 300.dp, max = 340.dp)
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
                        ambientColor = Color.Black.copy(alpha = if (dark) 0.6f else 0.22f),
                        spotColor = Color.Black.copy(alpha = if (dark) 0.6f else 0.22f),
                    )
                    .clip(shape)
                    .toolBoxWindowGlassEffect(glass, radius, layerScale = { 1.12f - 0.12f * appear.value }) { appear.value }
                    .border(0.5.dp, materials.overlayBorder, shape)
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
                            color = (if (materials.realBlurEnabled) colors.onGlass() else colors).textSecondary,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                content?.let {
                    Spacer(Modifier.height(14.dp))
                    ProvideToolBoxGlassText { Column(Modifier.fillMaxWidth(), content = it) }
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
    val neutralFill = ToolBoxNeutralFill.copy(alpha = if (dark) 0.24f else 0.12f)
    // Solid iOS 26 confirm: accent (#0064D2 / #0A84FF) or destructive (#D70015 / #FF6961); white
    // label unless it would fall under 3:1 (dark #FF6961), then the iOS dark label.
    val confirmFill = if (destructive) colors.danger else colors.primary
    val confirmContent = if (actionContrast(confirmFill, Color.White) >= 3.0f) Color.White else Color(0xFF1C1C1E)
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
            .heightIn(min = 48.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .toolBoxGlassPress(interactionSource, enabled = enabled, shape = CircleShape)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = container.alpha * 0.45f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
