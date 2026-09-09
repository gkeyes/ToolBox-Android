package io.toolbox.host.importflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.*

@Composable
internal fun ImportScreen(
    viewModel: ImportViewModel,
    onPickPackage: () -> Unit,
    onBack: () -> Unit,
    onReady: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { onReady() }
    DetailScreen(title = "导入小工具", onBack = onBack) { chromePadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = mergePadding(chromePadding, PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { AppText("选择一个 .tbx 包，导入进度和结果会显示在这里。", color = ToolBoxThemeTokens.colors.textSecondary,
                textStyle = ToolBoxThemeTokens.textStyles.metadata) }
            item { SectionHeader("选择文件") }
            item {
                ToolBoxCard(modifier = Modifier.fillMaxWidth(), onClick = if (state.working || state.confirmation != null) null else onPickPackage) {
                    ToolBoxIcon(ToolBoxIconKey.Folder, null, tint = ToolBoxThemeTokens.colors.primary)
                    Spacer(Modifier.height(12.dp))
                    AppText(if (state.working) "正在导入…" else "选择 .tbx 文件", textStyle = ToolBoxThemeTokens.textStyles.title)
                    Spacer(Modifier.height(8.dp))
                    AppText("从设备文件中选择小工具包。", color = ToolBoxThemeTokens.colors.textSecondary, textStyle = ToolBoxThemeTokens.textStyles.metadata)
                }
            }
            if (state.working || state.message != null) {
                item { SectionHeader("导入结果") }
                item {
                    FeedbackSurface(
                        message = if (state.working) "正在检查并安装工具…" else state.message.orEmpty(),
                        tone = if (state.working) FeedbackTone.Progress else if (state.succeeded) FeedbackTone.Success else FeedbackTone.Error,
                        dismissible = false, onDismiss = {},
                    )
                }
                if (!state.working) item {
                    ToolBoxTextButton(if (state.succeeded) "查看工具" else "重新选择文件", if (state.succeeded) onBack else onPickPackage)
                }
            }
        }
        ImportReplacementDialog(state, viewModel::confirmVersionReplacement, viewModel::cancelVersionReplacement)
    }
}
