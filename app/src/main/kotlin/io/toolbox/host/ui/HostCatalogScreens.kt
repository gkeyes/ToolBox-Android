package io.toolbox.host.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import io.toolbox.core.data.CatalogGroup
import io.toolbox.core.ui.component.ToolBoxSwitchSettingRow
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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import io.toolbox.core.ui.component.ToolBoxMotion
import io.toolbox.core.ui.component.ToolBoxStatusState
import io.toolbox.core.ui.component.ToolBoxStatusTone
import io.toolbox.core.ui.component.rememberToolBoxEntrance
import io.toolbox.core.ui.component.rememberToolBoxHaptics
import io.toolbox.core.ui.component.toolBoxEntrance
import io.toolbox.core.ui.component.toolBoxPressFeedback
import io.toolbox.core.ui.component.toolBoxShape
import io.toolbox.host.HostImportConfirmationKind
import io.toolbox.host.R
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogFeedback
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.tool.packagekit.lifecycle.PackageImportPhase
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.utils.overScrollVertical
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
    var openGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousHome by rememberSaveable { mutableStateOf(home) }
    val toolsById = remember(state.tools) { state.tools.associateBy { it.toolId } }
    val favoriteIds = remember(state.layout.favorites) { state.layout.favorites.toSet() }
    val selectedTool = selectedToolId?.let(toolsById::get)
    val drag = rememberCatalogHomeDragState(home && editing, listState, contentPadding)
    BackHandler(home && editing && selectedToolId == null && editingGroupId == null && openGroupId == null && !addingFavorites && !creatingGroup) {
        onEditingChange(false)
    }
    LaunchedEffect(home) {
        if (previousHome != home) {
            if (!home) onEditingChange(false)
            editingGroupId = null
            openGroupId = null
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
    val entrance = rememberToolBoxEntrance(if (home) "home" else "tools")
    io.toolbox.core.ui.component.ToolBoxTitleCollapseSource(listState)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val direction = LocalLayoutDirection.current
        val contentWidth = maxWidth - contentPadding.calculateStartPadding(direction) - contentPadding.calculateEndPadding(direction)
        val fontScale = LocalDensity.current.fontScale
        val columns = homeGridColumnCount((contentWidth - 32.dp).value, fontScale)
        val folderColumns = homeFolderColumnCount(contentWidth.value, fontScale)
        LazyColumn(
            userScrollEnabled = drag.active == null,
            state = listState,
            modifier = Modifier.fillMaxSize()
                .overScrollVertical()
                .testTag(if (home) "catalog_home_list" else "catalog_tools_list")
                .catalogDragSurface(drag, home && editing),
            contentPadding = contentPadding,
            overscrollEffect = null,
        ) {
            if (home && editing) item("organize-help") {
                Row(
                    Modifier.padding(bottom = 16.dp).fillMaxWidth()
                        .clip(toolBoxShape(18.dp))
                        .background(ToolBoxThemeTokens.colors.softPrimary)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolBoxIcon(ToolBoxIconKey.Drag, null, Modifier.size(20.dp), tint = ToolBoxThemeTokens.colors.primary)
                    Spacer(Modifier.width(10.dp))
                    AppText("长按拖动排序，点 − 移出常用，点分组可查看和排列工具，点 ··· 编辑分组", color = ToolBoxThemeTokens.colors.primary,
                        textStyle = ToolBoxThemeTokens.textStyles.metadata)
                }
            }
            if (!home) {
                item("search") {
                    ToolBoxSearchField(
                        value = state.query,
                        onValueChange = { onAction(CatalogAction.SetQuery(it)) },
                        placeholder = "搜索工具",
                        modifier = Modifier.toolBoxEntrance(entrance, 0),
                    )
                }
                item("installed-title") {
                    Box(Modifier.toolBoxEntrance(entrance, 1)) { CatalogListHeader(state, onAction) }
                }
                item("after-search") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
            }

            if (importState.working || importState.message != null) {
                item("import-feedback") {
                    FeedbackSurface(
                        modifier = Modifier.animateItem(
                            fadeInSpec = ToolBoxMotion.effects(),
                            placementSpec = ToolBoxMotion.ItemPlacement,
                            fadeOutSpec = ToolBoxMotion.effects(),
                        ).padding(bottom = ToolBoxThemeTokens.spacing.oneHalf),
                        message = if (importState.working) importState.progressMessage else requireNotNull(importState.message),
                        tone = importState.feedbackTone,
                        dismissible = !importState.working && !importState.succeeded,
                        onDismiss = onDismissImport,
                        onCancel = onCancelActiveImport.takeIf {
                            importState.working && importState.importPhase == PackageImportPhase.IMPORTING
                        },
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
                        modifier = Modifier.animateItem(
                            fadeInSpec = ToolBoxMotion.effects(),
                            placementSpec = ToolBoxMotion.ItemPlacement,
                            fadeOutSpec = ToolBoxMotion.effects(),
                        ).padding(bottom = ToolBoxThemeTokens.spacing.oneHalf),
                    )
                }
            }

            if (home && state.isLoaded && state.tools.isNotEmpty()) {
                catalogHomeSections(
                    state, toolsById, editing, columns, folderColumns, drag, onAction,
                    onEditGroup = { editingGroupId = it }, onOpenGroup = { openGroupId = it },
                    onAddFavorites = { addingFavorites = true },
                    onOptions = { selectedToolId = it },
                    runningContent = runningTools,
                )
            }

            when {
                !state.isLoaded -> item("loading") { CatalogStatusState("正在读取工具", loading = true) }
                state.tools.isEmpty() -> item("empty") {
                    Box(Modifier.toolBoxEntrance(entrance, 0)) { EmptyCatalogState(onImport, onInstallExamples) }
                }
                home -> Unit
                state.visibleTools.isEmpty() -> item("no-match") {
                    CatalogStatusState(
                        "没有匹配的工具",
                        icon = ToolBoxIconKey.Search,
                        detail = "换个关键词试试",
                        actionLabel = "清除搜索",
                        onAction = { onAction(CatalogAction.SetQuery("")) },
                        modifier = Modifier.animateItem(fadeInSpec = ToolBoxMotion.effects(), fadeOutSpec = null),
                    )
                }
                else -> itemsIndexed(
                    items = state.visibleTools,
                    key = { _, tool -> tool.toolId },
                    contentType = { _, _ -> "tool" },
                ) { index, tool ->
                    CatalogToolRow(
                        tool = tool,
                        onOpen = { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) },
                        onDetails = { selectedToolId = tool.toolId },
                        favorite = tool.toolId in favoriteIds,
                        first = index == 0,
                        last = index == state.visibleTools.lastIndex,
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = ToolBoxMotion.effects(),
                                placementSpec = ToolBoxMotion.ItemPlacement,
                                fadeOutSpec = ToolBoxMotion.effects(),
                            )
                            .toolBoxEntrance(entrance, index + 2),
                    )
                }
            }
            if (!home && state.isLoaded && state.visibleTools.isNotEmpty()) item("tools-footer") {
                AppText(
                    "长按工具可收藏、分组与管理；删除工具会一并清除其文件、权限、存储和后台任务。",
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    color = ToolBoxThemeTokens.colors.textSecondary,
                    textStyle = ToolBoxThemeTokens.textStyles.label.copy(fontSize = 13.sp, lineHeight = 18.sp),
                )
            }
        }
        CatalogDragPreview(drag)
    }

    ImportReplacementDialog(importState, onConfirmImport, onCancelImport)
    // The folder overlay sits below the option sheet and editors it can open.
    val openGroup = openGroupId?.let { id -> state.layout.groups.firstOrNull { it.id == id } }
    LaunchedEffect(state.isLoaded, openGroupId, openGroup) {
        if (state.isLoaded && openGroupId != null && openGroup == null) openGroupId = null
    }
    if (home && openGroup != null) CatalogGroupFolderOverlay(
        group = openGroup,
        members = openGroup.members.mapNotNull(toolsById::get),
        editing = editing,
        onAction = onAction,
        onOptions = { selectedToolId = it },
        onEdit = { openGroupId = null; editingGroupId = openGroup.id },
        onDismiss = { openGroupId = null },
    )
    if (addingFavorites) CatalogFavoritesPicker(state, onAction) { addingFavorites = false }
    if (selectedTool != null) CatalogToolOptions(selectedTool, state, onAction,
        onDismiss = { selectedToolId = null },
        onManage = { selectedToolId = null; openGroupId = null; onOpenDetails(selectedTool.toolId) },
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
    val entrance = rememberToolBoxEntrance("tool-detail")
    var groupsOpen by rememberSaveable(toolId) { mutableStateOf(false) }

    DetailScreen(title = "工具详情", onBack = onBack) { chromePadding ->
        LazyColumn(
            state = rememberTitleBoundListState(),
            modifier = Modifier
                .widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
                .fillMaxWidth()
                .fillMaxHeight()
                .overScrollVertical()
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
            overscrollEffect = null,
        ) {
            if (tool == null) {
                item("missing") {
                    CatalogStatusState("该工具已不存在", icon = ToolBoxIconKey.Tools, detail = "它可能已被删除或正在更新。")
                }
            } else {
                val favorite = tool.toolId in state.layout.favorites
                val memberOf = state.layout.groups.filter { tool.toolId in it.members }
                item("identity") {
                    ToolDetailHero(
                        tool = tool,
                        favorite = favorite,
                        onOpen = { onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId)) },
                        onToggleFavorite = { onAction(CatalogAction.SetFavorite(tool.toolId, !favorite)) },
                        onGroups = { groupsOpen = !groupsOpen },
                        modifier = Modifier.toolBoxEntrance(entrance, 0),
                    )
                }
                item("stats") {
                    ToolDetailStats(tool, memberOf.size, Modifier.padding(top = 20.dp).toolBoxEntrance(entrance, 1))
                }
                item("before-management") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.section)) }
                item("management-title") { SectionHeader("管理") }
                item("management-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
                item("management") {
                    ToolBoxGroupedSurface(Modifier.toolBoxEntrance(entrance, 2)) {
                        ToolBoxSettingRow(title = "权限", icon = ToolBoxIconKey.Shield, onClick = { onPermissions(tool.toolId) })
                        ToolBoxGroupDivider()
                        ToolBoxSettingRow(title = "后台任务", icon = ToolBoxIconKey.Clock, onClick = { onBackground(tool.toolId) })
                    }
                }
                item("before-organization") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.section)) }
                item("organization-title") { SectionHeader("收藏与分组") }
                item("organization-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
                item("organization") {
                    ToolBoxGroupedSurface(Modifier.toolBoxEntrance(entrance, 3)) {
                        ToolBoxSwitchSettingRow(
                            title = "收藏",
                            checked = favorite,
                            onCheckedChange = { onAction(CatalogAction.SetFavorite(tool.toolId, it)) },
                            summary = "显示在首页“常用”",
                            icon = ToolBoxIconKey.Star,
                            modifier = Modifier.testTag("tool_detail_favorite"),
                        )
                        ToolBoxSettingRow(
                            title = "加入分组",
                            summary = when {
                                state.layout.groups.isEmpty() -> "还没有分组"
                                memberOf.isEmpty() -> "未加入分组"
                                else -> memberOf.joinToString("、") { it.name }
                            },
                            icon = ToolBoxIconKey.Folder,
                            onClick = { groupsOpen = !groupsOpen },
                            modifier = Modifier.testTag("tool_detail_groups").semantics {
                                stateDescription = if (groupsOpen) "已展开" else "已收起"
                            },
                        )
                        AnimatedVisibility(
                            visible = groupsOpen,
                            enter = ToolBoxMotion.ExpandIn,
                            exit = ToolBoxMotion.CollapseOut,
                        ) {
                            Column(Modifier.fillMaxWidth().padding(start = 60.dp, bottom = 6.dp)) {
                                if (state.layout.groups.isEmpty()) {
                                    AppText("在首页菜单中选择“新建分组”来整理工具。", Modifier.padding(end = 16.dp, top = 4.dp, bottom = 8.dp),
                                        color = ToolBoxThemeTokens.colors.textSecondary, textStyle = ToolBoxThemeTokens.textStyles.metadata)
                                }
                                state.layout.groups.forEach { group ->
                                    ToolDetailGroupRow(
                                        group = group,
                                        selected = tool.toolId in group.members,
                                        onChecked = { selected ->
                                            onAction(CatalogAction.SetGroupMembership(group.id, tool.toolId, selected))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                item("before-information") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.section)) }
                item("information-title") { SectionHeader("信息") }
                item("information-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
                item("information") {
                    ToolBoxGroupedSurface(Modifier.toolBoxEntrance(entrance, 4)) {
                        ToolBoxValueRow(title = "版本", value = tool.versionName)
                        ToolBoxGroupDivider(startPadding = ToolBoxThemeTokens.spacing.oneHalf)
                        ToolBoxValueRow(title = "工具大小", value = tool.bundleBytes.fileSizeLabel())
                        ToolBoxGroupDivider(startPadding = ToolBoxThemeTokens.spacing.oneHalf)
                        ToolBoxValueRow(title = "最近打开", value = tool.lastOpenedAt.lastOpenedLabel())
                    }
                }
                item("before-delete") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.section)) }
                item("delete") {
                    Column(Modifier.toolBoxEntrance(entrance, 5)) {
                        ToolDetailDeleteRow { onAction(CatalogAction.RequestUninstall(tool.toolId)) }
                        AppText(
                            "工具文件、权限、存储和后台任务都会一并删除。",
                            Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                            color = ToolBoxThemeTokens.colors.textSecondary,
                            textStyle = ToolBoxThemeTokens.textStyles.label.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        )
                    }
                }
            }
        }

        if (confirmation != null) ToolBoxModalDialog(
            onDismissRequest = { onAction(CatalogAction.CancelUninstall) },
            title = "删除 ${confirmation.toolName}？",
        ) {
            confirmation.let {
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

/** App Store style hero: large icon, name and summary, with Open / Favorite / Groups capsules below. */
@Composable
private fun ToolDetailHero(
    tool: CatalogTool,
    favorite: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onGroups: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ToolBoxThemeTokens.colors
    val visual = tool.visual(colors.primary)
    val presentation = rememberCatalogToolPresentation(tool.toolId, tool.versionCode)
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(96.dp).clip(toolBoxShape(26.dp))) {
                CatalogToolArtwork(presentation.bitmap, visual, 96.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText(
                    text = tool.name,
                    modifier = Modifier.semantics { heading() },
                    maxLines = 2,
                    textStyle = ToolBoxThemeTokens.textStyles.detailTitle.copy(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold),
                )
                AppText(
                    text = presentation.description ?: "版本 ${tool.versionName}",
                    maxLines = 2,
                    color = colors.textSecondary,
                    textStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 15.sp, lineHeight = 20.sp),
                )
            }
        }
        // Full width below the identity row so the capsules never compete with the 96dp icon;
        // on narrow screens or large font scales they wrap onto a second line.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DetailCapsule(
                label = "打开",
                onClick = onOpen,
                container = colors.primary,
                content = colors.onPrimary,
                modifier = Modifier.semantics { contentDescription = "打开工具" },
            )
            DetailCapsule(
                label = if (favorite) "已收藏" else "收藏",
                onClick = onToggleFavorite,
                container = colors.surface,
                content = if (favorite) colors.warning else colors.textPrimary,
                icon = if (favorite) ToolBoxIconKey.StarFilled else ToolBoxIconKey.Star,
                modifier = Modifier.testTag("tool_detail_favorite_capsule").semantics {
                    stateDescription = if (favorite) "已收藏" else "未收藏"
                },
            )
            DetailCapsule(
                label = null,
                onClick = onGroups,
                container = colors.surface,
                content = colors.textPrimary,
                icon = ToolBoxIconKey.Folder,
                modifier = Modifier.semantics { contentDescription = "加入分组" },
            )
        }
    }
}

@Composable
private fun DetailCapsule(
    label: String?,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    icon: ToolBoxIconKey? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = toolBoxShape(ToolBoxThemeTokens.radii.full)
    val haptics = rememberToolBoxHaptics()
    Row(
        modifier
            .heightIn(min = 40.dp)
            .then(if (label == null) Modifier.width(40.dp) else Modifier)
            .toolBoxPressFeedback(interactionSource, shape = shape)
            .clip(shape)
            .background(container)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = if (label == null) 0.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) ToolBoxIcon(icon, null, Modifier.size(18.dp), tint = content)
        if (label != null) {
            AppText(label, color = content, maxLines = 1,
                textStyle = ToolBoxThemeTokens.textStyles.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** Horizontal information strip separated by hairlines, as on an App Store product page. */
@Composable
private fun ToolDetailStats(tool: CatalogTool, groupCount: Int, modifier: Modifier = Modifier) {
    val colors = ToolBoxThemeTokens.colors
    val stats = listOf(
        Triple("版本", tool.versionName, "版本号 ${tool.versionCode}"),
        Triple("大小", tool.bundleBytes.fileSizeLabel(), "工具包"),
        Triple("分组", groupCount.toString(), "个分组"),
        Triple("最近打开", tool.lastOpenedAt?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) } ?: "—",
            if (tool.lastOpenedAt == null) "尚未打开" else "日期"),
    )
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stats.forEachIndexed { index, (label, value, sub) ->
                if (index > 0) Box(Modifier.width(0.5.dp).height(40.dp).background(colors.divider))
                Column(
                    Modifier.widthIn(min = 88.dp).padding(horizontal = 6.dp).semantics(mergeDescendants = true) { },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    AppText(label, color = colors.textTertiary, maxLines = 1,
                        textStyle = ToolBoxThemeTokens.textStyles.label.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold))
                    AppText(value, color = colors.textSecondary, maxLines = 1,
                        textStyle = ToolBoxThemeTokens.textStyles.title.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold))
                    AppText(sub, color = colors.textTertiary, maxLines = 1,
                        textStyle = ToolBoxThemeTokens.textStyles.label.copy(fontSize = 12.sp, lineHeight = 15.sp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
    }
}

@Composable
private fun ToolDetailGroupRow(group: CatalogGroup, selected: Boolean, onChecked: (Boolean) -> Unit) {
    val colors = ToolBoxThemeTokens.colors
    val haptics = rememberToolBoxHaptics()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(selected, role = Role.Checkbox) { haptics.toggle(it); onChecked(it) }
            .testTag("tool_detail_group:" + group.id)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CatalogGroupStyleIcon(group, 24.dp, 14.dp)
        AppText(group.name, Modifier.weight(1f), maxLines = 1, textStyle = ToolBoxThemeTokens.textStyles.body)
        AppText("${group.members.size} 个", color = colors.textSecondary, textStyle = ToolBoxThemeTokens.textStyles.metadata)
        IosCheckCircle(selected)
    }
}

/** iOS selection circle: filled accent with a check, or a hairline ring. */
@Composable
internal fun IosCheckCircle(selected: Boolean, size: androidx.compose.ui.unit.Dp = 22.dp) {
    val colors = ToolBoxThemeTokens.colors
    val fill = androidx.compose.animation.animateColorAsState(
        if (selected) colors.primary else Color.Transparent, ToolBoxMotion.effects(), label = "check fill",
    )
    Box(
        Modifier.size(size).clip(CircleShape).drawBehind { drawRect(fill.value) }
            .then(if (selected) Modifier else Modifier.border(1.5.dp, colors.textTertiary, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) ToolBoxIcon(ToolBoxIconKey.Check, null, Modifier.size(size * 0.7f), tint = colors.onPrimary)
    }
}

@Composable
private fun ToolDetailDeleteRow(onClick: () -> Unit) {
    val colors = ToolBoxThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val shape = toolBoxShape(ToolBoxThemeTokens.radii.card)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .toolBoxPressFeedback(interactionSource, shape = shape, scale = ToolBoxMotion.RowPressScale)
            .clip(shape)
            .background(colors.surface)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(30.dp).clip(toolBoxShape(8.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFFF6B5E), Color(0xFFD70015)))),
            contentAlignment = Alignment.Center,
        ) {
            ToolBoxIcon(ToolBoxIconKey.Close, null, Modifier.size(18.dp), tint = Color.White)
        }
        AppText("删除工具", Modifier.weight(1f), color = colors.danger, textStyle = ToolBoxThemeTokens.textStyles.body)
    }
}

/** One row of the inset grouped "all tools" list; [first] / [last] round the card's ends. */
@Composable
internal fun CatalogToolRow(
    tool: CatalogTool,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    favorite: Boolean = false,
    first: Boolean = true,
    last: Boolean = true,
) {
    val radius = ToolBoxThemeTokens.radii.card
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp, topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp, bottomEnd = if (last) radius else 0.dp,
    )
    val colors = ToolBoxThemeTokens.colors
    val visual = tool.visual(colors.primary)
    val presentation = rememberCatalogToolPresentation(tool.toolId, tool.versionCode)
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = rememberToolBoxHaptics()
    Box(
        modifier.fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .testTag("tool_card:" + tool.toolId),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f)
                    .heightIn(min = 68.dp)
                    .toolBoxPressFeedback(interactionSource, scale = ToolBoxMotion.RowPressScale)
                    .testTag("tool_main:" + tool.toolId)
                    .combinedClickable(interactionSource = interactionSource, indication = null,
                        role = Role.Button, onClickLabel = "打开${tool.name}", onClick = onOpen,
                        onLongClickLabel = "收藏、分组与管理", onLongClick = {
                            haptics.longPress()
                            onDetails()
                        })
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CatalogToolArtwork(presentation.bitmap, visual, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(tool.name, Modifier.weight(1f, fill = false), maxLines = 1,
                            textStyle = ToolBoxThemeTokens.textStyles.title.copy(fontSize = 17.sp, lineHeight = 22.sp),
                            weight = FontWeight.SemiBold)
                        if (favorite) {
                            Spacer(Modifier.width(6.dp))
                            ToolBoxIcon(ToolBoxIconKey.StarFilled, "已收藏", Modifier.size(14.dp), tint = Color(0xFFF2A100))
                        }
                    }
                    // Reserve the same one-line summary slot while metadata loads or is absent.
                    AppText(
                        text = presentation.description ?: "版本 ${tool.versionName}",
                        maxLines = 1,
                        textStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = colors.textSecondary,
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
        if (!last) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 72.dp).fillMaxWidth().height(0.5.dp).background(colors.divider))
        }
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
    // Status banner: tone changes (progress -> success / error) cross-fade on the effects spring.
    val (toneContainer, toneContent) = io.toolbox.core.ui.component.toolBoxToneColors(
        when (tone) {
            FeedbackTone.Progress -> io.toolbox.core.ui.component.ToolBoxTone.Info
            FeedbackTone.Success -> io.toolbox.core.ui.component.ToolBoxTone.Success
            FeedbackTone.Neutral -> io.toolbox.core.ui.component.ToolBoxTone.Neutral
            FeedbackTone.Error -> io.toolbox.core.ui.component.ToolBoxTone.Danger
        },
    )
    val containerState = androidx.compose.animation.animateColorAsState(toneContainer, ToolBoxMotion.effects(), label = "feedback container")
    val content = androidx.compose.animation.animateColorAsState(toneContent, ToolBoxMotion.effects(), label = "feedback content").value
    val icon = when (tone) {
        FeedbackTone.Progress -> ToolBoxIconKey.Clock
        FeedbackTone.Success -> ToolBoxIconKey.Check
        FeedbackTone.Neutral -> ToolBoxIconKey.Close
        FeedbackTone.Error -> ToolBoxIconKey.Close
    }
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            if (tone != FeedbackTone.Progress) liveRegion = LiveRegionMode.Polite
        }.clip(toolBoxShape(ToolBoxThemeTokens.radii.control)).drawBehind { drawRect(containerState.value) }
            .heightIn(min = ToolBoxThemeTokens.sizes.touchTarget).padding(start = ToolBoxThemeTokens.spacing.oneHalf),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBoxIcon(icon = icon, contentDescription = null, tint = content)
        Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
        androidx.compose.animation.AnimatedContent(
            targetState = message,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                androidx.compose.animation.fadeIn(ToolBoxMotion.effects()) togetherWith
                    androidx.compose.animation.fadeOut(ToolBoxMotion.effects())
            },
            label = "feedback text",
        ) { text ->
            AppText(text = text, color = colors.textPrimary, textStyle = ToolBoxThemeTokens.textStyles.metadata)
        }
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
    // First run: one inset card with a stacked-icon illustration, headline, subhead and actions.
    val colors = ToolBoxThemeTokens.colors
    Column(
        Modifier.fillMaxWidth().testTag(HostTestTags.CatalogEmptyState).padding(top = ToolBoxThemeTokens.spacing.oneHalf)
            .clip(toolBoxShape(ToolBoxThemeTokens.radii.card))
            .background(colors.surface)
            .padding(start = 24.dp, end = 24.dp, top = 36.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(width = 132.dp, height = 96.dp).clearAndSetSemantics { }) {
            EmptyStateTile(Color(0xFFFFC93C), Color(0xFFF29900), -10f, Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 20.dp))
            EmptyStateTile(Color(0xFFB07CFF), Color(0xFF7B3FD0), 10f, Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 20.dp))
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(64.dp)
                    .clip(toolBoxShape(18.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF4A9BFF), Color(0xFF0A5BD6)))),
                contentAlignment = Alignment.Center,
            ) {
                ToolBoxIcon(ToolBoxIconKey.Tools, null, Modifier.size(32.dp), tint = Color.White)
            }
        }
        AppText("还没有工具", Modifier.semantics { heading() }, align = androidx.compose.ui.text.style.TextAlign.Center,
            textStyle = ToolBoxThemeTokens.textStyles.detailTitle.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold))
        AppText("先安装四个可直接使用的范例，或导入自己的 .tbx。", Modifier.widthIn(max = 280.dp),
            color = colors.textSecondary, align = androidx.compose.ui.text.style.TextAlign.Center,
            textStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 15.sp, lineHeight = 21.sp))
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToolBoxPrimaryButton("安装四个范例", onInstallExamples, Modifier.fillMaxWidth())
            ToolBoxSecondaryButton("导入工具", onImport, Modifier.fillMaxWidth())
        }
        AppText("四个范例会随 App 一起安装，可随时删除。", color = colors.textSecondary,
            align = androidx.compose.ui.text.style.TextAlign.Center,
            textStyle = ToolBoxThemeTokens.textStyles.label.copy(fontSize = 13.sp, lineHeight = 18.sp))
    }
}

@Composable
private fun EmptyStateTile(top: Color, bottom: Color, rotation: Float, modifier: Modifier) {
    Box(
        modifier.graphicsLayer { rotationZ = rotation }.size(56.dp)
            .clip(toolBoxShape(16.dp))
            .background(Brush.verticalGradient(listOf(top, bottom))),
    )
}

@Composable
internal fun CatalogStatusState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ToolBoxIconKey? = null,
    detail: String? = null,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ToolBoxStatusState(
        title = message,
        modifier = modifier,
        icon = icon,
        message = detail,
        tone = if (loading) ToolBoxStatusTone.Loading else ToolBoxStatusTone.Neutral,
        actionLabel = actionLabel,
        onAction = onAction,
    )
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
