package io.toolbox.host.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.toolbox.core.data.CatalogSort
import io.toolbox.core.ui.component.ToolBoxActionSheet
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconButton
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogUiState

@Composable
internal fun CatalogListHeader(state: CatalogUiState, onAction: (CatalogAction) -> Unit) {
    var choosingSort by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val title = if (!state.isLoaded) "全部工具" else if (state.isSearching) {
        "搜索结果 · ${state.visibleTools.size}"
    } else "全部工具 · ${state.tools.size}"
    val openSort = { focus.clearFocus(); choosingSort = true }
    val largeText = LocalDensity.current.fontScale >= 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("catalog_list_header")) {
        if (maxWidth < 300.dp || largeText) {
            Column(Modifier.fillMaxWidth()) {
                AppText(title, Modifier.semantics { heading() }, textStyle = ToolBoxThemeTokens.textStyles.title)
                ToolBoxTextButton("排序 · ${state.layout.sort.label}", openSort, outlined = false)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppText(title, Modifier.weight(1f).semantics { heading() }, textStyle = ToolBoxThemeTokens.textStyles.title)
                ToolBoxTextButton("排序 · ${state.layout.sort.label}", openSort, outlined = false)
            }
        }
    }
    if (choosingSort) ToolBoxActionSheet(title = "排序", onDismissRequest = { choosingSort = false }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppText("排序", Modifier.weight(1f).semantics { heading() }, textStyle = ToolBoxThemeTokens.textStyles.sectionTitle)
                ToolBoxIconButton(ToolBoxIconKey.Close, "关闭排序", { choosingSort = false })
            }
            CatalogSort.entries.forEach { sort ->
                val selected = state.layout.sort == sort
                Row(
                    Modifier.fillMaxWidth().heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = {
                            choosingSort = false
                            if (!selected) onAction(CatalogAction.SetSort(sort))
                        }).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(sort.label, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    if (selected) ToolBoxIcon(ToolBoxIconKey.Check, null, tint = ToolBoxThemeTokens.colors.primary)
                }
            }
        }
    }
}
