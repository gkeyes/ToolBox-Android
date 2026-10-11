package io.toolbox.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/**
 * Confirmation / choice / form dialog. Design system v2 presents every such dialog as a bottom
 * sheet (28dp top corners, drag handle, drag-to-dismiss, predictive back), including over a
 * WebView: the sheet is its own window, so taps never reach the page behind it.
 */
@Composable
fun ToolBoxModalDialog(
    onDismissRequest: () -> Unit,
    title: String = "对话框",
    content: @Composable ColumnScope.() -> Unit,
) {
    ToolBoxActionSheet(title = title, onDismissRequest = onDismissRequest) {
        Column(Modifier.fillMaxWidth()) {
            ToolBoxActionSheetHeader { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.xs)) }
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = ToolBoxThemeTokens.spacing.xxs,
                        end = ToolBoxThemeTokens.spacing.xxs,
                        bottom = ToolBoxThemeTokens.spacing.sm,
                    ),
                content = content,
            )
        }
    }
}
