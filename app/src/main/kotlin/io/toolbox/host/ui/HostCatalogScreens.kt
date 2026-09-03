package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconButton
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxSearchField
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxValueRow
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.R
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.COMPACT_RECENT_TOOL_COUNT
import io.toolbox.host.catalog.CatalogFeedback
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportUiState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.text.DateFormat
import java.util.Date

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
    val subtitle = if (state.tools.isEmpty()) "轻量网页工具架" else "${state.tools.size} 个已安装工具"
    PrimaryScreen(
        selected = MainDestination.Tools,
        onDestination = onDestination,
        title = "工具",
        subtitle = subtitle,
        onImport = onImport,
    ) { padding, layout ->
        val recentLimit = if (layout.isCompact) COMPACT_RECENT_TOOL_COUNT else 3
        val recentTools = state.recentTools.take(recentLimit)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {
            item("search") {
                ToolBoxSearchField(
                    value = state.query,
                    onValueChange = { onAction(CatalogAction.SetQuery(it)) },
                    placeholder = "搜索工具",
                )
            }
            item("after-search") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }

            if (importState.working || importState.message != null) {
                item("import-feedback") {
                    FeedbackSurface(
                        message = if (importState.working) "正在检查并安装工具…" else requireNotNull(importState.message),
                        tone = when {
                            importState.working -> FeedbackTone.Progress
                            importState.succeeded -> FeedbackTone.Success
                            else -> FeedbackTone.Error
                        },
                        dismissible = !importState.working,
                        onDismiss = onDismissImport,
                        modifier = Modifier.padding(bottom = ToolBoxThemeTokens.spacing.oneHalf),
                    )
                }
            }
            state.feedback?.let { feedback ->
                item("catalog-feedback") {
                    FeedbackSurface(
                        message = feedback.message,
                        tone = if (feedback is CatalogFeedback.Completed) FeedbackTone.Success else FeedbackTone.Error,
                        dismissible = true,
                        onDismiss = { onAction(CatalogAction.DismissFeedback) },
                        modifier = Modifier.padding(bottom = ToolBoxThemeTokens.spacing.oneHalf),
                    )
                }
            }

            when {
                !state.isLoaded -> item("loading") { CatalogStatusState("正在读取工具") }
                state.tools.isEmpty() -> item("empty") { EmptyCatalogState(onImport, onInstallExamples) }
                state.visibleTools.isEmpty() -> {
                    item("installed-title") { SectionHeader("搜索结果") }
                    item("no-match") { CatalogStatusState("没有匹配的工具") }
                }
                else -> {
                    if (!state.isSearching && recentTools.isNotEmpty()) {
                        item("recent-title") { SectionHeader("最近使用") }
                        item("before-recent") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
                        item("recent-tools", contentType = "recent-tools") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
                            ) {
                                recentTools.forEach { tool ->
                                    CatalogRecentCard(
                                        tool = tool,
                                        onOpen = { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(recentLimit - recentTools.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        item("after-recent") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
                    }
                    item("installed-title") {
                        SectionHeader(if (state.isSearching) "搜索结果 · ${state.visibleTools.size}" else "全部工具")
                    }
                    item("before-tools") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
                    itemsIndexed(
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
    val confirmation = state.uninstallConfirmation?.takeIf { it.toolId == toolId }
    var menuVisible by rememberSaveable(toolId) { mutableStateOf(false) }

    DetailScreen(
        title = "工具详情",
        onBack = onBack,
        actions = {
            ToolBoxIconButton(ToolBoxIconKey.More, "更多操作", { menuVisible = true })
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
                .fillMaxWidth()
                .fillMaxHeight()
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(
                start = ToolBoxThemeTokens.spacing.two,
                top = ToolBoxThemeTokens.spacing.oneHalf,
                end = ToolBoxThemeTokens.spacing.two,
                bottom = ToolBoxThemeTokens.spacing.twoHalf,
            ),
        ) {
            if (tool == null) {
                item("missing") { CatalogStatusState("该工具已不存在") }
            } else {
                item("identity") {
                    SurfaceCard {
                        ToolIdentity(tool)
                        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf))
                        ToolBoxPrimaryButton(
                            label = "打开工具",
                            onClick = { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item("before-management") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
                item("management-title") { SectionHeader("管理") }
                item("management-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
                item("management") {
                    ToolBoxGroupedSurface {
                        ToolBoxSettingRow(
                            title = "权限",
                            summary = "管理此工具已声明的能力",
                            icon = ToolBoxIconKey.Shield,
                            onClick = { onPermissions(tool.toolId) },
                        )
                        ToolBoxGroupDivider()
                        ToolBoxSettingRow(
                            title = "后台任务",
                            summary = "查看运行状态、结果与取消操作",
                            icon = ToolBoxIconKey.Clock,
                            onClick = { onBackground(tool.toolId) },
                        )
                    }
                }
                item("before-information") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two)) }
                item("information-title") { SectionHeader("信息") }
                item("information-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
                item("information") {
                    ToolBoxGroupedSurface {
                        ToolBoxValueRow(title = "版本", value = tool.versionName)
                        ToolBoxGroupDivider(startPadding = ToolBoxThemeTokens.spacing.oneHalf)
                        ToolBoxValueRow(title = "工具大小", value = tool.bundleBytes.fileSizeLabel())
                        ToolBoxGroupDivider(startPadding = ToolBoxThemeTokens.spacing.oneHalf)
                        ToolBoxValueRow(title = "最近打开", value = tool.lastOpenedAt.lastOpenedLabel())
                    }
                }
                item("before-delete") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
                item("delete") {
                    ToolBoxTextButton(
                        label = "删除工具",
                        onClick = { onAction(CatalogAction.RequestUninstall(tool.toolId)) },
                        modifier = Modifier.fillMaxWidth(),
                        contentColor = ToolBoxThemeTokens.colors.danger,
                    )
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
                    ToolBoxSettingRow(
                        title = "打开",
                        icon = ToolBoxIconKey.OpenInNew,
                        onClick = {
                            menuVisible = false
                            onAction(CatalogAction.RequestRuntimeLaunch(it.toolId))
                        },
                    )
                    ToolBoxGroupDivider()
                    ToolBoxSettingRow(
                        title = "权限",
                        icon = ToolBoxIconKey.Shield,
                        onClick = {
                            menuVisible = false
                            onPermissions(it.toolId)
                        },
                    )
                    ToolBoxGroupDivider()
                    ToolBoxSettingRow(
                        title = "后台任务",
                        icon = ToolBoxIconKey.Clock,
                        onClick = {
                            menuVisible = false
                            onBackground(it.toolId)
                        },
                    )
                    ToolBoxGroupDivider()
                    ToolBoxTextButton(
                        label = "删除工具",
                        onClick = {
                            menuVisible = false
                            onAction(CatalogAction.RequestUninstall(it.toolId))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "从菜单删除${it.name}" },
                        contentColor = ToolBoxThemeTokens.colors.danger,
                    )
                }
            }
        }

        OverlayDialog(
            show = confirmation != null,
            title = confirmation?.let { "删除 ${it.toolName}？" },
            summary = "工具文件、权限、存储和后台任务都会一并删除。",
            onDismissRequest = { onAction(CatalogAction.CancelUninstall) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
            ) {
                ToolBoxTextButton(
                    label = "取消",
                    onClick = { onAction(CatalogAction.CancelUninstall) },
                    modifier = Modifier.weight(1f),
                    contentColor = ToolBoxThemeTokens.colors.textPrimary,
                )
                ToolBoxPrimaryButton(
                    label = "确认删除",
                    onClick = { onAction(CatalogAction.ConfirmUninstall) },
                    modifier = Modifier.weight(1f),
                    destructive = true,
                )
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
    val visual = tool.visual(ToolBoxThemeTokens.colors.primary)
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
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ToolBoxThemeTokens.sizes.catalogRow)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(start = ToolBoxThemeTokens.spacing.oneHalf, end = ToolBoxThemeTokens.spacing.half),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CatalogToolGlyph(
                    toolId = tool.toolId,
                    versionCode = tool.versionCode,
                    visual = visual,
                    size = ToolBoxThemeTokens.sizes.compactToolGlyph,
                )
                Spacer(Modifier.width(ToolBoxThemeTokens.spacing.oneHalf))
                Column(Modifier.weight(1f)) {
                    AppText(
                        text = tool.name,
                        textStyle = ToolBoxThemeTokens.textStyles.title,
                        weight = FontWeight.SemiBold,
                        maxLines = 2,
                    )
                    AppText(
                        text = "${tool.versionName} · ${tool.bundleBytes.fileSizeLabel()}",
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        color = ToolBoxThemeTokens.colors.textSecondary,
                    )
                }
            }
            ToolBoxIconButton(ToolBoxIconKey.ChevronRight, "管理${tool.name}", onDetails)
        }
        if (!isLast) ToolBoxGroupDivider(startPadding = 68.dp)
    }
}

@Composable
private fun CatalogRecentCard(
    tool: CatalogTool,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = tool.visual(ToolBoxThemeTokens.colors.primary)
    ToolBoxCard(
        modifier = modifier.semantics { contentDescription = "打开最近使用的${tool.name}" },
        onClick = onOpen,
        contentPadding = PaddingValues(ToolBoxThemeTokens.spacing.oneHalf),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CatalogToolGlyph(
                toolId = tool.toolId,
                versionCode = tool.versionCode,
                visual = visual,
                size = 38.dp,
            )
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
            Column(Modifier.weight(1f)) {
                AppText(
                    text = tool.name,
                    textStyle = ToolBoxThemeTokens.textStyles.metadata,
                    weight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                AppText(
                    text = tool.versionName,
                    textStyle = ToolBoxThemeTokens.textStyles.label,
                    color = ToolBoxThemeTokens.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ToolIdentity(tool: CatalogTool) {
    val visual = tool.visual(ToolBoxThemeTokens.colors.primary)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogToolGlyph(
            toolId = tool.toolId,
            versionCode = tool.versionCode,
            visual = visual,
        )
        Spacer(Modifier.width(ToolBoxThemeTokens.spacing.oneHalf))
        Column(Modifier.weight(1f)) {
            AppText(
                text = tool.name,
                textStyle = ToolBoxThemeTokens.textStyles.screenTitle,
                weight = FontWeight.Bold,
                maxLines = 2,
            )
            AppText(
                text = "${tool.versionName} · ${tool.bundleBytes.fileSizeLabel()}",
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
                color = ToolBoxThemeTokens.colors.textSecondary,
            )
        }
    }
}

private enum class FeedbackTone { Progress, Success, Error }

@Composable
private fun FeedbackSurface(
    message: String,
    tone: FeedbackTone,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ToolBoxThemeTokens.colors
    val container = when (tone) {
        FeedbackTone.Progress -> colors.softPrimary
        FeedbackTone.Success -> colors.softSuccess
        FeedbackTone.Error -> colors.softDanger
    }
    val content = when (tone) {
        FeedbackTone.Progress -> colors.primary
        FeedbackTone.Success -> colors.success
        FeedbackTone.Error -> colors.danger
    }
    val icon = when (tone) {
        FeedbackTone.Progress -> ToolBoxIconKey.Clock
        FeedbackTone.Success -> ToolBoxIconKey.Check
        FeedbackTone.Error -> ToolBoxIconKey.Close
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ToolBoxThemeTokens.radii.badge))
            .background(container)
            .heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
            .padding(start = ToolBoxThemeTokens.spacing.oneHalf),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBoxIcon(icon = icon, contentDescription = null, tint = content)
        Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
        AppText(
            text = message,
            modifier = Modifier.weight(1f),
            color = colors.textPrimary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
        if (dismissible) {
            ToolBoxIconButton(
                icon = ToolBoxIconKey.Close,
                contentDescription = "关闭提示",
                onClick = onDismiss,
            )
        } else {
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.oneHalf))
        }
    }
}

@Composable
internal fun EmptyCatalogState(onImport: () -> Unit, onInstallExamples: () -> Unit) {
    SurfaceCard(Modifier.testTag(HostTestTags.CatalogEmptyState)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ToolGlyph(
                icon = ToolBoxIconKey.Tools,
                accent = ToolBoxThemeTokens.colors.primary,
            )
        }
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf))
        AppText(
            text = "还没有工具",
            modifier = Modifier.fillMaxWidth(),
            textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
            weight = FontWeight.SemiBold,
            align = androidx.compose.ui.text.style.TextAlign.Center,
        )
        AppText(
            text = "先安装四个可直接使用的范例，或导入自己的 .tbx。",
            modifier = Modifier.fillMaxWidth(),
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
            color = ToolBoxThemeTokens.colors.textSecondary,
            align = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf))
        ToolBoxPrimaryButton("安装四个范例", onInstallExamples, Modifier.fillMaxWidth())
        ToolBoxTextButton("导入 .tbx", onImport, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun CatalogStatusState(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = message,
            color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
    }
}

internal data class ToolVisual(
    val icon: ToolBoxIconKey,
    val accent: Color,
    val imageResource: Int? = null,
)

internal fun CatalogTool.visual(primary: Color): ToolVisual = when (toolId) {
    "io.toolbox.positioncalculator" ->
        ToolVisual(ToolBoxIconKey.Calculator, primary, R.drawable.example_position_calculator)
    "io.toolbox.quicknotes" ->
        ToolVisual(ToolBoxIconKey.Note, Color(0xFF6A78B7), R.drawable.example_quick_notes)
    "io.toolbox.backgroundtaskdemo" ->
        ToolVisual(ToolBoxIconKey.Code, Color(0xFF317F87), R.drawable.example_background_tasks)
    "io.toolbox.notificationlab" ->
        ToolVisual(ToolBoxIconKey.Notifications, Color(0xFF526A9C), R.drawable.example_notification_lab)
    else -> fallbackVisual(primary)
}

private fun CatalogTool.fallbackVisual(primary: Color): ToolVisual = when {
    toolId.contains("position", ignoreCase = true) || name.contains("计算") ->
        ToolVisual(ToolBoxIconKey.Calculator, primary)
    toolId.contains("note", ignoreCase = true) || name.contains("笔记") ->
        ToolVisual(ToolBoxIconKey.Note, Color(0xFFFFB000))
    toolId.contains("background", ignoreCase = true) || name.contains("后台") ->
        ToolVisual(ToolBoxIconKey.Code, Color(0xFF0A8F6A))
    else -> ToolVisual(ToolBoxIconKey.Tools, Color(0xFF7C4DFF))
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

private fun Long?.lastOpenedLabel(): String = when (this) {
    null -> "尚未打开"
    else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))
}
