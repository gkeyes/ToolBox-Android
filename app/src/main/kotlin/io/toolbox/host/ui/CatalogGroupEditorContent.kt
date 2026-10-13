package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.toolbox.core.data.CatalogGroup
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogLayoutWriteStatus
import io.toolbox.host.catalog.CatalogUiState
import java.util.UUID

@Composable
internal fun GroupEditorContent(
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
    var colorKey by draft.color
    var iconKey by draft.icon
    var selectedOnly by rememberSaveable(creating, group?.id) { mutableStateOf(false) }
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
    // Only the owning session discards the draft/receipt after an explicit decision.
    val cancel = { finishInput(); if (!writing) onCancel() }
    val close = { finishInput(); if (!writing) onClose() }
    val back = { if (!writing) { if (confirmingDelete) confirmingDelete = false else cancel() } }
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
            item("heading") { PanelHeader("删除分组", close, onBack = back, enabled = !writing) }
            item("message") {
                ToolBoxText("删除“${group.name}”？组内工具和收藏会保留。", style = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textPrimary))
            }
            error?.let { item("error") { PanelError(it, Modifier.testTag("catalog_group_error")) } }
            item("actions") {
                PanelEditorActions(
                    cancel = { confirmingDelete = false }, cancelEnabled = !writing,
                    confirmLabel = if (writing) "正在删除…" else "删除分组",
                    confirmEnabled = !writing, destructive = true,
                    confirmTag = "catalog_group_delete_confirm",
                    confirm = {
                        forget()
                        val id = UUID.randomUUID().toString()
                        writes.ids = writes.ids + (EDITOR_WRITE to id)
                        onAction(CatalogAction.DeleteGroup(group.id, id))
                    },
                )
            }
        }
        return
    }
    val memberIds = remember(members) { members.toSet() }
    val selectedCount = state.tools.count { it.toolId in memberIds }
    val tools = matchingTools(state.tools, query).let { if (selectedOnly) it.filter { tool -> tool.toolId in memberIds } else it }
    Column(Modifier.fillMaxWidth().testTag("catalog_group_editor")) {
        LazyColumn(Modifier.weight(1f, fill = false).testTag("catalog_group_members"), state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("heading") { PanelHeader(if (creating) "新建分组" else "编辑分组", close, onBack = back, enabled = !writing) }
            item("hero") {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CatalogGroupStyleIcon(colorKey, iconKey, 84.dp, 44.dp, Modifier.clearAndSetSemantics { })
                    ToolBoxText(name.ifBlank { if (creating) "新建分组" else group?.name.orEmpty() }, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = ToolBoxThemeTokens.textStyles.detailTitle.copy(color = ToolBoxThemeTokens.colors.textPrimary))
                }
            }
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
                            .clip(toolBoxShape(ToolBoxThemeTokens.radii.control))
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
            item("style") {
                GroupStylePicker(
                    colorKey = colorKey,
                    iconKey = iconKey,
                    enabled = !writing,
                    onColor = { colorKey = it },
                    onIcon = { iconKey = it },
                )
            }
            item("search") {
                ToolBoxSearchField(query, { query = it }, "搜索工具", Modifier.testTag("catalog_member_search"))
            }
            item("selection-summary") {
                Column(Modifier.fillMaxWidth()) {
                    AppText("已选 $selectedCount 个 / 共 ${state.tools.size} 个",
                        textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
                    ToolBoxTextButton(if (selectedOnly) "显示全部工具" else "仅看已选", { selectedOnly = !selectedOnly },
                        Modifier.testTag("catalog_selected_only").semantics {
                            stateDescription = if (selectedOnly) "仅显示已选工具" else "显示全部工具"
                        }, enabled = !writing, outlined = false)
                }
            }
            if (tools.isEmpty()) item("empty") {
                PanelEmpty(when {
                    state.tools.isEmpty() -> "暂无工具，可以先保存空分组。"
                    selectedOnly -> "没有匹配的已选工具"
                    else -> "没有匹配的工具"
                })
                if (query.isNotBlank()) ToolBoxTextButton("清除搜索", { query = "" }, outlined = false)
            }
            items(tools, key = { "tool:${it.toolId}" }) { tool ->
                ToolCheckRow(tool, tool.toolId in memberIds, !writing, "group-tool:${tool.toolId}",
                    Modifier.animateItem(fadeInSpec = ToolBoxMotion.effects(), placementSpec = ToolBoxMotion.ItemPlacement, fadeOutSpec = ToolBoxMotion.effects())) { selected ->
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
        PanelEditorActions(
            cancel = cancel, cancelEnabled = !writing,
            confirmLabel = if (writing) "正在保存…" else "保存",
            confirmEnabled = !writing && name.isNotBlank(), confirmTag = "catalog_group_save",
            confirm = {
                finishInput()
                forget()
                val id = UUID.randomUUID().toString()
                writes.ids = writes.ids + (EDITOR_WRITE to id)
                val installed = state.tools.mapTo(HashSet()) { it.toolId }
                onAction(CatalogAction.SaveGroup(if (creating) null else group?.id, name, members.filter(installed::contains), id,
                    color = colorKey, icon = iconKey))
            },
        )
    }
}

/** Colour swatches and an icon grid, as in the GroupEditor board. Selection updates the draft only. */
@Composable
private fun GroupStylePicker(
    colorKey: String,
    iconKey: String,
    enabled: Boolean,
    onColor: (String) -> Unit,
    onIcon: (String) -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val selectedColor = catalogGroupColor(colorKey)
    val selectedGlyph = catalogGroupGlyph(iconKey)
    val haptics = rememberToolBoxHaptics()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolBoxText("图标与颜色", style = ToolBoxThemeTokens.textStyles.label.copy(color = colors.textSecondary))
        Column(
            Modifier.fillMaxWidth()
                .clip(toolBoxShape(22.dp))
                .background(colors.surfaceMuted)
                .padding(horizontal = 10.dp, vertical = 12.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.46f },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().selectableGroup().testTag("catalog_group_colors")) {
                CatalogGroupColors.forEach { option ->
                    val selected = option.key == selectedColor.key
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp)
                            .selectable(selected, enabled = enabled, role = Role.RadioButton) {
                                haptics.tick()
                                onColor(option.key)
                            }
                            .semantics { contentDescription = option.label }
                            .testTag("catalog_group_color:" + option.key),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(36.dp)
                                .then(if (selected) Modifier.border(2.5.dp, option.bottom, CircleShape) else Modifier)
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(Brush.verticalGradient(listOf(option.top, option.bottom))),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
            CatalogGroupGlyphs.chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    row.forEach { glyph ->
                        val selected = glyph.key == selectedGlyph.key
                        Box(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .selectable(selected, enabled = enabled, role = Role.RadioButton) {
                                    haptics.tick()
                                    onIcon(glyph.key)
                                }
                                .semantics { contentDescription = glyph.label }
                                .testTag("catalog_group_icon:" + glyph.key),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(44.dp)
                                    .clip(toolBoxShape(14.dp))
                                    .background(
                                        if (selected) Brush.verticalGradient(listOf(selectedColor.top, selectedColor.bottom))
                                        else SolidColor(colors.surface),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                ToolBoxIcon(glyph.icon, null, Modifier.size(22.dp),
                                    tint = if (selected) Color.White else colors.textPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}
