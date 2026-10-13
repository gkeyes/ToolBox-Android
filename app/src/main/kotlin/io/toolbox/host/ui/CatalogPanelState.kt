package io.toolbox.host.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import io.toolbox.core.data.CatalogGroup
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogLayoutWriteStatus
import io.toolbox.host.catalog.CatalogUiState
import java.util.UUID

internal fun groupDraftHasChanges(
    name: String,
    members: List<String>,
    originalName: String,
    originalMembers: List<String>,
    style: String = "",
    originalStyle: String = "",
): Boolean =
    name.trim() != originalName.trim() || members != originalMembers || style != originalStyle

internal class GroupDraft(
    initialName: String,
    initialMembers: List<String>,
    initialQuery: String = "",
    initialConfirmingDelete: Boolean = false,
    initialColor: String = catalogGroupColor(null).key,
    initialIcon: String = catalogGroupGlyph(null).key,
) {
    val name = mutableStateOf(initialName)
    val members = mutableStateOf(initialMembers)
    val query = mutableStateOf(initialQuery)
    val confirmingDelete = mutableStateOf(initialConfirmingDelete)
    val color = mutableStateOf(initialColor)
    val icon = mutableStateOf(initialIcon)

    private val style: String get() = color.value + "/" + icon.value

    fun hasChanges(group: CatalogGroup?, initialMembers: List<String> = emptyList()) =
        groupDraftHasChanges(name.value, members.value, group?.name.orEmpty(), group?.members ?: initialMembers,
            style, group.styleKey())

    fun reset(initialMembers: List<String>) {
        name.value = ""
        members.value = initialMembers
        query.value = ""
        confirmingDelete.value = false
        color.value = catalogGroupColor(null).key
        icon.value = catalogGroupGlyph(null).key
    }

    companion object {
        val Saver = listSaver<GroupDraft, String>(
            save = {
                listOf(it.name.value, it.query.value, it.confirmingDelete.value.toString(), it.color.value, it.icon.value) +
                    it.members.value
            },
            restore = { GroupDraft(it[0], it.drop(5), it[1], it[2].toBoolean(), it[3], it[4]) },
        )
    }
}

/** Resolved style of a saved group (defaults for groups saved before styles existed). */
private fun CatalogGroup?.styleKey(): String =
    catalogGroupColor(this?.color).key + "/" + catalogGroupGlyph(this?.icon).key

@Composable
internal fun rememberGroupDraft(group: CatalogGroup?, initialMembers: List<String> = emptyList()): GroupDraft =
    rememberSaveable(saver = GroupDraft.Saver) {
        GroupDraft(
            group?.name.orEmpty(), group?.members ?: initialMembers,
            initialColor = catalogGroupColor(group?.color).key,
            initialIcon = catalogGroupGlyph(group?.icon).key,
        )
    }

internal class PanelSession {
    var active = true

    fun dismiss(action: () -> Unit) {
        if (!active) return
        active = false
        action()
    }
}

@Composable
internal fun rememberPanelSession(): PanelSession {
    val session = remember { PanelSession() }
    DisposableEffect(session) { onDispose { session.active = false } }
    return session
}

/** One receipt per control; independent selections remain usable during writes. */
internal class PanelWrites(initialIds: Map<String, String> = emptyMap()) {
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

internal const val EDITOR_WRITE = "editor"

@Composable
internal fun rememberPanelWrites(state: CatalogUiState, onAction: (CatalogAction) -> Unit): PanelWrites {
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
