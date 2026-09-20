package io.toolbox.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator

/** The owning control announces its state; the visual spinner is not another focus target. */
@Composable
fun ToolBoxBusyIndicator(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.clearAndSetSemantics { }, size = 24.dp, strokeWidth = 2.dp)
}
