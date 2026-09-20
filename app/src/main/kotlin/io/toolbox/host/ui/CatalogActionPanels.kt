package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.toolbox.core.data.CatalogGroup
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogLayoutWriteStatus
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import java.util.UUID
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CheckboxDefaults

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
    val newGroupDraft = rememberGroupDraft(null, listOf(tool.toolId))
    val editorListState = rememberLazyListState()
    val writes = rememberPanelWrites(state, onAction)
    val session = rememberPanelSession()
    val dismiss = { session.dismiss { writes.forgetAll(onAction); onDismiss() } }
    val currentTool = state.tools.firstOrNull { it.toolId == tool.toolId }
    if (state.isLoaded && currentTool == null) {
        LaunchedEffect(tool.toolId) { dismiss() }
        return
    }
    val displayTool = currentTool ?: tool
    val favorite = tool.toolId in state.layout.favorites
    ToolBoxActionSheet(
        title = if (page == "options") displayTool.name else if (page == "groups") "加入分组" else "新建分组",
        onDismissRequest = dismiss,
        onBackRequest = {
            when (page) {
                "create" -> {
                    writes.forget(EDITOR_WRITE, onAction)
                    newGroupDraft.reset(listOf(tool.toolId))
                    page = "groups"
                }
                "groups" -> page = "options"
                else -> dismiss()
            }
        },
    ) {
        when (page) {
            "create" -> GroupEditorContent(
                group = null, state = state, creating = true,
                draft = newGroupDraft, listState = editorListState,
                writes = writes,
                onAction = onAction,
                onCancel = { newGroupDraft.reset(listOf(tool.toolId)); page = "groups" }, onClose = dismiss,
                onSaved = { newGroupDraft.reset(listOf(tool.toolId)); page = "groups" },
            )
            "groups" -> Column(Modifier.testTag("catalog_group_picker")) {
                val groups = state.layout.groups.filter { it.name.contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item("heading") { PanelHeader("加入分组", dismiss, onBack = { page = "options" }) }
                    item("search") {
                        ToolBoxSearchField(query, { query = it }, "搜索分组", Modifier.testTag("catalog_group_search"))
                    }
                    if (groups.isEmpty()) item("empty") {
                        PanelEmpty(if (state.layout.groups.isEmpty()) "还没有分组，创建一个来整理工具。" else "没有匹配的分组")
                    }
                    items(groups, key = { "group:${it.id}" }) { group ->
                        val target = "group:${group.id}"
                        Column {
                            PanelCheckRow(
                                title = group.name,
                                selected = tool.toolId in group.members,
                                enabled = !writes.isWriting(target, state),
                                busy = writes.isWriting(target, state),
                                modifier = Modifier.testTag("membership:${group.id}"),
                                icon = { ToolBoxIcon(ToolBoxIconKey.Folder, null) },
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
    // Dialog content has a separate saveable-state owner. Keep the complete
    // editing session in the caller's composition, including after restoration.
    val draft = rememberGroupDraft(group)
    val listState = rememberLazyListState()
    val writes = rememberPanelWrites(state, onAction)
    val session = rememberPanelSession()
    val dismiss = { session.dismiss { writes.forgetAll(onAction); onDismiss() } }
    if (!creating && group == null) {
        if (state.isLoaded) LaunchedEffect(Unit) { dismiss() }
        return
    }
    ToolBoxActionSheet(
        title = if (creating) "新建分组" else "编辑分组",
        onDismissRequest = dismiss,
        onBackRequest = {
            if (draft.confirmingDelete.value) draft.confirmingDelete.value = false else dismiss()
        },
    ) {
        GroupEditorContent(group, state, creating, draft, listState, writes,
            onAction = onAction, onCancel = dismiss, onClose = dismiss, onSaved = dismiss)
    }
}

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
    ToolBoxActionSheet("添加收藏", dismiss) {
        LazyColumn(Modifier.fillMaxWidth().testTag("catalog_favorites_picker"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("heading") { PanelHeader("添加收藏", dismiss) }
            item("search") {
                ToolBoxSearchField(query, { query = it }, "搜索工具", Modifier.testTag("catalog_favorites_search"))
            }
            if (tools.isEmpty()) item("empty") { PanelEmpty(if (state.tools.isEmpty()) "导入工具后，可在这里添加收藏。" else "没有匹配的工具") }
            items(tools, key = { "tool:${it.toolId}" }) { tool ->
                val target = "favorite:${tool.toolId}"
                Column {
                    ToolCheckRow(tool, tool.toolId in state.layout.favorites, !writes.isWriting(target, state), "favorite-tool:${tool.toolId}") { selected ->
                        writes.submit(target, onAction) { id -> CatalogAction.SetFavorite(tool.toolId, selected, id) }
                    }
                    writes.error(target, state)?.let { PanelError(it) }
                }
            }
        }
    }
}

@Composable
private fun GroupEditorContent(
    group: CatalogGroup?,
    state: CatalogUiState,
    creating: Boolean,
    draft: GroupDraft,
    listState: LazyListState,
    writes: PanelWrites,
    onAction: (CatalogAction) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by draft.name
    var members by draft.members
    var query by draft.query
    var confirmingDelete by draft.confirmingDelete
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val finishInput = { focusManager.clearFocus(force = true); keyboard?.hide(); Unit }
    val operationId = writes.ids[EDITOR_WRITE]
    val status = operationId?.let(state.layoutWrites::get)
    val writing = status == CatalogLayoutWriteStatus.Writing
    val error = (status as? CatalogLayoutWriteStatus.Failed)?.message
    val latestSaved by rememberUpdatedState(onSaved)
    val latestClose by rememberUpdatedState(onClose)
    val forget = {
        operationId?.let { onAction(CatalogAction.ForgetLayoutWrite(it)) }
        writes.ids = writes.ids - EDITOR_WRITE
    }
    val cancel = { finishInput(); forget(); onCancel() }
    val close = { finishInput(); forget(); onClose() }
    val back = { if (confirmingDelete) confirmingDelete = false else cancel() }
    LaunchedEffect(operationId, status) {
        if (operationId != null && status == CatalogLayoutWriteStatus.Succeeded) {
            forget()
            latestSaved()
        }
    }
    LaunchedEffect(state.isLoaded, state.layout.groups, group?.id) {
        if (state.isLoaded && !creating && group != null && state.layout.groups.none { it.id == group.id }) {
            forget()
            latestClose()
        }
    }
    if (confirmingDelete && group != null) {
        LazyColumn(Modifier.fillMaxWidth().testTag("catalog_group_delete_confirmation"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("heading") { PanelHeader("删除分组", close, onBack = back) }
            item("message") {
                ToolBoxText("删除“${group.name}”？组内工具和收藏会保留。", style = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textPrimary))
            }
            error?.let { item("error") { PanelError(it, Modifier.testTag("catalog_group_error")) } }
            item("actions") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolBoxSecondaryButton("取消", { confirmingDelete = false }, Modifier.weight(1f))
                    ToolBoxDestructiveButton(
                        if (writing) "正在删除…" else "删除分组",
                        onClick = {
                            forget()
                            val id = UUID.randomUUID().toString()
                            writes.ids = writes.ids + (EDITOR_WRITE to id)
                            onAction(CatalogAction.DeleteGroup(group.id, id))
                        },
                        modifier = Modifier.weight(1f).testTag("catalog_group_delete_confirm"),
                        enabled = !writing,
                    )
                }
            }
        }
        return
    }
    val tools = matchingTools(state.tools, query)
    Column(Modifier.fillMaxWidth().testTag("catalog_group_editor")) {
        LazyColumn(Modifier.weight(1f, fill = false).testTag("catalog_group_members"), state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("heading") { PanelHeader(if (creating) "新建分组" else "编辑分组", close, onBack = back) }
            item("name") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolBoxText("分组名称", style = ToolBoxThemeTokens.textStyles.label.copy(color = ToolBoxThemeTokens.colors.textSecondary))
                    BasicTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        readOnly = writing,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finishInput() }),
                        textStyle = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textPrimary),
                        cursorBrush = SolidColor(ToolBoxThemeTokens.colors.primary),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(ToolBoxThemeTokens.radii.control))
                            .background(ToolBoxThemeTokens.colors.surfaceMuted)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .testTag("catalog_group_name")
                            .semantics { contentDescription = "分组名称" },
                        decorationBox = { field ->
                            Box {
                                if (name.isEmpty()) ToolBoxText("输入分组名称", style = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textSecondary))
                                field()
                            }
                        },
                    )
                }
            }
            item("search") {
                ToolBoxSearchField(query, { query = it }, "搜索工具", Modifier.testTag("catalog_member_search"))
            }
            if (tools.isEmpty()) item("empty") { PanelEmpty(if (state.tools.isEmpty()) "暂无工具，可以先保存空分组。" else "没有匹配的工具") }
            items(tools, key = { "tool:${it.toolId}" }) { tool ->
                ToolCheckRow(tool, tool.toolId in members, !writing, "group-tool:${tool.toolId}") { selected ->
                    members = if (selected) (members + tool.toolId).distinct() else members - tool.toolId
                }
            }
            if (!creating && group != null) item("delete") {
                PanelDivider()
                ToolBoxTextButton(
                    "删除分组", { finishInput(); confirmingDelete = true },
                    Modifier.fillMaxWidth().testTag("catalog_group_delete"),
                    enabled = !writing, contentColor = ToolBoxThemeTokens.colors.danger, outlined = false,
                )
            }
        }
        error?.let { PanelError(it, Modifier.testTag("catalog_group_error")) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolBoxSecondaryButton("取消", cancel, Modifier.weight(1f).testTag("catalog_group_cancel"))
            ToolBoxPrimaryButton(
                if (writing) "正在保存…" else "保存",
                onClick = {
                    finishInput()
                    forget()
                    val id = UUID.randomUUID().toString()
                    writes.ids = writes.ids + (EDITOR_WRITE to id)
                    val installed = state.tools.mapTo(HashSet()) { it.toolId }
                    onAction(CatalogAction.SaveGroup(if (creating) null else group?.id, name, members.filter(installed::contains), id))
                },
                modifier = Modifier.weight(1f).testTag("catalog_group_save"),
                enabled = !writing && name.isNotBlank(),
            )
        }
    }
}

@Composable
private fun PanelHeader(title: String, onClose: () -> Unit, onBack: (() -> Unit)? = null, tool: CatalogTool? = null) {
    ToolBoxActionSheetHeader(Modifier.testTag("catalog_panel_header")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) ToolBoxIconButton(ToolBoxIconKey.Back, "返回", onBack, Modifier.testTag("catalog_panel_back"))
            if (tool != null) CatalogToolGlyph(tool.toolId, tool.versionCode, tool.visual(ToolBoxThemeTokens.colors.primary))
            ToolBoxText(title, Modifier.weight(1f).semantics { heading() }, style = ToolBoxThemeTokens.textStyles.title.copy(color = ToolBoxThemeTokens.colors.textPrimary))
            ToolBoxIconButton(
                ToolBoxIconKey.Close, "关闭", onClose,
                Modifier.clip(RoundedCornerShape(ToolBoxThemeTokens.radii.full)).background(ToolBoxThemeTokens.colors.surfaceMuted).testTag("catalog_panel_close"),
            )
        }
    }
}

@Composable
private fun PanelShortcut(
    label: String,
    icon: ToolBoxIconKey,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    Row(
        modifier.clip(RoundedCornerShape(ToolBoxThemeTokens.radii.denseSurface))
            .background(if (selected) colors.softPrimary else colors.surfaceMuted)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (busy) stateDescription = "正在保存" }
            .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) ToolBoxBusyIndicator() else ToolBoxIcon(icon, null,
            tint = if (!enabled) ToolBoxThemeTokens.disabledContent else if (selected) colors.primary else colors.textSecondary)
        ToolBoxText(label, Modifier.weight(1f, fill = false), style = ToolBoxThemeTokens.textStyles.body.copy(
            color = if (enabled) colors.textPrimary else ToolBoxThemeTokens.disabledContent))
    }
}

@Composable
private fun ToolCheckRow(tool: CatalogTool, selected: Boolean, enabled: Boolean, tag: String, onChecked: (Boolean) -> Unit) {
    PanelCheckRow(tool.name, selected, enabled, Modifier.testTag(tag), icon = {
        CatalogToolGlyph(tool.toolId, tool.versionCode, tool.visual(ToolBoxThemeTokens.colors.primary), size = 40.dp)
    }, onChecked = onChecked)
}

@Composable
private fun PanelCheckRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    busy: Boolean = false,
    onChecked: (Boolean) -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(ToolBoxThemeTokens.radii.control))
            .toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = onChecked)
            .semantics { if (busy) stateDescription = "正在保存" }
            .heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        ToolBoxText(title, Modifier.weight(1f), style = ToolBoxThemeTokens.textStyles.body.copy(
            color = if (enabled) colors.textPrimary else ToolBoxThemeTokens.disabledContent))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (busy) ToolBoxBusyIndicator() else Checkbox(
            state = if (selected) ToggleableState.On else ToggleableState.Off,
            onClick = null, enabled = enabled, modifier = Modifier.clearAndSetSemantics { },
            colors = CheckboxDefaults.checkboxColors(
                checkedForegroundColor = colors.onPrimary,
                checkedBackgroundColor = colors.primary,
                uncheckedBackgroundColor = colors.divider,
                disabledCheckedBackgroundColor = colors.primary.copy(alpha = 0.5f),
                disabledUncheckedBackgroundColor = colors.divider,
            ),
        )
        }
    }
}

@Composable
private fun PanelDivider() { Spacer(Modifier.fillMaxWidth().height(1.dp).background(ToolBoxThemeTokens.colors.divider)) }

@Composable
private fun PanelEmpty(message: String) {
    ToolBoxText(message, Modifier.fillMaxWidth().padding(vertical = 24.dp), style = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textSecondary))
}

@Composable
private fun PanelError(message: String, modifier: Modifier = Modifier) {
    ToolBoxText(message, modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.danger))
}

private fun matchingTools(tools: List<CatalogTool>, query: String): List<CatalogTool> {
    val term = query.trim()
    return if (term.isEmpty()) tools else tools.filter { it.name.contains(term, true) || it.toolId.contains(term, true) }
}

private class GroupDraft(
    initialName: String,
    initialMembers: List<String>,
    initialQuery: String = "",
    initialConfirmingDelete: Boolean = false,
) {
    val name = mutableStateOf(initialName)
    val members = mutableStateOf(initialMembers)
    val query = mutableStateOf(initialQuery)
    val confirmingDelete = mutableStateOf(initialConfirmingDelete)

    fun reset(initialMembers: List<String>) {
        name.value = ""
        members.value = initialMembers
        query.value = ""
        confirmingDelete.value = false
    }

    companion object {
        val Saver = listSaver<GroupDraft, String>(
            save = { listOf(it.name.value, it.query.value, it.confirmingDelete.value.toString()) + it.members.value },
            restore = { GroupDraft(it[0], it.drop(3), it[1], it[2].toBoolean()) },
        )
    }
}

@Composable
private fun rememberGroupDraft(group: CatalogGroup?, initialMembers: List<String> = emptyList()): GroupDraft =
    rememberSaveable(saver = GroupDraft.Saver) { GroupDraft(group?.name.orEmpty(), group?.members ?: initialMembers) }

private class PanelSession {
    var active = true

    fun dismiss(action: () -> Unit) {
        if (!active) return
        active = false
        action()
    }
}

@Composable
private fun rememberPanelSession(): PanelSession {
    val session = remember { PanelSession() }
    DisposableEffect(session) { onDispose { session.active = false } }
    return session
}

/** One receipt per control; independent selections remain usable during writes. */
private class PanelWrites(initialIds: Map<String, String> = emptyMap()) {
    var ids by mutableStateOf(initialIds)

    fun isWriting(target: String, state: CatalogUiState) = ids[target]?.let(state.layoutWrites::get) == CatalogLayoutWriteStatus.Writing
    fun error(target: String, state: CatalogUiState) = (ids[target]?.let(state.layoutWrites::get) as? CatalogLayoutWriteStatus.Failed)?.message

    fun submit(target: String, onAction: (CatalogAction) -> Unit, action: (String) -> CatalogAction) {
        ids[target]?.let { onAction(CatalogAction.ForgetLayoutWrite(it)) }
        val id = UUID.randomUUID().toString()
        ids = ids + (target to id)
        onAction(action(id))
    }

    fun forget(target: String, onAction: (CatalogAction) -> Unit) {
        ids[target]?.let { onAction(CatalogAction.ForgetLayoutWrite(it)) }
        ids = ids - target
    }

    fun forgetAll(onAction: (CatalogAction) -> Unit) {
        ids.values.forEach { onAction(CatalogAction.ForgetLayoutWrite(it)) }
        ids = emptyMap()
    }

    companion object {
        val Saver = listSaver<PanelWrites, String>(
            save = { it.ids.flatMap { (target, id) -> listOf(target, id) } },
            restore = { values -> PanelWrites(values.chunked(2).associate { it[0] to it[1] }) },
        )
    }
}

private const val EDITOR_WRITE = "editor"

@Composable
private fun rememberPanelWrites(state: CatalogUiState, onAction: (CatalogAction) -> Unit): PanelWrites {
    val writes = rememberSaveable(saver = PanelWrites.Saver) { PanelWrites() }
    LaunchedEffect(state.layoutWrites) {
        val completed = writes.ids.filter { (target, id) -> target != EDITOR_WRITE && state.layoutWrites[id] == CatalogLayoutWriteStatus.Succeeded }
        if (completed.isNotEmpty()) {
            writes.ids = writes.ids - completed.keys
            completed.values.forEach { onAction(CatalogAction.ForgetLayoutWrite(it)) }
        }
    }
    return writes
}
