package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.toolbox.core.data.CatalogGroup
import io.toolbox.core.data.CatalogSort
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.*
import kotlin.math.abs

internal val CatalogSort.label: String get() = when (this) {
    CatalogSort.NAME -> "名称首字母"
    CatalogSort.INSTALLED -> "安装时间"
    CatalogSort.LAST_OPENED -> "最后打开"
}

internal fun LazyListScope.catalogHomeSections(
    state: CatalogUiState,
    toolsById: Map<String, CatalogTool>,
    editing: Boolean,
    listState: LazyListState,
    onAction: (CatalogAction) -> Unit,
    onEdit: () -> Unit,
    onEditGroup: (String) -> Unit,
    onOptions: (String) -> Unit,
) {
    item("home-edit") {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolBoxTextButton(if (editing) "完成编辑" else "编辑首页", onEdit, Modifier.weight(1f))
                ToolBoxTextButton("新建分组", { onEditGroup("") }, Modifier.weight(1f))
            }
        }
    }
    item("favorites-heading") { SectionHeader("收藏") }
    val favorites = state.layout.favorites.mapNotNull(toolsById::get)
    if (favorites.isEmpty()) item("favorites-empty") { CatalogStatusState("在工具的“更多”中添加收藏") }
    val favoriteKeys = favorites.map { "favorite:${it.toolId}" }
    itemsIndexed(favorites, key = { _, tool -> "favorite:${tool.toolId}" }, contentType = { _, _ -> "favorite" }) { index, tool ->
        CatalogOrderedItem(favoriteKeys[index], tool.name, favoriteKeys, index, editing, listState,
            onMove = { onAction(CatalogAction.MoveFavorite(tool.toolId, it)) }) {
            CatalogToolRow(tool, { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) }, { onOptions(tool.toolId) })
        }
    }
    item("groups-heading") { SectionHeader("分组") }
    if (state.layout.groups.isEmpty()) item("groups-empty") { CatalogStatusState("新建分组，将工具按用途整理") }
    val groupKeys = state.layout.groups.map { "group:${it.id}" }
    state.layout.groups.forEachIndexed { index, group ->
        item("group:${group.id}", contentType = "group") {
            CatalogOrderedItem(groupKeys[index], "分组${group.name}", groupKeys, index, editing, listState,
                onMove = { onAction(CatalogAction.MoveGroup(group.id, it)) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ToolBoxDisclosureRow(
                            title = group.name, expanded = group.expanded,
                            summary = "${group.members.count(toolsById::containsKey)} 个工具",
                            onClick = { onAction(CatalogAction.SetGroupExpanded(group.id, !group.expanded)) },
                            modifier = Modifier.testTag("catalog_group:${group.id}"),
                        )
                    }
                    ToolBoxTextButton("编辑", { onEditGroup(group.id) }, modifier = Modifier.semantics { contentDescription = "编辑分组${group.name}" })
                }
            }
        }
        if (group.expanded) {
            val members = group.members.mapNotNull(toolsById::get)
            val memberKeys = members.map { "member:${group.id}:${it.toolId}" }
            if (members.isEmpty()) item("empty-group:${group.id}") { CatalogStatusState("空分组，点“编辑”添加工具") }
            itemsIndexed(members, key = { _, tool -> "member:${group.id}:${tool.toolId}" }, contentType = { _, _ -> "member" }) { memberIndex, tool ->
                CatalogOrderedItem(memberKeys[memberIndex], "${group.name}中的${tool.name}", memberKeys, memberIndex, editing, listState,
                    onMove = { onAction(CatalogAction.MoveMember(group.id, tool.toolId, it)) }) {
                    CatalogToolRow(tool, { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) }, { onOptions(tool.toolId) })
                }
            }
        }
    }
}

/** Drag targets are actual measured lazy rows, so large text and expanded groups do not change ordering semantics. */
@Composable
private fun CatalogOrderedItem(
    itemKey: String,
    label: String,
    siblingKeys: List<String>,
    index: Int,
    editing: Boolean,
    listState: LazyListState,
    onMove: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    var dragOffset by remember(itemKey) { mutableFloatStateOf(0f) }
    var dragging by remember(itemKey) { mutableStateOf(false) }
    var startCenter by remember(itemKey) { mutableFloatStateOf(0f) }
    val latestMove by rememberUpdatedState(onMove)
    val latestKeys by rememberUpdatedState(siblingKeys)
    val latestIndex by rememberUpdatedState(index)
    Column(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
        .graphicsLayer { translationY = dragOffset }
        .testTag(itemKey)) {
        content()
        if (editing) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("drag:$itemKey")
                    .semantics {
                        contentDescription = "长按拖动$label"
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("前移") { onMove(-1); true })
                            if (index < siblingKeys.lastIndex) add(CustomAccessibilityAction("后移") { onMove(1); true })
                        }
                    }
                    .pointerInput(itemKey, editing) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                startCenter = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
                                    ?.let { it.offset + it.size / 2f } ?: 0f
                                dragOffset = 0f
                                dragging = true
                            },
                            onDrag = { change, amount -> change.consume(); dragOffset += amount.y },
                            onDragCancel = { dragging = false; dragOffset = 0f },
                            onDragEnd = {
                                val target = listState.layoutInfo.visibleItemsInfo
                                    .filter { item -> latestKeys.any { it == item.key } }
                                    .minByOrNull { abs(it.offset + it.size / 2f - (startCenter + dragOffset)) }
                                val targetIndex = target?.let { latestKeys.indexOf(it.key.toString()) } ?: -1
                                if (targetIndex >= 0 && targetIndex != latestIndex) latestMove(targetIndex - latestIndex)
                                dragging = false
                                dragOffset = 0f
                            },
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) { AppText("≡ 拖动排序", color = ToolBoxThemeTokens.colors.textSecondary) }
            ToolBoxTextButton("前移", { onMove(-1) }, enabled = index > 0,
                modifier = Modifier.semantics { contentDescription = "前移$label" })
            ToolBoxTextButton("后移", { onMove(1) }, enabled = index < siblingKeys.lastIndex,
                modifier = Modifier.semantics { contentDescription = "后移$label" })
        }
    }
}

@Composable
internal fun CatalogToolOptions(
    tool: CatalogTool,
    state: CatalogUiState,
    onAction: (CatalogAction) -> Unit,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    ToolBoxModalDialog(onDismiss) {
        AppText(tool.name, modifier = Modifier.semantics { heading() }, textStyle = ToolBoxThemeTokens.textStyles.title)
        ToolBoxSwitchSettingRow("收藏", checked = tool.toolId in state.layout.favorites,
            onCheckedChange = { onAction(CatalogAction.SetFavorite(tool.toolId, it)) })
        if (state.layout.groups.isNotEmpty()) SectionHeader("加入分组")
        state.layout.groups.forEach { group ->
            ToolBoxSwitchSettingRow(group.name, checked = tool.toolId in group.members,
                modifier = Modifier.testTag("membership:${group.id}"),
                onCheckedChange = { onAction(CatalogAction.SetGroupMembership(group.id, tool.toolId, it)) })
        }
        ToolBoxSettingRow("管理工具", onClick = onManage)
        ToolBoxTextButton("完成", onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun CatalogGroupEditor(
    group: CatalogGroup?,
    state: CatalogUiState,
    creating: Boolean,
    onAction: (CatalogAction) -> Unit,
    onDismiss: () -> Unit,
) {
    if (group == null && !creating) { LaunchedEffect(Unit) { onDismiss() }; return }
    var name by rememberSaveable(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    var members by rememberSaveable(group?.id) { mutableStateOf(group?.members.orEmpty()) }
    var confirmDelete by rememberSaveable(group?.id) { mutableStateOf(false) }
    ToolBoxModalDialog(onDismiss) {
        AppText(if (creating) "新建分组" else "编辑分组", modifier = Modifier.semantics { heading() }, textStyle = ToolBoxThemeTokens.textStyles.title)
        Spacer(Modifier.height(12.dp))
        BasicTextField(name, { name = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .background(ToolBoxThemeTokens.colors.surfaceMuted, RoundedCornerShape(12.dp)).padding(12.dp)
                .semantics { contentDescription = "分组名称" }.testTag("catalog_group_name"),
            textStyle = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textPrimary),
            cursorBrush = SolidColor(ToolBoxThemeTokens.colors.primary),
            decorationBox = { input -> Box { if (name.isEmpty()) AppText("分组名称", color = ToolBoxThemeTokens.colors.textSecondary); input() } },
        )
        SectionHeader("成员")
        state.tools.forEach { tool ->
            ToolBoxSwitchSettingRow(tool.name, checked = tool.toolId in members,
                modifier = Modifier.testTag("group-tool:${tool.toolId}"),
                onCheckedChange = { selected -> members = if (selected) (members + tool.toolId).distinct() else members - tool.toolId })
        }
        Row(Modifier.fillMaxWidth()) {
            ToolBoxTextButton("取消", onDismiss, Modifier.weight(1f))
            ToolBoxPrimaryButton("保存", {
                onAction(CatalogAction.SaveGroup(group?.id, name, members))
                onDismiss()
            }, Modifier.weight(1f), enabled = name.isNotBlank())
        }
        if (group != null) {
            if (confirmDelete) {
                AppText("删除分组只移除这个分组，工具与收藏会保留。")
                ToolBoxDestructiveButton("确认删除分组", { onAction(CatalogAction.DeleteGroup(group.id)); onDismiss() }, Modifier.fillMaxWidth())
            } else ToolBoxTextButton("删除分组", { confirmDelete = true }, Modifier.fillMaxWidth(), contentColor = ToolBoxThemeTokens.colors.danger)
        }
    }
}
