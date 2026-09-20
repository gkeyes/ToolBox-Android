package io.toolbox.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** A modal window: taps cannot reach the catalog behind the sheet. */
@Composable
fun ToolBoxActionSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val glass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass
    WindowBottomSheet(
        show = true,
        onDismissRequest = onDismissRequest,
        backgroundColor = if (glass) ToolBoxThemeTokens.materials.glassFallback else colors.surface,
        cornerRadius = if (glass) 28.dp else 24.dp,
        sheetMaxWidth = 560.dp,
        outsideMargin = DpSize.Zero,
        insideMargin = DpSize(20.dp, 12.dp),
        dragHandleColor = colors.textSecondary.copy(alpha = 0.32f),
        defaultWindowInsetsPadding = true,
    ) {
        // Miuix accounts for the top cutout and IME; the inner content also avoids
        // gesture/three-button navigation and landscape side cutouts.
        Box(
            modifier.fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .semantics { paneTitle = title },
        ) { content() }
    }
}
