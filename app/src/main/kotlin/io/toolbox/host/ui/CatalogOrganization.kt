package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.toolbox.core.data.CatalogGroup
import io.toolbox.core.data.CatalogSort
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.*

internal val CatalogSort.label: String get() = when (this) {
    CatalogSort.NAME -> "名称首字母"
    CatalogSort.INSTALLED -> "安装时间"
    CatalogSort.LAST_OPENED -> "最后打开"
}

@Composable
internal fun HomeSectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppText(title, Modifier.weight(1f).semantics { heading() },
            textStyle = ToolBoxThemeTokens.textStyles.title.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold))
        if (action != null) ToolBoxTextButton(action, onAction, outlined = false)
    }
}

internal fun homeGridColumnCount(availableWidth: Float, fontScale: Float): Int =
    ((availableWidth + 12f) / (72f * fontScale.coerceAtLeast(1f) + 12f)).toInt().coerceAtLeast(1)

internal fun LazyListScope.catalogHomeSections(
    state: CatalogUiState,
    toolsById: Map<String, CatalogTool>,
    editing: Boolean,
    columns: Int,
    drag: CatalogHomeDragState,
    onAction: (CatalogAction) -> Unit,
    onEditGroup: (String) -> Unit,
    onAddFavorites: () -> Unit,
    onOptions: (String) -> Unit,
) {
    item("favorites-heading") { HomeSectionHeader("收藏", if (editing) "添加" else null, onAddFavorites) }
    val favorites = state.layout.favorites.mapNotNull(toolsById::get)
    if (favorites.isEmpty()) item("favorites-empty") {
        HomeEmptySection("收藏常用工具，放在这里快速打开", "添加收藏", onAddFavorites)
    }
    homeToolGrid(favorites, "favorite", "favorites", columns, editing, drag, onAction, onOptions,
        onMove = { tool, offset -> onAction(CatalogAction.MoveFavorite(tool.toolId, offset)) })
    item("groups-heading") {
        Spacer(Modifier.height(20.dp))
        HomeSectionHeader("分组")
    }
    if (state.layout.groups.isEmpty()) item("groups-empty") {
        AppText("按用途整理工具，同一个工具可以加入多个分组。", color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata, modifier = Modifier.padding(vertical = 12.dp))
    }
    state.layout.groups.forEachIndexed { index, group ->
        val members = group.members.mapNotNull(toolsById::get)
        val expanded = group.expanded && !drag.collapsingGroups
        item("group:" + group.id, contentType = "group-header") {
            GroupHeader(group, members.size, expanded, editing, index, state.layout.groups.size, drag,
                onExpand = { onAction(CatalogAction.SetGroupExpanded(group.id, !group.expanded)) },
                onEdit = { onEditGroup(group.id) },
                onMove = { onAction(CatalogAction.MoveGroup(group.id, it)) })
        }
        if (expanded) {
            if (members.isEmpty()) item("empty-group:" + group.id) {
                Box(Modifier.groupSurface(bottom = true).padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    ToolBoxTextButton("添加工具", { onEditGroup(group.id) }, outlined = false)
                }
            }
            homeToolGrid(members, "member:" + group.id, "members:" + group.id, columns, editing, drag, onAction, onOptions,
                grouped = true, onMove = { tool, offset -> onAction(CatalogAction.MoveMember(group.id, tool.toolId, offset)) })
        }
        item("after-group:" + group.id) { Spacer(Modifier.height(12.dp)) }
    }
}

/** Lazy rows retain one continuous group surface without nesting another vertical scroll container. */
private fun LazyListScope.homeToolGrid(
    tools: List<CatalogTool>,
    prefix: String,
    collection: String,
    columns: Int,
    editing: Boolean,
    drag: CatalogHomeDragState,
    onAction: (CatalogAction) -> Unit,
    onOptions: (String) -> Unit,
    grouped: Boolean = false,
    onMove: (CatalogTool, Int) -> Unit,
) {
    val rows = ((tools.size.toLong() + columns - 1) / columns).toInt()
    val rowPrefix = if (prefix == "favorite") "favorite-row" else "member-row:" + prefix.removePrefix("member:")
    items(rows, key = { rowPrefix + ":" + it }, contentType = { "home-grid-row" }) { row ->
        Row(
            Modifier.fillMaxWidth()
                .then(if (grouped) Modifier.groupSurface(bottom = row == rows - 1) else Modifier)
                .padding(start = 16.dp, end = 16.dp,
                    bottom = if (row < rows - 1) 12.dp else if (grouped) 8.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(columns) { column ->
                val index = row * columns + column
                val tool = tools.getOrNull(index)
                if (tool == null) Spacer(Modifier.weight(1f))
                else key(tool.toolId) {
                    CatalogHomeTile(
                        tool, editing,
                        onOpen = { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) },
                        onOptions = { onOptions(tool.toolId) },
                        modifier = Modifier.weight(1f).testTag(prefix + ":" + tool.toolId)
                            .catalogDragTarget(drag, prefix + ":" + tool.toolId, collection, index, tools.size, tool.name, tool,
                                enabled = editing, onMove = { onMove(tool, it) }),
                        onMoveBefore = if (editing && index > 0) ({ onMove(tool, -1) }) else null,
                        onMoveAfter = if (editing && index < tools.lastIndex) ({ onMove(tool, 1) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun Modifier.groupSurface(top: Boolean = false, bottom: Boolean = false): Modifier {
    val radius = ToolBoxThemeTokens.radii.card
    val shape = RoundedCornerShape(
        topStart = if (top) radius else 0.dp, topEnd = if (top) radius else 0.dp,
        bottomStart = if (bottom) radius else 0.dp, bottomEnd = if (bottom) radius else 0.dp,
    )
    return fillMaxWidth().clip(shape).background(ToolBoxThemeTokens.colors.surface, shape)
}

@Composable
private fun GroupHeader(
    group: CatalogGroup, count: Int, expanded: Boolean, editing: Boolean,
    index: Int, total: Int, drag: CatalogHomeDragState,
    onExpand: () -> Unit, onEdit: () -> Unit, onMove: (Int) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier.groupSurface(top = true, bottom = !expanded)
            .indication(interactionSource, LocalIndication.current)
            .catalogDragTarget(drag, "group:" + group.id, "groups", index, total, group.name,
                enabled = editing, onMove = onMove)
            .testTag("group:" + group.id),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp).testTag("catalog_group:" + group.id)
                .semantics {
                    stateDescription = if (expanded) "已展开" else "已收起"
                    customActions = buildList {
                        add(CustomAccessibilityAction("编辑分组" + group.name) { onEdit(); true })
                        if (editing && index > 0) add(CustomAccessibilityAction("前移") { onMove(-1); true })
                        if (editing && index < total - 1) add(CustomAccessibilityAction("后移") { onMove(1); true })
                    }
                }
                .then(if (editing) Modifier.clickable(interactionSource = interactionSource, indication = null,
                        role = Role.Button, onClick = onExpand)
                    else Modifier.combinedClickable(interactionSource = interactionSource, indication = null,
                        role = Role.Button, onClick = onExpand, onLongClick = onEdit,
                        onLongClickLabel = "编辑分组" + group.name))
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText(group.name, Modifier.weight(1f), maxLines = 2,
                textStyle = ToolBoxThemeTokens.textStyles.title.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold))
            AppText(count.toString(), color = ToolBoxThemeTokens.colors.textSecondary,
                modifier = Modifier.semantics { contentDescription = count.toString() + " 个工具" },
                textStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 13.sp))
            ToolBoxIcon(if (expanded) ToolBoxIconKey.ChevronDown else ToolBoxIconKey.ChevronRight, null,
                tint = ToolBoxThemeTokens.colors.textSecondary)
        }
        if (editing) ToolBoxIconButton(ToolBoxIconKey.More, "编辑分组" + group.name, onEdit)
        else Spacer(Modifier.width(8.dp))
    }
}

@Composable
internal fun CatalogHomeTile(
    tool: CatalogTool,
    editing: Boolean,
    onOpen: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
    onMoveBefore: (() -> Unit)? = null,
    onMoveAfter: (() -> Unit)? = null,
) {
    Column(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = tool.name
                customActions = buildList {
                    add(CustomAccessibilityAction("收藏、分组与管理") { onOptions(); true })
                    onMoveBefore?.let { add(CustomAccessibilityAction("前移") { it(); true }) }
                    onMoveAfter?.let { add(CustomAccessibilityAction("后移") { it(); true }) }
                }
            }
            .then(if (editing) Modifier.clickable(role = Role.Button, onClickLabel = "收藏、分组与管理", onClick = onOptions)
                else Modifier.combinedClickable(role = Role.Button, onClickLabel = "打开" + tool.name, onClick = onOpen,
                    onLongClickLabel = "收藏、分组与管理", onLongClick = onOptions))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.testTag("catalog_home_icon:" + tool.toolId)) {
            CatalogToolGlyph(toolId = tool.toolId, versionCode = tool.versionCode,
                visual = tool.visual(ToolBoxThemeTokens.colors.primary), size = 52.dp)
            if (editing) Box(Modifier.align(Alignment.TopEnd).size(20.dp)
                .background(ToolBoxThemeTokens.colors.surfaceMuted, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                ToolBoxIcon(ToolBoxIconKey.More, null, Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        AppText(tool.name, maxLines = 2, align = TextAlign.Center,
            textStyle = ToolBoxThemeTokens.textStyles.label.copy(fontSize = 13.sp, lineHeight = 18.sp))
    }
}

@Composable
private fun HomeEmptySection(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(ToolBoxThemeTokens.colors.surface, RoundedCornerShape(ToolBoxThemeTokens.radii.card))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText(message, color = ToolBoxThemeTokens.colors.textSecondary, textStyle = ToolBoxThemeTokens.textStyles.metadata)
        ToolBoxTextButton(action, onAction, outlined = false)
    }
}
