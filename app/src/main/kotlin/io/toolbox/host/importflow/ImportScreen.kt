package io.toolbox.host.importflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.*
import io.toolbox.tool.packagekit.lifecycle.PackageImportPhase

@Composable
internal fun ImportScreen(
    viewModel: ImportViewModel,
    onPickPackage: () -> Unit,
    onBack: () -> Unit,
    onReady: () -> Unit,
    onOpenTool: ((String) -> Unit)? = null,
    installedToolReady: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { onReady() }
    DetailScreen(title = "导入小工具", onBack = onBack) { chromePadding ->
        LazyColumn(
            modifier = Modifier.widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
                .fillMaxSize().align(Alignment.TopCenter),
            contentPadding = mergePadding(chromePadding, PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("select-package") {
                ToolBoxCard(modifier = Modifier.fillMaxWidth(), onClick = if (state.working || state.confirmation != null) null else onPickPackage) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ToolBoxIcon(ToolBoxIconKey.Folder, null, tint = ToolBoxThemeTokens.colors.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AppText(if (state.working) "正在导入…" else "选择 .tbx 文件", textStyle = ToolBoxThemeTokens.textStyles.title)
                            AppText("从设备文件中选择小工具包。", color = ToolBoxThemeTokens.colors.textSecondary,
                                textStyle = ToolBoxThemeTokens.textStyles.metadata)
                        }
                    }
                }
            }
            if (state.working || state.message != null) {
                item("import-result") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(when {
                            state.working -> "正在导入"
                            state.succeeded -> "导入完成"
                            state.outcome == ImportOutcome.Cancelled -> "已取消导入"
                            else -> "导入失败"
                        })
                        FeedbackSurface(
                            message = if (state.working) state.progressMessage else state.message.orEmpty(),
                            tone = state.feedbackTone,
                            dismissible = false, onDismiss = {},
                            onCancel = viewModel::cancelActiveImport.takeIf {
                                state.working && state.importPhase == PackageImportPhase.IMPORTING
                            },
                        )
                    }
                }
                if (!state.working && state.confirmation == null) item("result-actions") {
                    val installedToolId = state.installedToolId
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.succeeded && installedToolId != null && onOpenTool != null) {
                            ToolBoxPrimaryButton(
                                label = if (installedToolReady) "打开工具" else "正在更新目录…",
                                onClick = { onOpenTool(installedToolId) },
                                modifier = Modifier.fillMaxWidth().testTag("import_open_tool"),
                                enabled = installedToolReady,
                            )
                            ToolBoxTextButton("继续导入", onPickPackage, Modifier.fillMaxWidth(), outlined = false)
                        } else {
                            ToolBoxTextButton(
                                if (state.succeeded) "完成" else "重新选择文件",
                                if (state.succeeded) onBack else onPickPackage,
                                Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        ImportReplacementDialog(state, viewModel::confirmVersionReplacement, viewModel::cancelVersionReplacement)
    }
}
