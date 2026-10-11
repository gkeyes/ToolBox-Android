package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

enum class ToolBoxStatusTone { Neutral, Loading, Error }

/**
 * Empty / loading / error state: a soft icon disc, one line of title, an optional short
 * explanation and an optional action. Never leaves a blank screen.
 */
@Composable
fun ToolBoxStatusState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ToolBoxIconKey? = null,
    message: String? = null,
    tone: ToolBoxStatusTone = ToolBoxStatusTone.Neutral,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = ToolBoxThemeTokens.colors
    val spacing = ToolBoxThemeTokens.spacing
    val (disc, tint) = when (tone) {
        ToolBoxStatusTone.Error -> colors.softDanger to colors.onSoftDanger
        else -> colors.softPrimary to colors.primary
    }
    Box(
        modifier.fillMaxWidth().heightIn(min = 160.dp).padding(vertical = spacing.three),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 360.dp).semantics(mergeDescendants = true) {
                if (tone != ToolBoxStatusTone.Loading) liveRegion = LiveRegionMode.Polite
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                tone == ToolBoxStatusTone.Loading -> Box(Modifier.size(ToolBoxThemeTokens.sizes.emptyStateIcon), contentAlignment = Alignment.Center) {
                    ToolBoxBusyIndicator()
                }
                icon != null -> Box(
                    Modifier.size(ToolBoxThemeTokens.sizes.emptyStateIcon).clip(CircleShape).background(disc),
                    contentAlignment = Alignment.Center,
                ) {
                    ToolBoxIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                }
            }
            if (tone == ToolBoxStatusTone.Loading || icon != null) Spacer(Modifier.height(spacing.oneHalf))
            ToolBoxText(
                title,
                style = ToolBoxThemeTokens.textStyles.headline.copy(color = colors.textPrimary, textAlign = TextAlign.Center),
            )
            message?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(spacing.half))
                ToolBoxText(
                    it,
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(spacing.md))
                ToolBoxTextButton(actionLabel, onAction)
            }
        }
    }
}

enum class ToolBoxTone { Success, Warning, Danger, Info, Neutral }

/** Semantic container / content colours for chips and banners. */
@Composable
fun toolBoxToneColors(tone: ToolBoxTone): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val c = ToolBoxThemeTokens.colors
    return when (tone) {
        ToolBoxTone.Success -> c.softSuccess to c.onSoftSuccess
        ToolBoxTone.Warning -> c.softWarning to c.onSoftWarning
        ToolBoxTone.Danger -> c.softDanger to c.onSoftDanger
        ToolBoxTone.Info -> c.softInfo to c.onSoftInfo
        ToolBoxTone.Neutral -> c.softNeutral to c.onSoftNeutral
    }
}

/**
 * Banner with a semantic tone. Tone changes cross-fade the colours and the text fades through,
 * so a status update reads as one surface changing rather than a new one appearing.
 */
@Composable
fun ToolBoxBanner(
    text: String,
    tone: ToolBoxTone,
    modifier: Modifier = Modifier,
    icon: ToolBoxIconKey? = null,
) {
    val (container, content) = toolBoxToneColors(tone)
    val bg = androidx.compose.animation.animateColorAsState(container, ToolBoxMotion.effects(), label = "banner container")
    val fg = androidx.compose.animation.animateColorAsState(content, ToolBoxMotion.effects(), label = "banner content")
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth()
            .clip(toolBoxShape(ToolBoxThemeTokens.radii.control))
            .drawBehind { drawRect(bg.value) }
            .padding(horizontal = ToolBoxThemeTokens.spacing.sm, vertical = ToolBoxThemeTokens.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) {
            ToolBoxIcon(icon, null, Modifier.size(18.dp), tint = fg.value)
            Spacer(Modifier.size(ToolBoxThemeTokens.spacing.xs))
        }
        androidx.compose.animation.AnimatedContent(
            targetState = text,
            transitionSpec = {
                androidx.compose.animation.fadeIn(ToolBoxMotion.effects()) togetherWith
                    androidx.compose.animation.fadeOut(ToolBoxMotion.effects())
            },
            label = "banner text",
        ) { value ->
            ToolBoxText(value, style = ToolBoxThemeTokens.textStyles.metadata.copy(color = fg.value))
        }
    }
}

/** Compact status chip (fully rounded) in a semantic tone. */
@Composable
fun ToolBoxStatusChip(text: String, tone: ToolBoxTone, modifier: Modifier = Modifier) {
    val (container, content) = toolBoxToneColors(tone)
    val bg = androidx.compose.animation.animateColorAsState(container, ToolBoxMotion.effects(), label = "chip container")
    val fg = androidx.compose.animation.animateColorAsState(content, ToolBoxMotion.effects(), label = "chip content")
    Box(
        modifier.clip(CircleShape).drawBehind { drawRect(bg.value) }
            .padding(horizontal = ToolBoxThemeTokens.spacing.sm, vertical = ToolBoxThemeTokens.spacing.xxs),
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = text,
            transitionSpec = {
                androidx.compose.animation.fadeIn(ToolBoxMotion.effects()) togetherWith
                    androidx.compose.animation.fadeOut(ToolBoxMotion.effects())
            },
            label = "chip text",
        ) { value ->
            ToolBoxText(value, style = ToolBoxThemeTokens.textStyles.label.copy(color = fg.value, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1)
        }
    }
}
