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
 * Choice / form dialog, presented as an iOS 27 floating glass sheet (inset from the screen edges,
 * every corner rounded, grabber, drag-to-dismiss, predictive back), including over a WebView: the
 * sheet is its own window, so taps never reach the page behind it. Short yes/no confirmations can
 * use the centered [ToolBoxAlertDialog] instead.
 */
@Composable
fun ToolBoxModalDialog(
    onDismissRequest: () -> Unit,
    title: String = "对话框",
    content: @Composable ColumnScope.() -> Unit,
) {
    // Liquid glass over a copy of the page. Over a live page (browser, running tool) the copy is a
    // frozen frame of it, as iOS freezes the frame behind an alert.
    ToolBoxActionSheet(title = title, onDismissRequest = onDismissRequest) {
        Column(Modifier.fillMaxWidth()) {
            // Grabber, then a 12dp breath before the first line of content, as on iOS sheets.
            ToolBoxActionSheetHeader { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.sm)) }
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = ToolBoxThemeTokens.spacing.micro,
                        end = ToolBoxThemeTokens.spacing.micro,
                        top = ToolBoxThemeTokens.spacing.micro,
                        bottom = ToolBoxThemeTokens.spacing.xs,
                    ),
                content = content,
            )
        }
    }
}
