package io.toolbox.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
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
    val rotation = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -90f else 90f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
            .semantics(mergeDescendants = true) {
                stateDescription = if (expanded) "已展开" else "已折叠"
                if (sectionHeading) heading()
            }
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "收起$title" else "展开$title",
                onClick = onClick,
            )
            .padding(horizontal = spacing.oneHalf, vertical = spacing.one),
        horizontalArrangement = Arrangement.spacedBy(spacing.one),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.half)) {
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
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(color = colors.textSecondary),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        ToolBoxIcon(
            icon = ToolBoxIconKey.ChevronRight,
            contentDescription = null,
            modifier = Modifier.rotate(if (expanded) rotation else 0f),
        )
    }
}
