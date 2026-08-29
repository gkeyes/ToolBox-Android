package io.toolbox.core.ui.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
fun ToolBoxText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ToolBoxThemeTokens.textStyles.body,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    require(style.lineHeight != TextUnit.Unspecified) {
        "ToolBoxText styles must declare an explicit lineHeight"
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
    )
}
