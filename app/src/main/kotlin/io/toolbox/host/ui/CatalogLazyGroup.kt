package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/** One lazy item owns one row; adjacent items still form a single rounded group. */
@Composable
internal fun CatalogLazyGroupItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    dividerStart: Dp = ToolBoxThemeTokens.spacing.oneHalf,
    content: @Composable ColumnScope.() -> Unit,
) {
    val radius = ToolBoxThemeTokens.radii.denseSurface
    val shape = RoundedCornerShape(
        topStart = if (index == 0) radius else 0.dp,
        topEnd = if (index == 0) radius else 0.dp,
        bottomStart = if (index == count - 1) radius else 0.dp,
        bottomEnd = if (index == count - 1) radius else 0.dp,
    )
    Column(modifier.fillMaxWidth().clip(shape).background(ToolBoxThemeTokens.colors.surface)) {
        content()
        if (index < count - 1) ToolBoxGroupDivider(startPadding = dividerStart)
    }
}
