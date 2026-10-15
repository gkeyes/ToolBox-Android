package io.toolbox.core.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import kotlinx.coroutines.launch
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import kotlin.math.roundToInt

/**
 * Segmented control (iOS 26): a capsule track with a glass thumb that slides between segments on
 * the spatial spring. Press the thumb and it lifts under the finger: it grows about 10%, overflows
 * the track and turns to clear glass that refracts the labels beneath it; drag it along the track
 * and release on a segment to pick it. Tapping another segment picks it too. Each change ticks.
 * Segments stay radio buttons inside a selectable group for accessibility.
 */
@Composable
fun ToolBoxSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    segmentTestTags: List<String?> = emptyList(),
) {
    if (options.isEmpty()) return
    val colors = ToolBoxThemeTokens.colors
    val haptics = rememberToolBoxHaptics()
    val dark = colors.surface.luminance() < 0.5f
    val trackFill = ToolBoxNeutralFill.copy(alpha = if (dark) 0.24f else 0.12f)
    val pillFill = if (dark) Color(0xFF636366) else Color.White
    val live = glassRendersLive()
    val selected = selectedIndex.coerceIn(0, options.lastIndex)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var dragging by remember { mutableStateOf(false) }
    val lift = animateFloatAsState(
        targetValue = if (enabled && dragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f),
        label = "segmented lift",
    )
    // The thumb refracts the track (and its labels) it slides over; the track is drawn on the
    // surface, so record both into the thumb's backdrop.
    val trackOnSurface = trackFill.compositeOver(colors.surface)
    val trackBackdrop = rememberLayerBackdrop {
        drawRect(trackOnSurface)
        drawContent()
    }
    // The track's capsule is a background, not a clip, so the lifted thumb can grow past it.
    BoxWithConstraints(
        modifier.fillMaxWidth().heightIn(min = 44.dp).background(trackFill, CircleShape).padding(3.dp),
    ) {
        val segmentWidth = maxWidth / options.size
        val segmentPx = with(LocalDensity.current) { segmentWidth.toPx() }
        val lastIndex = options.lastIndex
        val thumbX = remember { Animatable(segmentPx * selected) }
        val scope = rememberCoroutineScope()
        // Settle on the selection whenever it changes or a drag ends.
        LaunchedEffect(selected, segmentPx, dragging) {
            if (!dragging) thumbX.animateTo(segmentPx * selected, ToolBoxMotion.spatial())
        }
        val thumbIndex by remember(segmentPx, lastIndex) {
            derivedStateOf { (thumbX.value / segmentPx).roundToInt().coerceIn(0, lastIndex) }
        }
        val gestures = if (!enabled) Modifier else Modifier.pointerInput(segmentPx, lastIndex) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val onThumb = (down.position.x / segmentPx).toInt().coerceIn(0, lastIndex) == currentSelected
                var under = currentSelected
                var release: Offset? = null
                if (onThumb) dragging = true
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            release = change.position
                            break
                        }
                        if (onThumb) {
                            change.consume()
                            val x = (change.position.x - segmentPx / 2f).coerceIn(0f, segmentPx * lastIndex)
                            scope.launch { thumbX.snapTo(x) }
                            val now = (x / segmentPx).roundToInt().coerceIn(0, lastIndex)
                            if (now != under) {
                                under = now
                                haptics.tick()
                            }
                        }
                    }
                } finally {
                    dragging = false
                }
                val up = release ?: return@awaitEachGesture
                val target = when {
                    onThumb -> under
                    up.x in 0f..size.width.toFloat() && up.y in 0f..size.height.toFloat() ->
                        (up.x / segmentPx).toInt().coerceIn(0, lastIndex)
                    else -> null
                }
                if (target != null && target != currentSelected) {
                    if (!onThumb) haptics.tick()
                    currentOnSelect(target)
                }
            }
        }
        Row(
            Modifier.fillMaxSize().selectableGroup().then(gestures)
                .then(if (live) Modifier.layerBackdrop(trackBackdrop) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, label ->
                val isSelected = index == selected
                val textColor = animateColorAsState(
                    if (isSelected) colors.textPrimary else colors.textSecondary,
                    ToolBoxMotion.effects(),
                    label = "segment label",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp)
                        .then(segmentTestTags.getOrNull(index)?.let(Modifier::testTag) ?: Modifier)
                        .semantics(mergeDescendants = true) {
                            role = Role.RadioButton
                            this.selected = isSelected
                            if (!enabled) disabled()
                            onClick {
                                if (enabled && !isSelected) {
                                    haptics.tick()
                                    currentOnSelect(index)
                                }
                                enabled
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ToolBoxText(
                        label,
                        style = ToolBoxThemeTokens.textStyles.metadata.copy(
                            color = textColor.value,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val thumb = Modifier
            .offset { IntOffset(thumbX.value.roundToInt(), 0) }
            .width(segmentWidth)
            .fillMaxHeight()
            .graphicsLayer {
                val s = 1f + 0.1f * lift.value
                scaleX = s
                scaleY = s
            }
        if (live) {
            // Drawn over the labels: solid at rest, clear lens glass while lifted. No blur, so the
            // labels seen through it stay sharp.
            Box(
                thumb.drawBackdrop(
                    backdrop = trackBackdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        lens(10.dp.toPx(), 20.dp.toPx())
                    },
                    onDrawSurface = { drawRect(pillFill.copy(alpha = 1f - 0.85f * lift.value)) },
                ),
            )
            // The label of the segment under the thumb, drawn on top so it stays readable: fully at
            // rest, still 60% while the thumb is lifted.
            Box(
                thumb.graphicsLayer { alpha = 1f - 0.4f * lift.value }.clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                ToolBoxText(
                    options[thumbIndex],
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Box(
                thumb
                    .zIndex(-1f)
                    .shadow(
                        elevation = 3.dp,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.12f),
                        spotColor = Color.Black.copy(alpha = 0.12f),
                    )
                    .clip(CircleShape)
                    .background(pillFill),
            )
        }
    }
}

/**
 * Number that rolls digit by digit: increases roll up, decreases roll down. Accessibility reads
 * the whole value once instead of individual digits.
 */
@Composable
fun ToolBoxRollingNumber(
    value: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val text = value.toString()
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { position, char ->
            // Key digits from the right so a new leading digit does not shift the others' identity.
            androidx.compose.runtime.key(text.length - position) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        val up = (targetState.digitToIntOrNull() ?: 0) > (initialState.digitToIntOrNull() ?: 0)
                        (slideInVertically(ToolBoxMotion.spatial()) { if (up) it else -it } + fadeIn(ToolBoxMotion.effects()))
                            .togetherWith(slideOutVertically(ToolBoxMotion.spatial()) { if (up) -it else it } + fadeOut(ToolBoxMotion.effects()))
                    },
                    label = "rolling digit",
                ) { digit ->
                    ToolBoxText(digit.toString(), style = style.copy(fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}
