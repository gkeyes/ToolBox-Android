package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState

@Composable
internal fun CatalogFavoritesPicker(
    state: CatalogUiState,
    onAction: (CatalogAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val writes = rememberPanelWrites(state, onAction)
    val session = rememberPanelSession()
    val dismiss = { session.dismiss { writes.forgetAll(onAction); onDismiss() } }
    val tools = matchingTools(state.tools, query)
    val order = remember(state.layout.favorites) { state.layout.favorites.withIndex().associate { (index, id) -> id to index + 1 } }
    ToolBoxActionSheet("添加收藏", dismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Four icons per row on phones, as the home "常用" grid; fewer with very large text.
            val columns = homeGridColumnCount((maxWidth - 12.dp).value, LocalDensity.current.fontScale).coerceIn(1, 4)
            val rows = (tools.size + columns - 1) / columns
            // Grid rows join into one card, so spacing is per item instead of an arrangement.
            LazyColumn(Modifier.fillMaxWidth().testTag("catalog_favorites_picker")) {
                item("heading") { Box(Modifier.padding(bottom = 8.dp)) { PanelHeader("添加收藏", dismiss) } }
                item("summary") {
                    AppText("已收藏 ${state.layout.favorites.size} 个 · 勾选后自动保存，按勾选顺序显示在首页“常用”",
                        Modifier.padding(bottom = 8.dp),
                        textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
                }
                item("search") {
                    ToolBoxSearchField(query, { query = it }, "搜索工具", Modifier.padding(bottom = 12.dp).testTag("catalog_favorites_search"))
                }
                if (tools.isEmpty()) item("empty") {
                    PanelEmpty(if (state.tools.isEmpty()) "导入工具后，可在这里添加收藏。" else "没有匹配的工具")
                    if (query.isNotBlank()) ToolBoxTextButton("清除搜索", { query = "" }, outlined = false)
                }
                items(rows, key = { "tool-row:$it" }) { row ->
                    val radius = ToolBoxThemeTokens.radii.card
                    val shape = RoundedCornerShape(
                        topStart = if (row == 0) radius else 0.dp, topEnd = if (row == 0) radius else 0.dp,
                        bottomStart = if (row == rows - 1) radius else 0.dp, bottomEnd = if (row == rows - 1) radius else 0.dp,
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(shape).background(ToolBoxThemeTokens.colors.surfaceMuted)
                            .padding(start = 6.dp, end = 6.dp, top = if (row == 0) 16.dp else 4.dp, bottom = if (row == rows - 1) 14.dp else 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        repeat(columns) { column ->
                            val tool = tools.getOrNull(row * columns + column)
                            if (tool == null) Spacer(Modifier.weight(1f))
                            else key(tool.toolId) {
                                val target = "favorite:${tool.toolId}"
                                FavoritePickerCell(
                                    tool = tool,
                                    order = order[tool.toolId],
                                    enabled = !writes.isWriting(target, state),
                                    busy = writes.isWriting(target, state),
                                    modifier = Modifier.weight(1f).testTag("favorite-tool:${tool.toolId}"),
                                ) { selected ->
                                    writes.submit(target, onAction) { id -> CatalogAction.SetFavorite(tool.toolId, selected, id) }
                                }
                            }
                        }
                    }
                }
                val errors = tools.mapNotNull { tool -> writes.error("favorite:${tool.toolId}", state) }.distinct()
                if (errors.isNotEmpty()) item("errors") { Column(Modifier.padding(top = 8.dp)) { errors.forEach { PanelError(it) } } }
            }
        }
    }
}

@Composable
private fun FavoritePickerCell(
    tool: CatalogTool,
    order: Int?,
    enabled: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onChecked: (Boolean) -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberToolBoxHaptics()
    val selected = order != null
    Column(
        modifier
            .toolBoxPressFeedback(interaction, enabled = enabled, shape = toolBoxShape(16.dp))
            .toggleable(selected, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Checkbox,
                onValueChange = { haptics.toggle(it); onChecked(it) })
            .semantics { if (busy) stateDescription = "正在保存" }
            .graphicsLayer { alpha = if (enabled) 1f else 0.6f }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            CatalogToolGlyph(tool.toolId, tool.versionCode, tool.visual(colors.primary), size = 56.dp)
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else colors.surface)
                    .then(if (selected) Modifier else Modifier.border(1.5.dp, colors.textTertiary, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) ToolBoxBusyIndicator()
                else if (order != null) ToolBoxText(order.toString(), style = ToolBoxThemeTokens.textStyles.label.copy(
                    color = colors.onPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            }
        }
        Spacer(Modifier.height(7.dp))
        ToolBoxText(tool.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = ToolBoxThemeTokens.textStyles.label.copy(color = colors.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center))
    }
}
