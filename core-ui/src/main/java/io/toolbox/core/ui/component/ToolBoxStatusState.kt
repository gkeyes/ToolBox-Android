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
                tone == ToolBoxStatusTone.Loading -> Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    ToolBoxBusyIndicator()
                }
                icon != null -> Box(
                    Modifier.size(52.dp).clip(CircleShape).background(disc),
                    contentAlignment = Alignment.Center,
                ) {
                    ToolBoxIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                }
            }
            if (tone == ToolBoxStatusTone.Loading || icon != null) Spacer(Modifier.height(spacing.oneHalf))
            ToolBoxText(
                title,
                style = ToolBoxThemeTokens.textStyles.title.copy(color = colors.textPrimary, textAlign = TextAlign.Center),
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
                Spacer(Modifier.height(spacing.one))
                ToolBoxTextButton(actionLabel, onAction, outlined = false)
            }
        }
    }
}
