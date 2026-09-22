package io.toolbox.host.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxSecondaryButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/** Shared content only: callers retain the appropriate overlay or Android window host. */
@Composable
internal fun HostConfirmationContent(
    title: String,
    summary: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    cancelLabel: String = "取消",
    destructive: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        AppText(
            title,
            modifier = Modifier.semantics { heading() },
            textStyle = ToolBoxThemeTokens.textStyles.title.copy(
                fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one))
        AppText(summary, color = ToolBoxThemeTokens.colors.textSecondary)
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two))
        val largeText = LocalDensity.current.fontScale >= 1.3f
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 300.dp || largeText) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one)) {
                    ToolBoxSecondaryButton(cancelLabel, onCancel, Modifier.fillMaxWidth())
                    ToolBoxPrimaryButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), destructive = destructive)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one)) {
                    ToolBoxSecondaryButton(cancelLabel, onCancel, Modifier.weight(1f))
                    ToolBoxPrimaryButton(confirmLabel, onConfirm, Modifier.weight(1f), destructive = destructive)
                }
            }
        }
    }
}
