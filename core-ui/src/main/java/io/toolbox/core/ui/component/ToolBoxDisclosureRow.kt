package io.toolbox.core.ui.component

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
fun ToolBoxDisclosureRow(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    sectionHeading: Boolean = false,
) {
    val spacing = ToolBoxThemeTokens.spacing
    val colors = ToolBoxThemeTokens.colors
    // Chevron points down when collapsed and flips 180° on the spatial spring when expanded.
    val angle = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = ToolBoxMotion.spatial(),
        label = "disclosure direction",
    )
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ToolBoxRowMinHeight)
            .semantics(mergeDescendants = true) {
                stateDescription = if (expanded) "已展开" else "已折叠"
                if (sectionHeading) heading()
            }
            .toolBoxPressFeedback(interactionSource, scale = ToolBoxMotion.RowPressScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = if (expanded) "收起$title" else "展开$title",
                onClick = onClick,
            )
            .padding(
                start = ToolBoxRowStartPadding,
                end = ToolBoxRowEndPadding,
                top = ToolBoxRowVerticalPadding,
                bottom = ToolBoxRowVerticalPadding,
            ),
        horizontalArrangement = Arrangement.spacedBy(spacing.one),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            ToolBoxText(
                text = title,
                style = ToolBoxThemeTokens.textStyles.title.copy(
                    color = if (expanded) colors.primary else colors.textPrimary,
                    fontWeight = if (sectionHeading) FontWeight.SemiBold else FontWeight.Normal,
                ),
            )
            summary?.takeIf(String::isNotBlank)?.let {
                ToolBoxText(
                    text = it,
                    style = ToolBoxThemeTokens.textStyles.sectionTitle.copy(color = colors.textSecondary),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        ToolBoxIcon(
            icon = ToolBoxIconKey.ChevronDown,
            contentDescription = null,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle.value },
            tint = colors.textTertiary,
        )
    }
}
