package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxIconButton
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxSearchField
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogFeedback
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportUiState
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun ToolManagerScreen(
    state: CatalogUiState,
    importState: ImportUiState,
    listState: LazyListState,
    onAction: (CatalogAction) -> Unit,
    onDestination: (MainDestination) -> Unit,
    onImport: () -> Unit,
    onInstallExamples: () -> Unit,
    onDismissImport: () -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    PrimaryScreen(MainDestination.Tools, onDestination, "工具", onImport) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            item("search") {
                ToolBoxSearchField(
                    value = state.query,
                    onValueChange = { onAction(CatalogAction.SetQuery(it)) },
                    placeholder = "搜索工具",
                )
            }
            if (importState.working || importState.message != null) {
                item("import-feedback") {
                    FeedbackSurface(
                        message = if (importState.working) "正在检查并安装工具…" else requireNotNull(importState.message),
                        success = importState.succeeded,
                        dismissible = !importState.working,
                        onDismiss = onDismissImport,
                    )
                }
            }
            state.feedback?.let { feedback ->
                item("catalog-feedback") {
                    FeedbackSurface(
                        message = feedback.message,
                        success = feedback is CatalogFeedback.Completed,
                        dismissible = true,
                        onDismiss = { onAction(CatalogAction.DismissFeedback) },
                    )
                }
            }
            when {
                !state.isLoaded -> item("loading") { CatalogStatusState("正在读取工具") }
                state.tools.isEmpty() -> item("empty") { EmptyCatalogState(onImport, onInstallExamples) }
                state.visibleTools.isEmpty() -> item("no-match") { CatalogStatusState("没有匹配的工具") }
                else -> itemsIndexed(
                    items = state.visibleTools,
                    key = { _, tool -> tool.toolId },
                    contentType = { _, _ -> "tool" },
                ) { index, tool ->
                    CatalogToolRow(
                        tool = tool,
                        isFirst = index == 0,
                        isLast = index == state.visibleTools.lastIndex,
                        onOpen = { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) },
                        onDetails = { onOpenDetails(tool.toolId) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ToolDetailScreen(
    toolId: String,
    state: CatalogUiState,
    onAction: (CatalogAction) -> Unit,
    onBack: () -> Unit,
    onPermissions: (String) -> Unit,
    onBackground: (String) -> Unit,
) {
    val tool = state.tools.firstOrNull { it.toolId == toolId }
    var menuVisible by rememberSaveable(toolId) { mutableStateOf(false) }
    DetailScreen(
        title = tool?.name ?: "工具详情",
        onBack = onBack,
        actions = {
            ToolBoxIconButton(ToolBoxIconKey.More, "更多操作", { menuVisible = true })
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ToolBoxThemeTokens.spacing.two),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            if (tool == null) {
                item { CatalogStatusState("该工具已不存在") }
            } else {
                item { ToolFactsCard(tool) }
                item {
                    ToolBoxPrimaryButton(
                        "打开工具",
                        { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) },
                        Modifier.fillMaxWidth(),
                    )
                }
                item { DetailAction("权限", "按工具独立开启或关闭能力") { onPermissions(tool.toolId) } }
                item { DetailAction("后台任务", "查看真实任务、结果和取消操作") { onBackground(tool.toolId) } }
                state.uninstallConfirmation?.takeIf { it.toolId == tool.toolId }?.let { confirmation ->
                    item {
                        ConfirmationSurface(
                            title = "删除 ${confirmation.toolName}？",
                            onCancel = { onAction(CatalogAction.CancelUninstall) },
                            onConfirm = { onAction(CatalogAction.ConfirmUninstall) },
                        )
                    }
                }
            }
        }
    }
    OverlayDialog(
        show = menuVisible,
        title = "工具操作",
        onDismissRequest = { menuVisible = false },
    ) {
        Column {
            tool?.let {
                ToolBoxSettingRow("打开", onClick = {
                    menuVisible = false
                    onAction(CatalogAction.RequestRuntimeLaunch(it.toolId))
                })
                ToolBoxSettingRow("权限", onClick = {
                    menuVisible = false
                    onPermissions(it.toolId)
                })
                ToolBoxSettingRow("后台任务", onClick = {
                    menuVisible = false
                    onBackground(it.toolId)
                })
                ToolBoxSettingRow("删除", summary = "删除工具及其数据和任务", onClick = {
                    menuVisible = false
                    onAction(CatalogAction.RequestUninstall(it.toolId))
                })
            }
        }
    }
}

@Composable
private fun CatalogToolRow(
    tool: CatalogTool,
    isFirst: Boolean,
    isLast: Boolean,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
) {
    val corner = ToolBoxThemeTokens.radii.denseSurface
    Column(
        Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = if (isFirst) corner else 0.dp,
                    topEnd = if (isFirst) corner else 0.dp,
                    bottomStart = if (isLast) corner else 0.dp,
                    bottomEnd = if (isLast) corner else 0.dp,
                ),
            )
            .background(ToolBoxThemeTokens.colors.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = ToolBoxThemeTokens.sizes.catalogRow)
                .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolGlyph(tool.name.firstOrNull()?.toString() ?: "T", ToolBoxThemeTokens.sizes.compactToolGlyph)
                Spacer(Modifier.width(ToolBoxThemeTokens.spacing.oneHalf))
                Column(Modifier.weight(1f)) {
                    AppText(tool.name, textStyle = ToolBoxThemeTokens.textStyles.title, weight = FontWeight.SemiBold)
                    AppText(
                        "${tool.versionName} · ${tool.bundleBytes.fileSizeLabel()}",
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        color = ToolBoxThemeTokens.colors.textSecondary,
                    )
                }
            }
            ToolBoxIconButton(ToolBoxIconKey.More, "管理${tool.name}", onDetails)
        }
        if (!isLast) {
            Box(
                Modifier.fillMaxWidth().padding(start = ToolBoxThemeTokens.spacing.three)
                    .height(ToolBoxThemeTokens.sizes.divider)
                    .background(ToolBoxThemeTokens.colors.divider),
            )
        }
    }
}

@Composable
private fun ToolFactsCard(tool: CatalogTool) {
    SurfaceCard {
        AppText(tool.name, textStyle = ToolBoxThemeTokens.textStyles.sectionTitle)
        AppText(tool.toolId, textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
        AppText("版本 ${tool.versionName} (${tool.versionCode})")
    }
}

@Composable
private fun DetailAction(title: String, summary: String, onClick: () -> Unit) {
    SurfaceCard(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        AppText(title, textStyle = ToolBoxThemeTokens.textStyles.title, weight = FontWeight.SemiBold)
        AppText(summary, textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
    }
}

@Composable
private fun FeedbackSurface(message: String, success: Boolean, dismissible: Boolean, onDismiss: () -> Unit) {
    SurfaceCard {
        AppText(
            message,
            color = if (success) ToolBoxThemeTokens.colors.success else ToolBoxThemeTokens.colors.textPrimary,
        )
        if (dismissible) ToolBoxSettingRow("关闭", onClick = onDismiss)
    }
}

@Composable
private fun ConfirmationSurface(title: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    SurfaceCard {
        AppText(title, textStyle = ToolBoxThemeTokens.textStyles.title, weight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one)) {
            ToolBoxPrimaryButton("取消", onCancel, Modifier.weight(1f))
            ToolBoxPrimaryButton("确认删除", onConfirm, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun EmptyCatalogState(onImport: () -> Unit, onInstallExamples: () -> Unit) {
    SurfaceCard(Modifier.testTag(HostTestTags.CatalogEmptyState)) {
        AppText("还没有工具", textStyle = ToolBoxThemeTokens.textStyles.sectionTitle)
        AppText(
            "导入 .tbx，或安装三个可直接使用的范例。",
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
            color = ToolBoxThemeTokens.colors.textSecondary,
        )
        ToolBoxPrimaryButton("导入 .tbx", onImport, Modifier.fillMaxWidth())
        ToolBoxPrimaryButton("安装三个范例", onInstallExamples, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun CatalogStatusState(message: String) {
    SurfaceCard { AppText(message, color = ToolBoxThemeTokens.colors.textSecondary) }
}

private val CatalogFeedback.message: String
    get() = when (this) {
        is CatalogFeedback.Completed -> message
        is CatalogFeedback.Failure -> message
    }

private fun Long.fileSizeLabel(): String = when {
    this < 1024L -> "$this B"
    this < 1024L * 1024L -> "${this / 1024L} KB"
    else -> "${this / (1024L * 1024L)} MB"
}
