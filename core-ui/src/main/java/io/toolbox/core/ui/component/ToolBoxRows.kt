package io.toolbox.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import io.toolbox.core.ui.liquid.LiquidToggle
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ToolBoxSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    contentDescription: String = placeholder,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = ToolBoxThemeTokens.colors
    val spacing = ToolBoxThemeTokens.spacing
    val shape = toolBoxShape(ToolBoxThemeTokens.radii.control)
    var focused by remember { mutableStateOf(false) }
    val focusColor = animateColorAsState(
        targetValue = if (focused) colors.primary.copy(alpha = 0.55f) else colors.primary.copy(alpha = 0f),
        animationSpec = ToolBoxMotion.effects(),
        label = "search focus ring",
    )
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = toolBoxSearchFieldMinHeight())
            .clip(shape)
            .background(colors.surfaceMuted)
            .border(1.5.dp, focusColor.value, shape)
            .padding(start = spacing.oneHalf, end = spacing.half),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBoxIcon(
            ToolBoxIconKey.Search, null, Modifier.size(18.dp),
            tint = if (focused) colors.primary else colors.textSecondary,
        )
        Spacer(Modifier.width(spacing.one))
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            modifier = Modifier.weight(1f).padding(vertical = spacing.row).focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .semantics { this.contentDescription = contentDescription },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            textStyle = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textPrimary),
            cursorBrush = SolidColor(ToolBoxThemeTokens.colors.primary),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) {
                        ToolBoxText(
                            placeholder,
                            style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
            },
        )
        // Reserve the same space in both states so the first keystroke does not
        // move the text or cursor. The empty slot has no hidden click semantics.
        Box(Modifier.size(ToolBoxThemeTokens.sizes.touchTarget), contentAlignment = Alignment.Center) {
            if (value.isNotEmpty()) {
                ToolBoxIconButton(ToolBoxIconKey.Close, "清空${contentDescription}", onClick = {
                    onValueChange("")
                    focusRequester.requestFocus()
                })
            }
        }
    }
}

internal fun toolBoxSearchFieldMinHeight() = ToolBoxThemeTokens.sizes.touchTarget


// iOS 27 inset-grouped row metrics: 16dp leading inset, 14dp trailing, 52dp single-line rows and
// 60dp rows with a subtitle. Separators (ToolBoxGroupDivider) start after the 30dp icon + 12dp gap.
internal val ToolBoxRowMinHeight = 52.dp
internal val ToolBoxRowWithSummaryMinHeight = 60.dp
internal val ToolBoxRowStartPadding = 16.dp
internal val ToolBoxRowEndPadding = 14.dp
internal val ToolBoxRowVerticalPadding = 10.dp
internal val ToolBoxRowIconSize = 30.dp
internal val ToolBoxRowIconGap = 12.dp

private fun rowMinHeight(summary: String?): Dp =
    if (summary.isNullOrBlank()) ToolBoxRowMinHeight else ToolBoxRowWithSummaryMinHeight

private fun Modifier.toolBoxRowPadding(): Modifier = padding(
    start = ToolBoxRowStartPadding,
    end = ToolBoxRowEndPadding,
    top = ToolBoxRowVerticalPadding,
    bottom = ToolBoxRowVerticalPadding,
)

@Composable
private fun ToolBoxRowLabels(
    title: String,
    summary: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ToolBoxThemeTokens.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        ToolBoxText(title, style = ToolBoxThemeTokens.textStyles.title.copy(
            color = if (enabled) colors.textPrimary else ToolBoxThemeTokens.disabledContent))
        summary?.takeIf(String::isNotBlank)?.let {
            ToolBoxText(it, style = ToolBoxThemeTokens.textStyles.sectionTitle.copy(
                color = if (enabled) colors.textSecondary else ToolBoxThemeTokens.disabledContent))
        }
    }
}

@Composable
fun ToolBoxSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ToolBoxIconKey? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val spacing = ToolBoxThemeTokens.spacing
    val colors = ToolBoxThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = rowMinHeight(summary))
            .toolBoxPressFeedback(
                interactionSource,
                enabled = enabled && onClick != null,
                scale = ToolBoxMotion.RowPressScale,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .toolBoxRowPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            ToolBoxPreferenceIcon(it, enabled)
            Spacer(Modifier.width(ToolBoxRowIconGap))
        }
        ToolBoxRowLabels(title, summary, enabled, Modifier.weight(1f))
        trailing?.let {
            Spacer(Modifier.width(spacing.one))
            it()
        }
        // A disclosure indicator promises navigation: informational rows must not show one.
        if (onClick != null) {
            Spacer(Modifier.width(spacing.one))
            ToolBoxIcon(
                ToolBoxIconKey.ChevronRight, null, Modifier.size(20.dp),
                tint = if (enabled) colors.textTertiary else ToolBoxThemeTokens.disabledContent,
            )
        }
    }
}

@Composable
fun ToolBoxSwitchSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ToolBoxIconKey? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberToolBoxHaptics()
    val latestOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val change: (Boolean) -> Unit = remember(haptics) {
        { value: Boolean ->
            haptics.toggle(value)
            latestOnCheckedChange(value)
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    // The whole row is the switch for accessibility and touch; the trailing control mirrors it.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = rowMinHeight(summary))
            .toolBoxPressFeedback(interactionSource, enabled = enabled, scale = ToolBoxMotion.RowPressScale)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = change,
            )
            .toolBoxRowPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            ToolBoxPreferenceIcon(it, enabled)
            Spacer(Modifier.width(ToolBoxRowIconGap))
        }
        ToolBoxRowLabels(title, summary, enabled, Modifier.weight(1f))
        Spacer(Modifier.width(ToolBoxThemeTokens.spacing.oneHalf))
        ToolBoxSwitchControl(checked = checked, enabled = enabled, onCheckedChange = change)
    }
}

/**
 * Trailing switch. With live glass it is the vendored Kyant [LiquidToggle] (a glass knob that
 * refracts the track and stretches while dragged); otherwise the flat iOS 26 solid switch (64x28 track,
 * capsule knob, green when on). Either way the owning row carries the switch semantics and handles taps outside it.
 */
@Composable
private fun ToolBoxSwitchControl(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    if (glassRendersLive()) {
        ToolBoxLiquidSwitch(checked, enabled, onCheckedChange)
    } else {
        ToolBoxSolidSwitch(checked, enabled)
    }
}

private class SwitchGestureState {
    /** Set when the glass knob itself reported a tap or drag during the current gesture. */
    var knobHandled = false
}

/** After a knob gesture, wait this long for the owner to adopt the value before snapping back. */
private const val SwitchResyncDelayMillis = 450L

@Composable
private fun ToolBoxLiquidSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val checkedState = rememberUpdatedState(checked)
    val enabledState = rememberUpdatedState(enabled)
    val latestChange = rememberUpdatedState(onCheckedChange)
    // LiquidToggle keeps its own knob position. When an owner declines a change (a confirmation
    // dialog, a guard, a disabled row), a fresh `selected` lambda restarts its sync effect so the
    // knob springs back to the real value.
    var resyncKey by remember { mutableIntStateOf(0) }
    val selected: () -> Boolean = remember(resyncKey) { { checkedState.value } }
    val gesture = remember { SwitchGestureState() }
    val scope = rememberCoroutineScope()
    val surface = ToolBoxThemeTokens.colors.surface
    // The knob refracts the row surface it sits on; the row is never a recorded backdrop layer.
    val backdrop = rememberCanvasBackdrop { drawRect(surface) }
    Box(
        Modifier
            .graphicsLayer { alpha = if (enabledState.value) 1f else 0.45f }
            .semantics { role = Role.Switch }
            .pointerInput(Unit) {
                // Runs after the knob in the main pass: taps on the bare track toggle here, and every
                // change is consumed so the row does not toggle a second time.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    gesture.knobHandled = false
                    down.consume()
                    var moved = false
                    var released = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            released = true
                            pointer.consume()
                            break
                        }
                        if ((pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        pointer.consume()
                    }
                    if (released && !moved && !gesture.knobHandled && enabledState.value) {
                        latestChange.value(!checkedState.value)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        LiquidToggle(
            selected = selected,
            onSelect = { value ->
                gesture.knobHandled = true
                if (enabledState.value && value != checkedState.value) latestChange.value(value)
                scope.launch {
                    delay(SwitchResyncDelayMillis)
                    if (checkedState.value != value) resyncKey++
                }
            },
            backdrop = backdrop,
        )
        if (!enabled) {
            // Drawn above the knob, so a disabled switch cannot be dragged or tapped.
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
            )
        }
    }
}

@Composable
private fun ToolBoxSolidSwitch(checked: Boolean, enabled: Boolean) {
    val dark = ToolBoxThemeTokens.colors.surface.luminance() < 0.5f
    val onColor = if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    val offColor = if (dark) Color(0x5C787880) else Color(0x29787880)
    val track = animateColorAsState(
        targetValue = if (checked) onColor else offColor,
        animationSpec = ToolBoxMotion.effects(),
        label = "switch track",
    )
    val knobOffset = animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp, // 64 - 40 - 2
        animationSpec = ToolBoxMotion.spatial(),
        label = "switch knob",
    )
    Box(
        Modifier
            .semantics { role = Role.Switch }
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .size(64.dp, 28.dp)
            .drawBehind {
                drawRoundRect(track.value, cornerRadius = CornerRadius(size.height / 2f))
            },
    ) {
        Box(
            Modifier
                .offset { IntOffset(knobOffset.value.roundToPx(), 2.dp.roundToPx()) }
                .size(40.dp, 24.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
fun ToolBoxValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ToolBoxIconKey? = null,
    valueColor: androidx.compose.ui.graphics.Color = ToolBoxThemeTokens.colors.textSecondary,
) {
    val spacing = ToolBoxThemeTokens.spacing
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = rowMinHeight(summary))
            .toolBoxRowPadding(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.3f
        val labelWidth = (maxWidth - if (icon != null) ToolBoxRowIconSize + ToolBoxRowIconGap else 0.dp) * 0.4f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                ToolBoxPreferenceIcon(it)
                Spacer(Modifier.width(ToolBoxRowIconGap))
            }
            if (stacked) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.half)) {
                    ToolBoxValueLabel(title, summary)
                    ToolBoxText(value, style = ToolBoxThemeTokens.textStyles.body.copy(color = valueColor))
                }
            } else {
                ToolBoxValueLabel(title, summary, Modifier.widthIn(max = labelWidth))
                Spacer(Modifier.width(spacing.oneHalf))
                ToolBoxText(
                    text = value,
                    modifier = Modifier.weight(1f),
                    style = ToolBoxThemeTokens.textStyles.body.copy(
                        color = valueColor,
                        textAlign = TextAlign.End,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ToolBoxValueLabel(title: String, summary: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        ToolBoxText(title, style = ToolBoxThemeTokens.textStyles.title.copy(
            color = ToolBoxThemeTokens.colors.textPrimary,
        ))
        summary?.takeIf(String::isNotBlank)?.let {
            ToolBoxText(it, style = ToolBoxThemeTokens.textStyles.sectionTitle.copy(color = ToolBoxThemeTokens.colors.textSecondary))
        }
    }
}

/** iOS Settings-style glyph tile: a 30dp continuous square with a two-stop gradient and a white glyph. */
@Composable
private fun ToolBoxPreferenceIcon(icon: ToolBoxIconKey, enabled: Boolean = true) {
    val (top, bottom) = preferenceIconGradient(icon, ToolBoxThemeTokens.colors.primary)
    Box(
        modifier = Modifier
            .size(ToolBoxRowIconSize)
            .clip(toolBoxShape(ToolBoxThemeTokens.radii.badge))
            .then(
                if (enabled) {
                    Modifier.background(Brush.verticalGradient(listOf(top, bottom)))
                } else {
                    Modifier.background(ToolBoxThemeTokens.colors.surfaceMuted)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        ToolBoxIcon(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = if (enabled) Color.White else ToolBoxThemeTokens.disabledContent,
        )
    }
}

/** Fixed iOS system hues per glyph so a settings list reads like the platform's; unknown glyphs use the accent. */
private fun preferenceIconGradient(icon: ToolBoxIconKey, accent: Color): Pair<Color, Color> = when (icon) {
    ToolBoxIconKey.Palette -> Color(0xFFFF7AC6) to Color(0xFFA845E8)
    ToolBoxIconKey.Lock, ToolBoxIconKey.Shield -> Color(0xFF4CD964) to Color(0xFF1E9E45)
    ToolBoxIconKey.Clock, ToolBoxIconKey.Stop -> Color(0xFFFFB340) to Color(0xFFFF7A00)
    ToolBoxIconKey.Notifications -> Color(0xFFFF6B6B) to Color(0xFFE5383B)
    ToolBoxIconKey.Folder, ToolBoxIconKey.Clipboard, ToolBoxIconKey.Note -> Color(0xFF3FD8E0) to Color(0xFF0E93B8)
    ToolBoxIconKey.Code -> Color(0xFF7C82FF) to Color(0xFF4B4FD6)
    ToolBoxIconKey.Globe, ToolBoxIconKey.Location, ToolBoxIconKey.Share -> Color(0xFF5AC8FA) to Color(0xFF007AFF)
    ToolBoxIconKey.Settings, ToolBoxIconKey.Device, ToolBoxIconKey.Haptics, ToolBoxIconKey.Camera ->
        Color(0xFFA1A1A8) to Color(0xFF6C6C74)
    ToolBoxIconKey.Refresh -> Color(0xFF5DE0C8) to Color(0xFF14A38B)
    ToolBoxIconKey.Star, ToolBoxIconKey.StarFilled -> Color(0xFFFFD60A) to Color(0xFFFF9F0A)
    else -> accent.copy(alpha = 0.72f).compositeOver(Color.White) to accent
}

/** iOS selection row: a plain label with a trailing accent checkmark when selected. */
@Composable
fun ToolBoxRadioSettingRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberToolBoxHaptics()
    val colors = ToolBoxThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ToolBoxRowMinHeight)
            .toolBoxPressFeedback(interactionSource, enabled = enabled, scale = ToolBoxMotion.RowPressScale)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = {
                    if (!selected) haptics.tick()
                    onClick()
                },
            )
            .toolBoxRowPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBoxText(
            title,
            modifier = Modifier.weight(1f),
            style = ToolBoxThemeTokens.textStyles.title.copy(
                color = if (enabled) colors.textPrimary else ToolBoxThemeTokens.disabledContent,
            ),
        )
        Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                ToolBoxIcon(
                    ToolBoxIconKey.Check, null, Modifier.size(22.dp),
                    tint = if (enabled) colors.primary else ToolBoxThemeTokens.disabledContent,
                )
            }
        }
    }
}
