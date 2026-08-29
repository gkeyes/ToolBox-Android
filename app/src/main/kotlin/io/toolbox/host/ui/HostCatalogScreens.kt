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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.SignatureState
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxSearchField
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogFeedback
import io.toolbox.host.catalog.CatalogSort
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState

@Composable
fun HomeScreen(
    state: CatalogUiState,
    onAction: (CatalogAction) -> Unit,
    onDestination: (MainDestination) -> Unit,
    onImport: () -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    PrimaryScreen(MainDestination.Home, onDestination, "ToolBox", onImport) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(HostTestTags.CatalogList),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            if (state.tools.isNotEmpty()) {
                item(key = "summary") {
                    CompactCatalogSummary(state.tools.size)
                }
            }
            state.feedback?.let { feedback ->
                item(key = "feedback") {
                    CatalogFeedbackCard(feedback, onAction)
                }
            }
            when {
                !state.isLoaded -> item(key = "loading") {
                    CatalogStatusState("正在读取已安装工具")
                }
                state.visibleTools.isEmpty() && state.tools.isEmpty() ->
                    item(key = "empty") { EmptyCatalogState(onImport) }
                else -> {
                    val pinnedTools = state.tools
                        .filter { it.pinnedOrder != null }
                        .sortedBy(CatalogTool::pinnedOrder)
                    val recentTools = state.tools
                        .filter { it.pinnedOrder == null && it.lastOpenedAt != null }
                        .sortedByDescending(CatalogTool::lastOpenedAt)
                    val installedTools = state.tools.filter { it.pinnedOrder == null && it.lastOpenedAt == null }

                    if (pinnedTools.isNotEmpty()) {
                        item(key = "pinned-header") { SectionHeader("已固定", "") }
                        catalogRows(pinnedTools, onAction, onOpenDetails)
                    }
                    if (recentTools.isNotEmpty()) {
                        item(key = "recent-header") { SectionHeader("最近使用", "") }
                        catalogRows(recentTools, onAction, onOpenDetails)
                    }
                    if (installedTools.isNotEmpty()) {
                        item(key = "installed-header") { SectionHeader("已安装工具", "") }
                        catalogRows(installedTools, onAction, onOpenDetails)
                    }
                }
            }
        }
    }
}

@Composable
fun ToolManagerScreen(
    state: CatalogUiState,
    onAction: (CatalogAction) -> Unit,
    onDestination: (MainDestination) -> Unit,
    onImport: () -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    CatalogScreen(state, onAction, onDestination, onImport, onOpenDetails)
}

@Composable
private fun CatalogScreen(
    state: CatalogUiState,
    onAction: (CatalogAction) -> Unit,
    onDestination: (MainDestination) -> Unit,
    onImport: () -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    PrimaryScreen(MainDestination.Tools, onDestination, "工具管理", onImport) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(HostTestTags.CatalogList),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            item(key = "search") { CatalogSearchField(state.query) { onAction(CatalogAction.SetQuery(it)) } }
            item(key = "filters") { CatalogFilters(state, onAction) }
            state.feedback?.let { feedback ->
                item(key = "feedback") { CatalogFeedbackCard(feedback, onAction) }
            }
            when {
                !state.isLoaded -> item(key = "loading") { CatalogStatusState("正在读取已安装工具") }
                state.visibleTools.isEmpty() && state.tools.isEmpty() ->
                    item(key = "empty") { EmptyCatalogState(onImport) }
                state.visibleTools.isEmpty() -> item(key = "no-match") {
                    CatalogStatusState("没有符合当前搜索或分类的工具。")
                }
                else -> catalogRows(state.visibleTools, onAction, onOpenDetails)
            }
        }
    }
}

private fun LazyListScope.catalogRows(
    tools: List<CatalogTool>,
    onAction: (CatalogAction) -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    items(tools, key = CatalogTool::toolId) { tool ->
        CatalogToolRow(
            tool = tool,
            onOpen = { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) },
            onDetails = { onOpenDetails(tool.toolId) },
        )
    }
}

@Composable
private fun CompactCatalogSummary(toolCount: Int) {
    SurfaceCard(contentPadding = ToolBoxThemeTokens.spacing.two) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.half)) {
                AppText(
                    "我的工具箱",
                    textStyle = ToolBoxThemeTokens.textStyles.metadata,
                    color = ToolBoxThemeTokens.colors.textSecondary,
                )
                AppText("$toolCount 个已安装工具", textStyle = ToolBoxThemeTokens.textStyles.sectionTitle)
            }
        }
    }
}

@Composable
private fun CatalogSearchField(value: String, onValueChange: (String) -> Unit) {
    ToolBoxSearchField(
        value = value,
        onValueChange = onValueChange,
        placeholder = "搜索名称、ID 或分类",
        contentDescription = "搜索已安装工具",
    )
}

@Composable
private fun CatalogFilters(state: CatalogUiState, onAction: (CatalogAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one)) {
        FilterButton(state.categoryFilter ?: "全部分类", state.categoryFilter != null, Modifier.weight(1f)) {
            val options = listOf(null) + state.categories
            val current = options.indexOf(state.categoryFilter).coerceAtLeast(0)
            onAction(CatalogAction.SetCategoryFilter(options[(current + 1) % options.size]))
        }
        FilterButton("排序：${state.sort.label}", false, Modifier.weight(1f)) {
            val sorts = CatalogSort.entries
            onAction(CatalogAction.SetSort(sorts[(sorts.indexOf(state.sort) + 1) % sorts.size]))
        }
    }
}

@Composable
private fun FilterButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val background = if (selected) ToolBoxThemeTokens.colors.softPrimary else ToolBoxThemeTokens.colors.surface
    val foreground = if (selected) ToolBoxThemeTokens.colors.primary else ToolBoxThemeTokens.colors.textPrimary
    Box(
        modifier = modifier
            .heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
            .clip(RoundedCornerShape(ToolBoxThemeTokens.radii.denseSurface))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            label,
            textStyle = ToolBoxThemeTokens.textStyles.label,
            color = foreground,
            weight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CatalogToolRow(tool: CatalogTool, onOpen: () -> Unit, onDetails: () -> Unit) {
    SurfaceCard(
        modifier = Modifier
            .heightIn(min = ToolBoxThemeTokens.sizes.catalogRow)
            .testTag(HostTestTags.ToolCardPrefix + tool.toolId),
        contentPadding = ToolBoxThemeTokens.spacing.oneHalf,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
                    .clip(RoundedCornerShape(ToolBoxThemeTokens.radii.denseSurface))
                    .clickable(role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = "打开${tool.name}，${tool.signatureState.label}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolGlyph(
                    tool.name.firstOrNull()?.toString() ?: "T",
                    size = ToolBoxThemeTokens.sizes.compactToolGlyph,
                )
                Spacer(Modifier.width(ToolBoxThemeTokens.spacing.oneHalf))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.half)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(
                            tool.name,
                            modifier = Modifier.weight(1f),
                            textStyle = ToolBoxThemeTokens.textStyles.title,
                            weight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        if (tool.pinnedOrder != null) {
                            AppText(
                                "已固定",
                                textStyle = ToolBoxThemeTokens.textStyles.label,
                                color = ToolBoxThemeTokens.colors.primary,
                            )
                        }
                    }
                    AppText(
                        "${tool.activeVersionName ?: "版本未知"} · ${tool.bundleBytes.fileSizeLabel()} · ${tool.signatureState.label}",
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        color = ToolBoxThemeTokens.colors.textSecondary,
                        maxLines = 2,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
                    .clip(RoundedCornerShape(ToolBoxThemeTokens.radii.denseSurface))
                    .clickable(role = Role.Button, onClick = onDetails)
                    .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    "详情",
                    textStyle = ToolBoxThemeTokens.textStyles.label,
                    color = ToolBoxThemeTokens.colors.primary,
                    weight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
fun ToolDetailScreen(
    toolId: String,
    state: CatalogUiState,
    onAction: (CatalogAction) -> Unit,
    onBack: () -> Unit,
    onPermissions: (String) -> Unit,
) {
    DisposableEffect(toolId) {
        onAction(CatalogAction.SelectDetails(toolId))
        onDispose { onAction(CatalogAction.SelectDetails(null)) }
    }
    val tool = state.tools.firstOrNull { it.toolId == toolId }
    DetailScreen(title = tool?.name ?: "工具详情", onBack = onBack, subtitle = toolId) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ToolBoxThemeTokens.spacing.two,
                vertical = ToolBoxThemeTokens.spacing.oneHalf,
            ),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            if (tool == null) {
                item { CatalogStatusState(if (state.isLoaded) "该工具已卸载或目录已更新。" else "正在读取工具详情") }
            } else {
                item { ToolFactsCard(tool) }
                state.feedback?.let { feedback -> item { CatalogFeedbackCard(feedback, onAction) } }
                item {
                    ToolBoxPrimaryButton(
                        label = "打开工具",
                        onClick = { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { DetailAction("权限与授权记录", "查看并撤销此工具的真实授权") { onPermissions(tool.toolId) } }
                item {
                    DetailAction(if (tool.pinnedOrder == null) "固定到前面" else "取消固定", "只改变宿主目录顺序") {
                        onAction(CatalogAction.TogglePinned(tool.toolId))
                    }
                }
                item {
                    DetailAction("卸载工具", "将删除代码、目录记录和授权", true) {
                        onAction(CatalogAction.RequestUninstall(tool.toolId))
                    }
                }
                state.uninstallConfirmation?.takeIf { it.toolId == tool.toolId }?.let { confirmation ->
                    item {
                        UninstallConfirmationCard(
                            confirmation.toolName,
                            { onAction(CatalogAction.CancelUninstall) },
                            { onAction(CatalogAction.ConfirmUninstall) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolFactsCard(tool: CatalogTool) {
    SurfaceCard(contentPadding = ToolBoxThemeTokens.spacing.oneHalf) {
        AppText(tool.name, textStyle = ToolBoxThemeTokens.textStyles.sectionTitle)
        AppText(
            tool.toolId,
            textStyle = ToolBoxThemeTokens.textStyles.label,
            color = ToolBoxThemeTokens.colors.textSecondary,
        )
        AppText("版本 ${tool.activeVersionName ?: "未知"} (${tool.activeVersionCode ?: "-"})")
        AppText("代码 ${tool.bundleBytes.fileSizeLabel()} · ${tool.signatureState.label}")
        AppText("启动状态：${tool.launchState.label}", color = ToolBoxThemeTokens.colors.textSecondary)
    }
}

@Composable
private fun DetailAction(title: String, summary: String, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) ToolBoxThemeTokens.colors.danger else ToolBoxThemeTokens.colors.textPrimary
    SurfaceCard(Modifier.clickable(role = Role.Button, onClick = onClick), ToolBoxThemeTokens.spacing.oneHalf) {
        AppText(title, textStyle = ToolBoxThemeTokens.textStyles.title, color = color, weight = FontWeight.SemiBold)
        AppText(
            summary,
            textStyle = ToolBoxThemeTokens.textStyles.label,
            color = ToolBoxThemeTokens.colors.textSecondary,
        )
    }
}

@Composable
private fun UninstallConfirmationCard(toolName: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    SurfaceCard(contentPadding = ToolBoxThemeTokens.spacing.oneHalf) {
        AppText(
            "确认卸载 $toolName？",
            textStyle = ToolBoxThemeTokens.textStyles.title,
            weight = FontWeight.Bold,
            color = ToolBoxThemeTokens.colors.danger,
        )
        AppText(
            "此操作只针对具名工具；失败或恢复未完成时会保留明确状态。",
            textStyle = ToolBoxThemeTokens.textStyles.label,
            color = ToolBoxThemeTokens.colors.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one)) {
            FilterButton("取消", false, Modifier.weight(1f), onCancel)
            FilterButton("确认卸载", true, Modifier.weight(1f), onConfirm)
        }
    }
}

@Composable
private fun CatalogFeedbackCard(feedback: CatalogFeedback, onAction: (CatalogAction) -> Unit) {
    SurfaceCard(contentPadding = ToolBoxThemeTokens.spacing.oneHalf) {
        val color = when (feedback) {
            is CatalogFeedback.Completed -> ToolBoxThemeTokens.colors.success
            is CatalogFeedback.Failure -> ToolBoxThemeTokens.colors.danger
            is CatalogFeedback.RecoveryPending -> ToolBoxThemeTokens.colors.warning
        }
        AppText(feedback.message, textStyle = ToolBoxThemeTokens.textStyles.metadata, color = color)
        Row(horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one)) {
            if (feedback is CatalogFeedback.RecoveryPending) {
                FilterButton("恢复目录", true, Modifier.weight(1f)) { onAction(CatalogAction.RecoverPendingMutation) }
            }
            FilterButton("关闭", false, Modifier.weight(1f)) { onAction(CatalogAction.DismissFeedback) }
        }
    }
}

private val CatalogFeedback.message: String
    get() = when (this) {
        is CatalogFeedback.Completed -> message
        is CatalogFeedback.Failure -> message
        is CatalogFeedback.RecoveryPending -> message
    }

private val CatalogSort.label: String
    get() = when (this) {
        CatalogSort.PINNED_THEN_RECENT -> "固定"
        CatalogSort.RECENTLY_OPENED -> "最近"
        CatalogSort.NAME -> "名称"
        CatalogSort.INSTALLED_VERSION -> "版本"
    }

private val SignatureState.label: String
    get() = when (this) {
        SignatureState.VERIFIED_TRUSTED -> "可信签名"
        SignatureState.VERIFIED_UNKNOWN -> "签名未信任"
        SignatureState.UNSIGNED -> "未签名"
        SignatureState.INVALID -> "签名无效"
    }

private val SignatureState.shortLabel: String
    get() = when (this) {
        SignatureState.VERIFIED_TRUSTED -> "可信"
        SignatureState.VERIFIED_UNKNOWN -> "待信任"
        SignatureState.UNSIGNED -> "未签名"
        SignatureState.INVALID -> "已阻止"
    }

private val LaunchState?.label: String
    get() = when (this) {
        LaunchState.PENDING -> "待首次验证"
        LaunchState.STABLE -> "稳定"
        LaunchState.FAILED -> "启动失败"
        null -> "未知"
    }

private fun Long?.fileSizeLabel(): String = when {
    this == null -> "大小未知"
    this < 1024L -> "$this B"
    this < 1024L * 1024L -> "${this / 1024L} KB"
    else -> "${this / (1024L * 1024L)} MB"
}
