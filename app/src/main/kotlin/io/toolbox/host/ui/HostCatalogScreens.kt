package io.toolbox.host.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import io.toolbox.host.importflow.ImportOutcome
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.toolbox.core.ui.component.toolBoxOpenDesignSurface
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconButton
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxSecondaryButton
import io.toolbox.core.ui.component.ToolBoxModalDialog
import io.toolbox.core.ui.component.ToolBoxDestructiveButton
import io.toolbox.core.ui.component.ToolBoxSearchField
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxValueRow
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.HostImportConfirmationKind
import io.toolbox.host.R
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogFeedback
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.tool.packagekit.lifecycle.PackageImportPhase
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ToolManagerContent(
    state: CatalogUiState,
    importState: ImportUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onAction: (CatalogAction) -> Unit,
    onImport: () -> Unit,
    onInstallExamples: () -> Unit,
    onDismissImport: () -> Unit,
    onOpenDetails: (String) -> Unit,
    onExpireImportSuccess: (Long) -> Unit = {},
    onConfirmImport: () -> Unit = {},
    onCancelImport: () -> Unit = {},
    onCancelActiveImport: () -> Unit = {},
    runningTools: @Composable () -> Unit = {},
    home: Boolean = false,
    editing: Boolean = false,
    onEditingChange: (Boolean) -> Unit = {},
    creatingGroup: Boolean = false,
    onDismissCreateGroup: () -> Unit = {},
) {
    var selectedToolId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingFavorites by rememberSaveable { mutableStateOf(false) }
    var editingGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousHome by rememberSaveable { mutableStateOf(home) }
    val toolsById = remember(state.tools) { state.tools.associateBy { it.toolId } }
    val selectedTool = selectedToolId?.let(toolsById::get)
    val recentTools = state.recentTools
    val drag = rememberCatalogHomeDragState(home && editing, listState, contentPadding)
    BackHandler(home && editing && selectedToolId == null && editingGroupId == null && !addingFavorites && !creatingGroup) {
        onEditingChange(false)
    }
    LaunchedEffect(home) {
        if (previousHome != home) {
            if (!home) onEditingChange(false)
            editingGroupId = null
            addingFavorites = false
            selectedToolId = null
            previousHome = home
        }
    }
    LaunchedEffect(state.isLoaded, selectedToolId, selectedTool) {
        if (state.isLoaded && selectedToolId != null && selectedTool == null) selectedToolId = null
    }
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(importState, accessibilityManager) {
        if (importState.succeeded && importState.message != null) {
            delay(accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = INSTALL_SUCCESS_FEEDBACK_DURATION_MS,
                containsIcons = true, containsText = true, containsControls = false,
            ) ?: INSTALL_SUCCESS_FEEDBACK_DURATION_MS)
            onExpireImportSuccess(importState.feedbackId)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val direction = LocalLayoutDirection.current
        val contentWidth = maxWidth - contentPadding.calculateStartPadding(direction) - contentPadding.calculateEndPadding(direction)
        val fontScale = LocalDensity.current.fontScale
        val columns = homeGridColumnCount((contentWidth - 32.dp).value, fontScale)
        val recentTileWidth = (112.dp * fontScale.coerceIn(1f, 1.35f)).coerceAtMost(contentWidth * 0.45f)
        LazyColumn(
            userScrollEnabled = drag.active == null,
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(if (home) "catalog_home_list" else "catalog_tools_list")
                .catalogDragSurface(drag, home && editing),
            contentPadding = contentPadding,
        ) {
            if (home && editing) item("organize-help") {
                AppText("长按拖动排序，点击工具或分组旁的 ··· 编辑", color = ToolBoxThemeTokens.colors.textSecondary,
                    textStyle = ToolBoxThemeTokens.textStyles.metadata, modifier = Modifier.padding(bottom = 16.dp))
            }
            if (!home) {
                item("search") {
                    ToolBoxSearchField(
                        value = state.query,
                        onValueChange = { onAction(CatalogAction.SetQuery(it)) },
                        placeholder = "搜索工具",
                    )
                }
                item("installed-title") { CatalogListHeader(state, onAction) }
                item("after-search") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
            }

            if (importState.working || importState.message != null) {
                item("import-feedback") {
                    FeedbackSurface(
                        message = if (importState.working) importState.progressMessage else requireNotNull(importState.message),
                        tone = importState.feedbackTone,
                        dismissible = !importState.working && !importState.succeeded,
                        onDismiss = onDismissImport,
                        onCancel = onCancelActiveImport.takeIf {
                            importState.working && importState.importPhase == PackageImportPhase.IMPORTING
                        },
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

            if (home && state.isLoaded && state.tools.isNotEmpty()) {
                catalogHomeSections(
                    state, toolsById, editing, columns, drag, onAction,
                    onEditGroup = { editingGroupId = it }, onAddFavorites = { addingFavorites = true },
                    onOptions = { selectedToolId = it },
                    hasRecentTools = recentTools.isNotEmpty(),
                    recentContent = {
                        CatalogRecentTools(recentTools, recentTileWidth, onAction, editing) { selectedToolId = it }
                    },
                )
                item("running-tools", contentType = "running-tools") { runningTools() }
            }

            when {
                !state.isLoaded -> item("loading") { CatalogStatusState("正在读取工具") }
                state.tools.isEmpty() -> item("empty") { EmptyCatalogState(onImport, onInstallExamples) }
                home -> Unit
                state.visibleTools.isEmpty() -> item("no-match") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CatalogStatusState("没有匹配的工具")
                        ToolBoxTextButton("清除搜索", { onAction(CatalogAction.SetQuery("")) }, outlined = false)
                    }
                }
                else -> itemsIndexed(
                    items = state.visibleTools,
                    key = { _, tool -> tool.toolId },
                    contentType = { _, _ -> "tool" },
                ) { _, tool ->
                    CatalogToolRow(
                        tool = tool,
                        onOpen = { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) },
                        onDetails = { selectedToolId = tool.toolId },
                    )
                }
            }
        }
        CatalogDragPreview(drag)
    }

    ImportReplacementDialog(importState, onConfirmImport, onCancelImport)
    if (addingFavorites) CatalogFavoritesPicker(state, onAction) { addingFavorites = false }
    if (selectedTool != null) CatalogToolOptions(selectedTool, state, onAction,
        onDismiss = { selectedToolId = null },
        onManage = { selectedToolId = null; onOpenDetails(selectedTool.toolId) },
    )
    if (editingGroupId != null) CatalogGroupEditor(
        group = state.layout.groups.firstOrNull { it.id == editingGroupId },
        state = state, creating = editingGroupId == "", onAction = onAction,
        onDismiss = { editingGroupId = null },
    )
    if (home && creatingGroup) CatalogGroupEditor(
        group = null, state = state, creating = true, onAction = onAction,
        onDismiss = onDismissCreateGroup,
    )
}

@Composable
internal fun ImportReplacementDialog(
    importState: ImportUiState,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
) {
    val confirmation = importState.confirmation ?: return
    val title = when (confirmation.kind) {
        HostImportConfirmationKind.SAME_VERSION -> "覆盖安装同一版本？"
        HostImportConfirmationKind.DOWNGRADE -> "安装较低版本？"
        HostImportConfirmationKind.UPDATE -> "更新并保留工具数据？"
    }
    val summary = confirmation.let {
        val installed = "${it.installedVersionName}（${it.installedVersionCode}）"
        val incoming = "${it.incomingVersionName}（${it.incomingVersionCode}）"
        val replacement = when (it.kind) {
            HostImportConfirmationKind.SAME_VERSION -> "两者 versionCode 相同，将覆盖现有工具文件。"
            HostImportConfirmationKind.DOWNGRADE -> "较低版本可能无法读取新版数据。"
            HostImportConfirmationKind.UPDATE -> "无法通过原工具的签名确认此次更新的身份。"
        }
        "${it.toolName} 当前为 $installed，待安装为 $incoming。$replacement 继续会停止旧运行和后台任务，并允许这份更新使用已保存的登录信息、设置与仍有效的权限。原来关闭的权限不会开启。仅在信任此包来源时继续。"
    }
    ToolBoxModalDialog(onDismissRequest = onCancelImport) {
        HostConfirmationContent(
            title = title,
            summary = summary,
            confirmLabel = if (confirmation.kind == HostImportConfirmationKind.SAME_VERSION) "仍要覆盖" else "仍要安装",
            onConfirm = onConfirmImport,
            onCancel = onCancelImport,
        )
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

    DetailScreen(title = "工具详情", onBack = onBack) { chromePadding ->
        LazyColumn(
            modifier = Modifier
                .widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
                .fillMaxWidth()
                .fillMaxHeight()
                .align(Alignment.TopCenter),
            contentPadding = mergePadding(
                chromePadding,
                PaddingValues(
                    start = ToolBoxThemeTokens.spacing.two,
                    top = ToolBoxThemeTokens.spacing.oneHalf,
                    end = ToolBoxThemeTokens.spacing.two,
                    bottom = ToolBoxThemeTokens.spacing.twoHalf,
                ),
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
                        ToolBoxSettingRow(title = "权限", icon = ToolBoxIconKey.Shield, onClick = { onPermissions(tool.toolId) })
                        ToolBoxGroupDivider()
                        ToolBoxSettingRow(title = "后台任务", icon = ToolBoxIconKey.Clock, onClick = { onBackground(tool.toolId) })
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
                    ToolBoxDestructiveButton(
                        label = "删除工具",
                        onClick = { onAction(CatalogAction.RequestUninstall(tool.toolId)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        OverlayDialog(
            show = confirmation != null,
            onDismissRequest = { onAction(CatalogAction.CancelUninstall) },
        ) {
            confirmation?.let {
                HostConfirmationContent(
                    title = "删除 ${it.toolName}？",
                    summary = "工具文件、权限、存储和后台任务都会一并删除。",
                    confirmLabel = "确认删除",
                    onConfirm = { onAction(CatalogAction.ConfirmUninstall) },
                    onCancel = { onAction(CatalogAction.CancelUninstall) },
                    destructive = true,
                )
            }
        }
    }
}

@Composable
internal fun CatalogToolRow(tool: CatalogTool, onOpen: () -> Unit, onDetails: () -> Unit) {
    val corner = ToolBoxThemeTokens.radii.denseSurface
    val visual = tool.visual(ToolBoxThemeTokens.colors.primary)
    val presentation = rememberCatalogToolPresentation(tool.toolId, tool.versionCode)
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .toolBoxOpenDesignSurface(RoundedCornerShape(corner))
            .indication(interactionSource, LocalIndication.current)
            .testTag("tool_card:" + tool.toolId),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f)
                    .heightIn(min = ToolBoxThemeTokens.sizes.catalogRow)
                    .testTag("tool_main:" + tool.toolId)
                    .combinedClickable(interactionSource = interactionSource, indication = null,
                        role = Role.Button, onClickLabel = "打开${tool.name}", onClick = onOpen,
                        onLongClickLabel = "收藏、分组与管理", onLongClick = onDetails)
                    .padding(start = ToolBoxThemeTokens.spacing.oneHalf, end = ToolBoxThemeTokens.spacing.half,
                        top = ToolBoxThemeTokens.spacing.one, bottom = ToolBoxThemeTokens.spacing.one),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CatalogToolArtwork(presentation.bitmap, visual, ToolBoxThemeTokens.sizes.compactToolGlyph)
                Spacer(Modifier.width(ToolBoxThemeTokens.spacing.oneHalf))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(tool.name, maxLines = 2, textStyle = ToolBoxThemeTokens.textStyles.title, weight = FontWeight.SemiBold)
                    // Reserve the same one-line summary slot while metadata loads or is absent.
                    AppText(
                        text = presentation.description ?: "版本 ${tool.versionName}",
                        maxLines = 1,
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        color = ToolBoxThemeTokens.colors.textSecondary,
                    )
                }
            }
            // Sibling targets: managing a tool must never bubble into the open action.
            ToolBoxIconButton(
                icon = ToolBoxIconKey.More,
                contentDescription = "${tool.name}的收藏、分组与管理",
                onClick = onDetails,
                modifier = Modifier.testTag("tool_more:" + tool.toolId),
            )
        }
    }
}

@Composable
internal fun CatalogRecentTools(
    tools: List<CatalogTool>,
    tileWidth: Dp,
    onAction: (CatalogAction) -> Unit,
    editing: Boolean = false,
    onOptions: (String) -> Unit,
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tools, key = CatalogTool::toolId) { tool ->
            CatalogHomeTile(tool, editing,
                onOpen = { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) },
                onOptions = { onOptions(tool.toolId) },
                labelMaxLines = 1,
                modifier = Modifier.width(tileWidth).testTag("recent:" + tool.toolId))
        }
    }
}

@Composable
private fun ToolIdentity(tool: CatalogTool) {
    val visual = tool.visual(ToolBoxThemeTokens.colors.primary)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CatalogToolGlyph(toolId = tool.toolId, versionCode = tool.versionCode, visual = visual)
        Spacer(Modifier.width(ToolBoxThemeTokens.spacing.oneHalf))
        AppText(text = tool.name, modifier = Modifier.weight(1f),
            textStyle = ToolBoxThemeTokens.textStyles.detailTitle, weight = FontWeight.Bold)
    }
}

private const val INSTALL_SUCCESS_FEEDBACK_DURATION_MS = 3_000L
internal enum class FeedbackTone { Progress, Success, Neutral, Error }

internal val ImportUiState.feedbackTone: FeedbackTone get() = when {
    working -> FeedbackTone.Progress
    outcome == ImportOutcome.Success -> FeedbackTone.Success
    outcome == ImportOutcome.Cancelled -> FeedbackTone.Neutral
    else -> FeedbackTone.Error
}

@Composable
internal fun FeedbackSurface(
    message: String,
    tone: FeedbackTone,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val colors = ToolBoxThemeTokens.colors
    val container = when (tone) {
        FeedbackTone.Progress -> colors.softPrimary
        FeedbackTone.Success -> colors.softSuccess
        FeedbackTone.Neutral -> colors.surfaceMuted
        FeedbackTone.Error -> colors.softDanger
    }
    val content = when (tone) {
        FeedbackTone.Progress -> colors.primary
        FeedbackTone.Success -> colors.onSoftSuccess
        FeedbackTone.Neutral -> colors.textSecondary
        FeedbackTone.Error -> colors.danger
    }
    val icon = when (tone) {
        FeedbackTone.Progress -> ToolBoxIconKey.Clock
        FeedbackTone.Success -> ToolBoxIconKey.Check
        FeedbackTone.Neutral -> ToolBoxIconKey.Close
        FeedbackTone.Error -> ToolBoxIconKey.Close
    }
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            if (tone != FeedbackTone.Progress) liveRegion = LiveRegionMode.Polite
        }.clip(RoundedCornerShape(ToolBoxThemeTokens.radii.badge)).background(container)
            .heightIn(min = ToolBoxThemeTokens.sizes.touchTarget).padding(start = ToolBoxThemeTokens.spacing.oneHalf),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBoxIcon(icon = icon, contentDescription = null, tint = content)
        Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
        AppText(text = message, modifier = Modifier.weight(1f), color = colors.textPrimary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata)
        if (onCancel != null) {
            ToolBoxTextButton(label = "取消", onClick = onCancel)
        } else if (dismissible) {
            ToolBoxIconButton(icon = ToolBoxIconKey.Close, contentDescription = "关闭提示", onClick = onDismiss)
        } else {
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.oneHalf))
        }
    }
}

@Composable
internal fun EmptyCatalogState(onImport: () -> Unit, onInstallExamples: () -> Unit) {
    SurfaceCard(Modifier.testTag(HostTestTags.CatalogEmptyState)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ToolGlyph(icon = ToolBoxIconKey.Tools, accent = ToolBoxThemeTokens.colors.primary)
        }
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf))
        AppText(text = "还没有工具", modifier = Modifier.fillMaxWidth(),
            textStyle = ToolBoxThemeTokens.textStyles.sectionTitle, weight = FontWeight.SemiBold,
            align = androidx.compose.ui.text.style.TextAlign.Center)
        AppText(text = "先安装四个可直接使用的范例，或导入自己的 .tbx。", modifier = Modifier.fillMaxWidth(),
            textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary,
            align = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf))
        ToolBoxPrimaryButton("安装四个范例", onInstallExamples, Modifier.fillMaxWidth())
        ToolBoxTextButton("导入 .tbx", onImport, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun CatalogStatusState(message: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
        AppText(text = message, color = ToolBoxThemeTokens.colors.textSecondary, textStyle = ToolBoxThemeTokens.textStyles.metadata)
    }
}

internal data class ToolVisual(val icon: ToolBoxIconKey, val accent: Color, val imageResource: Int? = null)

internal fun CatalogTool.visual(primary: Color): ToolVisual = when (toolId) {
    "io.toolbox.positioncalculator" -> ToolVisual(ToolBoxIconKey.Calculator, primary, R.drawable.example_position_calculator)
    "io.toolbox.quicknotes" -> ToolVisual(ToolBoxIconKey.Note, Color(0xFF6A78B7), R.drawable.example_quick_notes)
    "io.toolbox.backgroundtaskdemo" -> ToolVisual(ToolBoxIconKey.Code, Color(0xFF317F87), R.drawable.example_background_tasks)
    "io.toolbox.notificationlab" -> ToolVisual(ToolBoxIconKey.Notifications, Color(0xFF526A9C), R.drawable.example_notification_lab)
    else -> fallbackVisual(primary)
}

private fun CatalogTool.fallbackVisual(primary: Color): ToolVisual = when {
    toolId.contains("position", ignoreCase = true) || name.contains("计算") -> ToolVisual(ToolBoxIconKey.Calculator, primary)
    toolId.contains("note", ignoreCase = true) || name.contains("笔记") -> ToolVisual(ToolBoxIconKey.Note, Color(0xFFFFB000))
    toolId.contains("background", ignoreCase = true) || name.contains("后台") -> ToolVisual(ToolBoxIconKey.Code, Color(0xFF0A8F6A))
    else -> ToolVisual(ToolBoxIconKey.Tools, Color(0xFF7C4DFF))
}

private val CatalogFeedback.message: String get() = when (this) {
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
