package io.toolbox.host.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.toolbox.core.data.CatalogGroup
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState

@Composable
internal fun CatalogToolOptions(
    tool: CatalogTool,
    state: CatalogUiState,
    onAction: (CatalogAction) -> Unit,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) = key(tool.toolId) {
    CatalogToolOptionsSession(tool, state, onAction, onDismiss, onManage)
}

@Composable
private fun CatalogToolOptionsSession(
    tool: CatalogTool,
    state: CatalogUiState,
    onAction: (CatalogAction) -> Unit,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    var page by rememberSaveable(tool.toolId) { mutableStateOf("options") }
    var query by rememberSaveable(tool.toolId) { mutableStateOf("") }
    var discardDestination by rememberSaveable(tool.toolId) { mutableStateOf<String?>(null) }
    val initialMembers = listOf(tool.toolId)
    val newGroupDraft = rememberGroupDraft(null, initialMembers)
    val editorListState = rememberLazyListState()
    val writes = rememberPanelWrites(state, onAction)
    val session = rememberPanelSession()
    val dismiss = { session.dismiss { writes.forgetAll(onAction); onDismiss() } }
    val leaveEditor: (String) -> Unit = { destination ->
        discardDestination = null
        writes.forget(EDITOR_WRITE, onAction)
        newGroupDraft.reset(initialMembers)
        if (destination == "groups") page = "groups" else dismiss()
    }
    val requestLeave: (String) -> Unit = { destination ->
        if (!writes.isWriting(EDITOR_WRITE, state)) {
            if (page == "create" && newGroupDraft.hasChanges(null, initialMembers)) discardDestination = destination
            else if (page == "create") leaveEditor(destination)
            else dismiss()
        }
    }
    val currentTool = state.tools.firstOrNull { it.toolId == tool.toolId }
    if (state.isLoaded && currentTool == null) {
        LaunchedEffect(tool.toolId) { dismiss() }
        return
    }
    val displayTool = currentTool ?: tool
    val favorite = tool.toolId in state.layout.favorites
    ToolBoxActionSheet(
        title = if (page == "options") displayTool.name else if (page == "groups") "加入分组" else "新建分组",
        onDismissRequest = { requestLeave("dismiss") },
        onBackRequest = {
            when {
                discardDestination != null -> discardDestination = null
                page == "create" -> requestLeave("groups")
                page == "groups" -> page = "options"
                else -> dismiss()
            }
        },
    ) {
        val destination = discardDestination
        if (destination != null) {
            DiscardGroupChanges(onDiscard = { leaveEditor(destination) }, onKeepEditing = { discardDestination = null })
        } else when (page) {
            "create" -> GroupEditorContent(
                group = null, state = state, creating = true,
                draft = newGroupDraft, listState = editorListState,
                writes = writes,
                onAction = onAction,
                onCancel = { requestLeave("groups") }, onClose = { requestLeave("dismiss") },
                onSaved = { newGroupDraft.reset(initialMembers); page = "groups" },
            )
            "groups" -> Column(Modifier.testTag("catalog_group_picker")) {
                val groups = state.layout.groups.filter { it.name.contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item("heading") { PanelHeader("加入分组", dismiss, onBack = { page = "options" }) }
                    item("context") {
                        AppText("${displayTool.name} · 已加入 ${state.layout.groups.count { tool.toolId in it.members }} 个分组",
                            textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
                        AppText("勾选后自动保存", textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
                    }
                    item("search") {
                        ToolBoxSearchField(query, { query = it }, "搜索分组", Modifier.testTag("catalog_group_search"))
                    }
                    if (groups.isEmpty()) item("empty") {
                        PanelEmpty(if (state.layout.groups.isEmpty()) "还没有分组，创建一个来整理工具。" else "没有匹配的分组")
                        if (query.isNotBlank()) ToolBoxTextButton("清除搜索", { query = "" }, outlined = false)
                    }
                    items(groups, key = { "group:${it.id}" }) { group ->
                        val target = "group:${group.id}"
                        Column(Modifier.animateItem(fadeInSpec = ToolBoxMotion.effects(), placementSpec = ToolBoxMotion.ItemPlacement, fadeOutSpec = ToolBoxMotion.effects())) {
                            PanelCheckRow(
                                title = group.name,
                                summary = "${group.members.size} 个工具",
                                selected = tool.toolId in group.members,
                                enabled = !writes.isWriting(target, state),
                                busy = writes.isWriting(target, state),
                                modifier = Modifier.testTag("membership:${group.id}"),
                                icon = { CatalogGroupStyleIcon(group, 36.dp, 20.dp) },
                                onChecked = { selected ->
                                    writes.submit(target, onAction) { id ->
                                        CatalogAction.SetGroupMembership(group.id, tool.toolId, selected, id)
                                    }
                                },
                            )
                            writes.error(target, state)?.let { PanelError(it) }
                        }
                    }
                }
                ToolBoxTextButton("新建分组", { page = "create" }, Modifier.fillMaxWidth().testTag("catalog_group_create"), outlined = false)
            }
            else -> LazyColumn(
                Modifier.fillMaxWidth().testTag("catalog_tool_options"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("heading") { PanelHeader(displayTool.name, dismiss, tool = displayTool) }
                item("shortcuts") {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PanelShortcut(
                            label = if (favorite) "已收藏" else "收藏",
                            icon = if (favorite) ToolBoxIconKey.StarFilled else ToolBoxIconKey.Star,
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("catalog_tool_favorite"),
                            selected = favorite,
                            enabled = !writes.isWriting("favorite", state),
                            busy = writes.isWriting("favorite", state),
                        ) { writes.submit("favorite", onAction) { id -> CatalogAction.SetFavorite(tool.toolId, !favorite, id) } }
                        PanelShortcut("加入分组", ToolBoxIconKey.Folder, Modifier.weight(1f).fillMaxHeight().testTag("catalog_tool_groups")) {
                            page = "groups"
                        }
                    }
                }
                writes.error("favorite", state)?.let { error -> item("error") { PanelError(error) } }
                item("divider") { PanelDivider() }
                item("manage") {
                    ToolBoxSettingRow(
                        title = "管理工具", summary = "权限、信息与卸载", icon = ToolBoxIconKey.Settings,
                        modifier = Modifier.testTag("catalog_tool_manage"),
                        onClick = { session.dismiss { writes.forgetAll(onAction); onDismiss(); onManage() } },
                    )
                }
            }
        }
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
    // Deletion removes the projection before its write receipt can be rendered.
    // Retain the editing identity so the same session detaches its own receipt.
    var lastGroupId by rememberSaveable { mutableStateOf(group?.id) }
    if (group != null) SideEffect { lastGroupId = group.id }
    key(creating, group?.id ?: lastGroupId) {
        CatalogGroupEditorSession(group, state, creating, onAction, onDismiss)
    }
}

@Composable
private fun CatalogGroupEditorSession(
    group: CatalogGroup?,
    state: CatalogUiState,
    creating: Boolean,
    onAction: (CatalogAction) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keep the draft and write receipts outside the sheet's conditional content.
    val draft = rememberGroupDraft(group)
    val listState = rememberLazyListState()
    val writes = rememberPanelWrites(state, onAction)
    val session = rememberPanelSession()
    var discarding by rememberSaveable { mutableStateOf(false) }
    val dismiss = { session.dismiss { writes.forgetAll(onAction); onDismiss() } }
    val requestDismiss = {
        if (!writes.isWriting(EDITOR_WRITE, state)) {
            if (draft.hasChanges(group)) discarding = true else dismiss()
        }
    }
    if (!creating && group == null) {
        if (state.isLoaded) LaunchedEffect(Unit) { dismiss() }
        return
    }
    ToolBoxActionSheet(
        title = if (creating) "新建分组" else "编辑分组",
        onDismissRequest = requestDismiss,
        onBackRequest = {
            when {
                discarding -> discarding = false
                draft.confirmingDelete.value -> if (!writes.isWriting(EDITOR_WRITE, state)) draft.confirmingDelete.value = false
                else -> requestDismiss()
            }
        },
    ) {
        if (discarding) {
            DiscardGroupChanges(onDiscard = dismiss, onKeepEditing = { discarding = false })
        } else {
            GroupEditorContent(group, state, creating, draft, listState, writes,
                onAction = onAction, onCancel = requestDismiss, onClose = requestDismiss, onSaved = dismiss)
        }
    }
}

@Composable
private fun DiscardGroupChanges(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    HostConfirmationContent(
        title = "放弃本次修改？",
        summary = "分组名称、样式和成员尚未保存。继续编辑会保留当前选择。",
        confirmLabel = "放弃修改", cancelLabel = "继续编辑", destructive = true,
        onConfirm = onDiscard, onCancel = onKeepEditing,
    )
}
