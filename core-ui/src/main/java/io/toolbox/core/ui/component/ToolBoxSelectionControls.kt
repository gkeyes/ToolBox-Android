package io.toolbox.core.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import kotlin.math.roundToInt

/**
 * Segmented control (iOS 26): a capsule track with a glass thumb that slides between segments on
 * the spatial spring. While a segment is held the thumb lifts: it grows about 10% and turns to
 * clear glass that refracts the track and labels beneath it. Each change ticks. Segments are radio
 * buttons inside a selectable group for accessibility.
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
    val sources = remember(options.size) { List(options.size) { MutableInteractionSource() } }
    val pressed = sources.map { it.collectIsPressedAsState() }
    val lift = animateFloatAsState(
        targetValue = if (enabled && pressed.any { it.value }) 1f else 0f,
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
    BoxWithConstraints(
        modifier.fillMaxWidth().heightIn(min = 44.dp).clip(CircleShape).background(trackFill).padding(3.dp),
    ) {
        val segmentWidth = maxWidth / options.size
        val indicatorOffset = animateDpAsState(
            targetValue = segmentWidth * selectedIndex.coerceIn(0, options.lastIndex),
            animationSpec = ToolBoxMotion.spatial(),
            label = "segmented indicator",
        )
        Row(
            Modifier.fillMaxSize().selectableGroup()
                .then(if (live) Modifier.layerBackdrop(trackBackdrop) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val textColor = animateColorAsState(
                    if (selected) colors.textPrimary else colors.textSecondary,
                    ToolBoxMotion.effects(),
                    label = "segment label",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp)
                        .then(segmentTestTags.getOrNull(index)?.let(Modifier::testTag) ?: Modifier)
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            interactionSource = sources[index],
                            indication = null,
                            onClick = {
                                if (!selected) {
                                    haptics.tick()
                                    onSelect(index)
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    ToolBoxText(
                        label,
                        style = ToolBoxThemeTokens.textStyles.metadata.copy(
                            color = textColor.value,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (selectedIndex in options.indices) {
            val thumb = Modifier
                .offset { IntOffset(indicatorOffset.value.roundToPx(), 0) }
                .width(segmentWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    val s = 1f + 0.1f * lift.value
                    scaleX = s
                    scaleY = s
                }
            if (live) {
                // Drawn over the labels: solid at rest (the selected label is drawn on top again
                // below), clear lens glass while lifted.
                Box(
                    thumb.drawBackdrop(
                        backdrop = trackBackdrop,
                        shape = { CircleShape },
                        effects = {
                            vibrancy()
                            blur(2.dp.toPx())
                            lens(10.dp.toPx(), 20.dp.toPx())
                        },
                        onDrawSurface = { drawRect(pillFill.copy(alpha = 1f - 0.85f * lift.value)) },
                    ),
                )
                // The label of the segment the thumb is over stays readable on top of the solid
                // thumb, so a new selection's label doesn't ride across with the sliding thumb.
                val thumbIndex = (indicatorOffset.value / segmentWidth).roundToInt()
                    .coerceIn(0, options.lastIndex)
                Box(
                    thumb.graphicsLayer { alpha = 1f - lift.value }.clearAndSetSemantics { },
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
