package io.toolbox.host.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun BackupScreen(viewModel: BackupViewModel, onBack: () -> Unit, onReady: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(CreateBackupDocument()) { viewModel.exportSelected(it?.toString()) }
    val open = rememberLauncherForActivityResult(OpenBackupDocument()) { viewModel.restoreSelected(it?.toString()) }
    LaunchedEffect(Unit) { onReady() }
    LaunchedEffect(state) {
        if (state == BackupUiState.ExportReady) {
            viewModel.exportPickerStarted()
            try { save.launch("ToolBox-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.zip") }
            catch (_: Exception) { viewModel.pickerFailed() }
        }
    }
    val back = { if (viewModel.busy) viewModel.cancel() else onBack() }
    BackHandler(onBack = back)
    BackupContent(state, back, viewModel::requestExport, {
        viewModel.restorePickerStarted()
        try { open.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        catch (_: Exception) { viewModel.pickerFailed() }
    }, viewModel::prepareExport, viewModel::confirmRestore, viewModel::cancel)
}

@Composable
internal fun BackupContent(state: BackupUiState, onBack: () -> Unit, onExport: () -> Unit, onRestore: () -> Unit, onExportConsent: () -> Unit, onConfirmRestore: () -> Unit, onCancel: () -> Unit) {
    DetailScreen("备份与恢复", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("backup_page"), contentPadding = mergePadding(padding, PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { ToolBoxCard {
                AppText("备份内容", textStyle = ToolBoxThemeTokens.textStyles.title)
                AppText("外观、明暗、系统取色、透明度与后台设置；完整工具包、安装信息、ToolBox storage 配置及数据库数据、可解密的安全存储和任务历史。")
                AppText("不迁移硬件密钥、Android 系统授权、WebView 私有浏览器数据库或活动会话。外部文件访问授权与临时相机缓存需重新获取。", color = ToolBoxThemeTokens.colors.textSecondary)
            } }
            when (state) {
                BackupUiState.ExportConsent -> item { ToolBoxCard {
                    AppText("备份可能包含账号和令牌", textStyle = ToolBoxThemeTokens.textStyles.title)
                    AppText("ZIP 不加密，ToolBox 不会上传它。请只保存到可信的本地位置，不要公开分享。硬件密钥不导出；可解密数据将写入备份，并在恢复时重新加密。")
                    ToolBoxTextButton("同意并生成备份", onExportConsent, Modifier.fillMaxWidth().testTag("backup_export_consent"))
                    ToolBoxTextButton("取消", onCancel, Modifier.fillMaxWidth())
                } }
                is BackupUiState.Progress -> item { ToolBoxCard {
                    AppText("${state.percent}% · ${state.label}", modifier = Modifier.testTag("backup_progress_label"))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(ToolBoxThemeTokens.colors.textSecondary.copy(alpha = .15f)).progressSemantics(state.percent / 100f).testTag("backup_progress")) {
                        Box(Modifier.fillMaxWidth(state.percent / 100f).fillMaxHeight().background(ToolBoxThemeTokens.colors.primary))
                    }
                    ToolBoxTextButton(if (state.cancelling) "正在安全取消" else "取消操作", onCancel, Modifier.fillMaxWidth(), enabled = !state.cancelling)
                } }
                is BackupUiState.Picking -> item { ToolBoxCard {
                    AppText(if (state.export) "请选择本地保存位置。" else "请选择一份本地备份 ZIP。")
                    ToolBoxTextButton("取消", onCancel)
                } }
                is BackupUiState.ConfirmRestore -> {
                    item { ToolBoxCard {
                        AppText("恢复前确认", textStyle = ToolBoxThemeTokens.textStyles.title)
                        AppText("将覆盖宿主设置；恢复 ${state.preview.recoverable.size} 个工具，其中 ${state.preview.conflicts} 个与本机冲突。其他工具保留。", modifier = Modifier.testTag("backup_conflicts"))
                        AppText("当前有 ${state.preview.runtimeIds.size} 个运行环境和 ${state.preview.activeTaskIds.size} 个待执行或运行任务。恢复将停止全部本机工具会话与任务，完成或取消后均需手动重新打开。")
                        AppText("下方版本覆盖只确认一次；安装时仍执行原有校验。备份中的授权不会绕过当前权限模型。")
                    } }
                    state.preview.tools.forEach { tool -> item("tool-${tool.id}") { ToolBoxCard {
                        AppText(tool.name, textStyle = ToolBoxThemeTokens.textStyles.title)
                        AppText("${tool.current ?: "未安装"} → ${tool.incoming}")
                        tool.skipped?.let { AppText("跳过：$it", color = ToolBoxThemeTokens.colors.danger) }
                    } } }
                    state.preview.warnings.forEachIndexed { i, warning -> item("warning-$i") { AppText(warning, color = ToolBoxThemeTokens.colors.textSecondary) } }
                    item { ToolBoxCard {
                        ToolBoxTextButton("确认覆盖并恢复", onConfirmRestore, Modifier.fillMaxWidth().testTag("backup_confirm_restore"))
                        ToolBoxTextButton("取消恢复", onCancel, Modifier.fillMaxWidth())
                    } }
                }
                is BackupUiState.Result -> item { ToolBoxCard {
                    AppText(state.title, textStyle = ToolBoxThemeTokens.textStyles.title, modifier = Modifier.testTag("backup_result"))
                    AppText(state.message, color = if (state.failed) ToolBoxThemeTokens.colors.danger else ToolBoxThemeTokens.colors.textPrimary)
                    state.warnings.forEach { AppText(it, color = ToolBoxThemeTokens.colors.textSecondary) }
                } }
                else -> Unit
            }
            if (state is BackupUiState.Idle || state is BackupUiState.Result) item { ToolBoxGroupedSurface {
                ToolBoxSettingRow("导出备份", summary = "生成、复核，再选择本地位置", icon = ToolBoxIconKey.Share, onClick = onExport, modifier = Modifier.testTag("backup_export"))
                ToolBoxGroupDivider()
                ToolBoxSettingRow("从文件恢复", summary = "先检查，再确认覆盖内容", icon = ToolBoxIconKey.Folder, onClick = onRestore, modifier = Modifier.testTag("backup_restore"))
            } }
            item { AppText("先在私有临时文件中完整生成并原子发布，再写入所选位置。系统文件提供器不统一保证原子替换；写入失败会尝试删除不完整文件，强制结束应用仍可能留下待删除文件。", textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary) }
        }
    }
}
