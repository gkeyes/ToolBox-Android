package io.toolbox.host.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    val stage = when (state) {
        BackupUiState.Idle -> "idle"
        BackupUiState.ExportConsent -> "export-consent"
        BackupUiState.ExportReady -> "export-ready"
        is BackupUiState.Picking -> "picking"
        is BackupUiState.Progress -> "progress"
        is BackupUiState.ConfirmRestore -> "confirm-restore"
        is BackupUiState.Result -> "result"
    }
    // Each stage keeps its own scroll and disclosure state; safety notices never live in the disclosure.
    val listState = key(stage) { rememberLazyListState() }
    var detailsExpanded by rememberSaveable(stage) { mutableStateOf(false) }
    DetailScreen("备份与恢复", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("backup_page"), state = listState, contentPadding = mergePadding(padding, PaddingValues(16.dp))) {
            if (state == BackupUiState.Idle) {
                backupActions(onExport, onRestore)
                backupSection("overview") { ToolBoxCard {
                    AppText("工具、设置与数据", textStyle = ToolBoxThemeTokens.textStyles.title)
                    AppText("备份保存在你选择的位置；恢复前会先列出将覆盖和跳过的工具。", color = ToolBoxThemeTokens.colors.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    AppText("可能包含账号和令牌，ZIP 未加密，请勿公开分享。", color = ToolBoxThemeTokens.colors.danger)
                } }
            }
            when (state) {
                BackupUiState.ExportConsent -> backupSection("consent") { ToolBoxCard {
                    AppText("备份可能包含账号和令牌", textStyle = ToolBoxThemeTokens.textStyles.title)
                    AppText("ZIP 不加密，ToolBox 不会上传它。请只保存到可信的本地位置，不要公开分享。硬件密钥不导出；可解密数据将写入备份，并在恢复时重新加密。")
                    Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two))
                    ToolBoxPrimaryButton("同意并生成备份", onExportConsent, Modifier.fillMaxWidth().testTag("backup_export_consent"))
                    Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one))
                    ToolBoxSecondaryButton("取消", onCancel, Modifier.fillMaxWidth())
                } }
                is BackupUiState.Progress -> backupSection("progress") { ToolBoxCard {
                    AppText("${state.percent}% · ${state.label}", modifier = Modifier.testTag("backup_progress_label"))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(ToolBoxThemeTokens.colors.textSecondary.copy(alpha = .15f)).progressSemantics(state.percent / 100f).testTag("backup_progress")) {
                        Box(Modifier.fillMaxWidth(state.percent / 100f).fillMaxHeight().background(ToolBoxThemeTokens.colors.primary))
                    }
                    ToolBoxTextButton(if (state.cancelling) "正在安全取消" else "取消操作", onCancel, Modifier.fillMaxWidth(), enabled = !state.cancelling)
                } }
                is BackupUiState.Picking -> backupSection("picking") { ToolBoxCard {
                    AppText(if (state.export) "请选择本地保存位置。" else "请选择一份本地备份 ZIP。")
                    ToolBoxTextButton("取消", onCancel)
                } }
                is BackupUiState.ConfirmRestore -> {
                    val counts = restorePlanCounts(state.preview.tools)
                    backupSection("confirm-restore") { ToolBoxCard {
                        AppText("恢复前确认", textStyle = ToolBoxThemeTokens.textStyles.title)
                        AppText("可恢复 ${counts.recoverable} 个 · 覆盖 ${counts.overwritten} 个 · 跳过 ${counts.skipped} 个",
                            textStyle = ToolBoxThemeTokens.textStyles.title, modifier = Modifier.testTag("backup_conflicts"))
                        Spacer(Modifier.height(8.dp))
                        AppText("将覆盖宿主设置及下方标记的工具；其他工具保留。", color = ToolBoxThemeTokens.colors.danger)
                        AppText("当前有 ${state.preview.runtimeIds.size} 个运行环境和 ${state.preview.activeTaskIds.size} 个待执行或运行任务。恢复将停止全部本机工具会话与任务，完成或取消后均需手动重新打开。")
                        AppText("下方版本覆盖只确认一次；安装时仍执行原有校验。备份中的授权不会绕过当前权限模型。",
                            color = ToolBoxThemeTokens.colors.textSecondary)
                    } }
                    backupSection("tools-heading") { SectionHeader("工具变更清单") }
                    itemsIndexed(state.preview.tools, key = { _, tool -> "tool-${tool.id}" }) { index, tool ->
                        RestorePreviewRow(tool, first = index == 0, last = index == state.preview.tools.lastIndex)
                    }
                    state.preview.warnings.forEachIndexed { i, warning ->
                        backupSection("warning-$i") { AppText(warning, color = ToolBoxThemeTokens.colors.textSecondary) }
                    }
                    backupSection("confirm-actions") { ToolBoxCard {
                        ToolBoxDestructiveButton("确认覆盖并恢复", onConfirmRestore, Modifier.fillMaxWidth().testTag("backup_confirm_restore"))
                        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one))
                        ToolBoxSecondaryButton("取消恢复", onCancel, Modifier.fillMaxWidth())
                    } }
                }
                is BackupUiState.Result -> backupSection("result") { ToolBoxCard(
                    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    AppText(state.title, textStyle = ToolBoxThemeTokens.textStyles.title, modifier = Modifier.testTag("backup_result"))
                    AppText(state.message, color = if (state.failed) ToolBoxThemeTokens.colors.danger else ToolBoxThemeTokens.colors.textPrimary)
                    state.warnings.forEach { AppText(it, color = ToolBoxThemeTokens.colors.textSecondary) }
                } }
                else -> Unit
            }
            if (state is BackupUiState.Result) backupActions(onExport, onRestore)
            backupSection("technical-details") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolBoxTextButton(
                        if (detailsExpanded) "收起备份范围与技术说明" else "备份范围与技术说明",
                        { detailsExpanded = !detailsExpanded },
                        Modifier.fillMaxWidth().testTag("backup_details_toggle").semantics {
                            stateDescription = if (detailsExpanded) "已展开" else "已收起"
                        }, outlined = false,
                    )
                    if (detailsExpanded) {
                        AppText("包含外观、明暗、系统取色、透明度与后台设置；完整工具包、安装信息、ToolBox storage 配置及数据库数据、可解密的安全存储和任务历史。",
                            textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
                        AppText("不迁移硬件密钥、Android 系统授权、WebView 私有浏览器数据库或活动会话。外部文件访问授权与临时相机缓存需重新获取。",
                            textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
                        AppText("先在私有临时文件中完整生成并原子发布，再写入所选位置。系统文件提供器不统一保证原子替换；写入失败会尝试删除不完整文件，强制结束应用仍可能留下待删除文件。",
                            textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.backupActions(onExport: () -> Unit, onRestore: () -> Unit) {
    backupSection("actions") { ToolBoxGroupedSurface {
        ToolBoxSettingRow("导出备份", summary = "保存为本地 ZIP 文件", icon = ToolBoxIconKey.Share, onClick = onExport, modifier = Modifier.testTag("backup_export"))
        ToolBoxGroupDivider()
        ToolBoxSettingRow("从文件恢复", summary = "先检查文件，再确认覆盖", icon = ToolBoxIconKey.Folder, onClick = onRestore, modifier = Modifier.testTag("backup_restore"))
    } }
}

private fun LazyListScope.backupSection(key: String, content: @Composable () -> Unit) {
    item(key) {
        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) { content() }
    }
}

@Composable
private fun RestorePreviewRow(tool: RestoreToolPlan, first: Boolean, last: Boolean) {
    val radius = ToolBoxThemeTokens.radii.denseSurface
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp, topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp, bottomEnd = if (last) radius else 0.dp,
    )
    Column(Modifier.fillMaxWidth().padding(bottom = if (last) 16.dp else 0.dp)
        .clip(shape).background(ToolBoxThemeTokens.colors.surface)) {
        Column(Modifier.fillMaxWidth().heightIn(min = ToolBoxThemeTokens.sizes.denseRow)
            .padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(tool.name, textStyle = ToolBoxThemeTokens.textStyles.title)
            if (tool.skipped != null) {
                AppText("跳过 · ${tool.skipped}", color = ToolBoxThemeTokens.colors.danger)
            } else {
                AppText(if (tool.current == null) "新增安装" else "将覆盖本机版本",
                    color = if (tool.current == null) ToolBoxThemeTokens.colors.primary else ToolBoxThemeTokens.colors.danger,
                    textStyle = ToolBoxThemeTokens.textStyles.metadata)
            }
            AppText("${tool.current ?: "未安装"} → ${tool.incoming}", textStyle = ToolBoxThemeTokens.textStyles.metadata,
                color = ToolBoxThemeTokens.colors.textSecondary)
        }
        if (!last) ToolBoxGroupDivider(startPadding = 12.dp)
    }
}

internal data class RestorePlanCounts(val recoverable: Int, val overwritten: Int, val skipped: Int)

/** Skipped conflicts must never be counted as planned overwrites. */
internal fun restorePlanCounts(tools: List<RestoreToolPlan>): RestorePlanCounts = RestorePlanCounts(
    recoverable = tools.count { it.skipped == null },
    overwritten = tools.count { it.skipped == null && it.current != null },
    skipped = tools.count { it.skipped != null },
)
