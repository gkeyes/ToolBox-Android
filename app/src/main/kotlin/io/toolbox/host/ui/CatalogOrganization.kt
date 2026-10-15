package io.toolbox.host.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.toolbox.core.data.CatalogGroup
import io.toolbox.core.data.CatalogSort
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.*
import io.toolbox.core.ui.component.toolBoxGlassPress
import io.toolbox.core.ui.component.ProvideToolBoxWindowButtonBackdrop
import io.toolbox.core.ui.component.toolBoxClearGlass

internal val CatalogSort.label: String get() = when (this) {
    CatalogSort.NAME -> "名称首字母"
    CatalogSort.INSTALLED -> "安装时间"
    CatalogSort.LAST_OPENED -> "最后打开"
}

// ---------------------------------------------------------------------------------------------
// Group style: a palette colour and an icon, stored on CatalogGroup as stable string keys.
// ---------------------------------------------------------------------------------------------

internal data class CatalogGroupColor(val key: String, val label: String, val top: Color, val bottom: Color)

internal data class CatalogGroupGlyph(val icon: ToolBoxIconKey, val label: String) {
    val key: String get() = icon.name
}

internal val CatalogGroupColors = listOf(
    CatalogGroupColor("blue", "蓝色", Color(0xFF4A9BFF), Color(0xFF0A5BD6)),
    CatalogGroupColor("indigo", "靛蓝", Color(0xFF7C82FF), Color(0xFF4B4FD6)),
    CatalogGroupColor("purple", "紫色", Color(0xFFB07CFF), Color(0xFF7B3FD0)),
    CatalogGroupColor("orange", "橙色", Color(0xFFFF8A3D), Color(0xFFE8590C)),
    CatalogGroupColor("red", "红色", Color(0xFFFF6B5E), Color(0xFFD7261B)),
    CatalogGroupColor("pink", "粉色", Color(0xFFFF7AA8), Color(0xFFE0306A)),
    CatalogGroupColor("green", "绿色", Color(0xFF3DD17A), Color(0xFF1E8E3E)),
    CatalogGroupColor("teal", "青色", Color(0xFF2ECBCB), Color(0xFF0B8A8A)),
)

internal val CatalogGroupGlyphs = listOf(
    CatalogGroupGlyph(ToolBoxIconKey.Folder, "文件夹"),
    CatalogGroupGlyph(ToolBoxIconKey.Tools, "工具"),
    CatalogGroupGlyph(ToolBoxIconKey.Star, "星标"),
    CatalogGroupGlyph(ToolBoxIconKey.Code, "代码"),
    CatalogGroupGlyph(ToolBoxIconKey.Calculator, "计算"),
    CatalogGroupGlyph(ToolBoxIconKey.Note, "笔记"),
    CatalogGroupGlyph(ToolBoxIconKey.Globe, "网络"),
    CatalogGroupGlyph(ToolBoxIconKey.Notifications, "通知"),
    CatalogGroupGlyph(ToolBoxIconKey.Clock, "时间"),
    CatalogGroupGlyph(ToolBoxIconKey.Shield, "安全"),
    CatalogGroupGlyph(ToolBoxIconKey.Palette, "设计"),
    CatalogGroupGlyph(ToolBoxIconKey.Device, "设备"),
)

/** Unknown or missing keys (groups saved before styles existed) use the default folder style. */
internal fun catalogGroupColor(key: String?): CatalogGroupColor =
    CatalogGroupColors.firstOrNull { it.key == key } ?: CatalogGroupColors.first()

internal fun catalogGroupGlyph(key: String?): CatalogGroupGlyph =
    CatalogGroupGlyphs.firstOrNull { it.key == key } ?: CatalogGroupGlyphs.first()

@Composable
internal fun CatalogGroupStyleIcon(
    colorKey: String?,
    iconKey: String?,
    size: Dp,
    iconSize: Dp = size * 0.55f,
    modifier: Modifier = Modifier,
) {
    val color = catalogGroupColor(colorKey)
    Box(
        modifier.size(size)
            .clip(toolBoxShape(size * 0.27f))
            .background(Brush.verticalGradient(listOf(color.top, color.bottom))),
        contentAlignment = Alignment.Center,
    ) {
        ToolBoxIcon(catalogGroupGlyph(iconKey).icon, null, Modifier.size(iconSize), tint = Color.White)
    }
}

@Composable
internal fun CatalogGroupStyleIcon(group: CatalogGroup, size: Dp, iconSize: Dp = size * 0.55f, modifier: Modifier = Modifier) =
    CatalogGroupStyleIcon(group.color, group.icon, size, iconSize, modifier)

// ---------------------------------------------------------------------------------------------
// Home sections
// ---------------------------------------------------------------------------------------------

@Composable
internal fun HomeSectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(title, Modifier.weight(1f).semantics { heading() }, maxLines = 1,
            textStyle = ToolBoxThemeTokens.textStyles.title.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold))
        if (trailing != null) {
            AppText(trailing, color = ToolBoxThemeTokens.colors.textSecondary,
                textStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 15.sp, lineHeight = 20.sp))
        }
        if (action != null) ToolBoxTextButton(action, onAction, outlined = false)
    }
}

internal fun homeGridColumnCount(availableWidth: Float, fontScale: Float): Int =
    ((availableWidth + 12f) / (72f * fontScale.coerceAtLeast(1f) + 12f)).toInt().coerceAtLeast(1)

/**
 * Groups are one-line capsules (two full icons, then a 2x2 cluster); a phone fits two per row
 * like iOS home-screen widgets, a narrow phone one, wider windows up to four. Icon sizes do not
 * follow the font scale, so neither does the count.
 */
internal fun homeFolderColumnCount(availableWidth: Float, @Suppress("UNUSED_PARAMETER") fontScale: Float): Int =
    ((availableWidth + FOLDER_GAP) / (GROUP_CAPSULE_MIN_WIDTH + FOLDER_GAP)).toInt().coerceIn(1, 4)

private const val GROUP_CAPSULE_MIN_WIDTH = 150f
private const val FOLDER_GAP = 12f

internal fun LazyListScope.catalogHomeSections(
    state: CatalogUiState,
    toolsById: Map<String, CatalogTool>,
    editing: Boolean,
    columns: Int,
    folderColumns: Int,
    drag: CatalogHomeDragState,
    onAction: (CatalogAction) -> Unit,
    onEditGroup: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onAddFavorites: () -> Unit,
    onOptions: (String) -> Unit,
    runningContent: @Composable () -> Unit = {},
) {
    // Running tools first, as compact capsules; they render nothing while no session runs.
    item("running-tools", contentType = "running-tools") { runningContent() }

    val favorites = state.layout.favorites.mapNotNull(toolsById::get)
    if (editing) {
        item("favorites-heading") { HomeSectionHeader("常用", "添加", onAddFavorites) }
        if (favorites.isEmpty()) item("favorites-empty") {
            HomeEmptySection("把常用工具放在首页", "添加收藏", onAddFavorites)
        }
        homeToolGrid(
            favorites, "favorite", "favorites", columns, editing, drag, onAction, onOptions,
            surface = true,
            onRemove = { tool -> onAction(CatalogAction.SetFavorite(tool.toolId, false)) },
            onMove = { tool, offset -> onAction(CatalogAction.MoveFavorite(tool.toolId, offset)) },
        )
    } else {
        val shortcuts = homeShortcuts(favorites, state.recentTools)
        item("shortcuts-heading") { HomeSectionHeader("常用") }
        if (shortcuts.isEmpty()) item("favorites-empty") {
            HomeEmptySection("把常用工具放在首页", "添加收藏", onAddFavorites)
        }
        homeToolGrid(
            shortcuts, "favorite", "shortcuts", columns, false, drag, onAction, onOptions,
            surface = true,
            onMove = { _, _ -> },
        )
    }

    val groups = state.layout.groups
    if (groups.isNotEmpty() || editing) item("groups-heading") {
        Column(Modifier.homeItemMotion(this, editing)) {
            Spacer(Modifier.height(HOME_SECTION_GAP))
            HomeSectionHeader("分组", trailing = if (groups.isNotEmpty()) "${groups.size} 个" else null)
        }
    }
    if (editing && groups.isEmpty()) item("groups-empty") {
        HomeEmptySection("按用途整理工具", "新建分组", { onEditGroup("") })
    }
    val cells = if (groups.isEmpty()) 0 else groups.size + if (editing) 1 else 0
    val rows = (cells + folderColumns - 1) / folderColumns
    items(rows, key = { "group-row:$it" }, contentType = { "group-row" }) { row ->
        Row(
            Modifier.homeItemMotion(this, editing).fillMaxWidth()
                .padding(bottom = if (row < rows - 1) FOLDER_GAP.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(FOLDER_GAP.dp),
        ) {
            repeat(folderColumns) { column ->
                val index = row * folderColumns + column
                val group = groups.getOrNull(index)
                when {
                    group != null -> key(group.id) {
                        CatalogGroupFolder(
                            group = group,
                            members = group.members.mapNotNull(toolsById::get),
                            editing = editing, index = index, total = groups.size, drag = drag,
                            onOpen = { onOpenGroup(group.id) },
                            onEdit = { onEditGroup(group.id) },
                            onMove = { onAction(CatalogAction.MoveGroup(group.id, it)) },
                            onLaunch = { onAction(CatalogAction.RequestRuntimeLaunch(it.toolId)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    editing && index == groups.size -> NewGroupFolder({ onEditGroup("") }, Modifier.weight(1f))
                    else -> Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private const val HOME_SHORTCUT_LIMIT = 8

/** Every favorite stays on Home (the picker has no cap); recent tools only fill the free slots up to the limit. */
internal fun homeShortcuts(favorites: List<CatalogTool>, recent: List<CatalogTool>): List<CatalogTool> {
    val favoriteIds = favorites.mapTo(HashSet(), CatalogTool::toolId)
    val fill = recent.filterNot { it.toolId in favoriteIds }.distinctBy(CatalogTool::toolId)
    return favorites + fill.take(maxOf(0, HOME_SHORTCUT_LIMIT - favorites.size))
}
private val HOME_SECTION_GAP = 24.dp

/** Lazy rows retain one continuous card surface without nesting another vertical scroll container. */
internal fun LazyListScope.homeToolGrid(
    tools: List<CatalogTool>,
    prefix: String,
    collection: String,
    columns: Int,
    editing: Boolean,
    drag: CatalogHomeDragState,
    onAction: (CatalogAction) -> Unit,
    onOptions: (String) -> Unit,
    surface: Boolean = false,
    iconSize: Dp = 60.dp,
    onOpen: (CatalogTool) -> Unit = { onAction(CatalogAction.RequestRuntimeLaunch(it.toolId)) },
    onRemove: ((CatalogTool) -> Unit)? = null,
    onMove: (CatalogTool, Int) -> Unit,
) {
    val rows = ((tools.size.toLong() + columns - 1) / columns).toInt()
    val rowPrefix = if (prefix == "favorite") "favorite-row" else "member-row:" + prefix.removePrefix("member:")
    items(rows, key = { rowPrefix + ":" + it }, contentType = { "home-grid-row" }) { row ->
        val first = row == 0
        val last = row == rows - 1
        Row(
            Modifier.homeItemMotion(this, editing).fillMaxWidth()
                .then(if (surface) Modifier.groupSurface(top = first, bottom = last) else Modifier)
                .padding(
                    start = if (surface) 12.dp else 0.dp,
                    end = if (surface) 12.dp else 0.dp,
                    top = if (surface && first) 18.dp else 0.dp,
                    bottom = if (!last) 16.dp else if (surface) 14.dp else 0.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(columns) { column ->
                val index = row * columns + column
                val tool = tools.getOrNull(index)
                if (tool == null) Spacer(Modifier.weight(1f))
                else key(tool.toolId) {
                    CatalogHomeTile(
                        tool, editing,
                        onOpen = { onOpen(tool) },
                        onOptions = { onOptions(tool.toolId) },
                        labelMaxLines = 1,
                        iconSize = iconSize,
                        labelFontSize = 12.sp,
                        labelLineHeight = 16.sp,
                        labelSpacing = 6.dp,
                        onRemove = onRemove?.let { remove -> { remove(tool) } },
                        modifier = Modifier.weight(1f).testTag(prefix + ":" + tool.toolId)
                            .catalogDragTarget(drag, prefix + ":" + tool.toolId, collection, index, tools.size, tool.name, tool,
                                enabled = editing, onMove = { onMove(tool, it) }),
                        onMoveBefore = if (editing && index > 0) ({ onMove(tool, -1) }) else null,
                        onMoveAfter = if (editing && index < tools.lastIndex) ({ onMove(tool, 1) }) else null,
                    )
                }
            }
        }
    }
}

/**
 * Inserted and removed rows fade and the content below springs into place.
 * Disabled while organizing: the drag controller measures item positions directly.
 */
private fun Modifier.homeItemMotion(scope: LazyItemScope, editing: Boolean): Modifier =
    if (editing) this else with(scope) {
        this@homeItemMotion.animateItem(
            fadeInSpec = ToolBoxMotion.effects(),
            placementSpec = ToolBoxMotion.ItemPlacement,
            fadeOutSpec = ToolBoxMotion.effects(),
        )
    }

@Composable
private fun Modifier.groupSurface(top: Boolean = false, bottom: Boolean = false): Modifier {
    val radius = ToolBoxThemeTokens.radii.card
    val shape = RoundedCornerShape(
        topStart = if (top) radius else 0.dp, topEnd = if (top) radius else 0.dp,
        bottomStart = if (bottom) radius else 0.dp, bottomEnd = if (bottom) radius else 0.dp,
    )
    return fillMaxWidth().clip(shape).background(ToolBoxThemeTokens.colors.surface, shape)
}

// ---------------------------------------------------------------------------------------------
// Folders
// ---------------------------------------------------------------------------------------------

@Composable
private fun CatalogGroupFolder(
    group: CatalogGroup,
    members: List<CatalogTool>,
    editing: Boolean,
    index: Int,
    total: Int,
    drag: CatalogHomeDragState,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onLaunch: (CatalogTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ToolBoxThemeTokens.colors
    val style = catalogGroupColor(group.color)
    val interactionSource = remember { MutableInteractionSource() }
    val editLabel = "编辑分组" + group.name
    // Shared by the capsule and the name below it; the shared source keeps press feedback on the capsule.
    val openGesture = if (editing) Modifier.clickable(interactionSource = interactionSource, indication = null,
        role = Role.Button, onClickLabel = "打开分组", onClick = onOpen)
    else Modifier.combinedClickable(interactionSource = interactionSource, indication = null,
        role = Role.Button, onClickLabel = "打开分组", onClick = onOpen,
        onLongClick = onEdit, onLongClickLabel = editLabel)
    Column(
        modifier
            .catalogDragTarget(drag, "group:" + group.id, "groups", index, total, group.name,
                enabled = editing, onMove = onMove)
            .testTag("group:" + group.id),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val metrics = groupCapsuleMetrics(maxWidth)
            val capsuleShape = RoundedCornerShape(metrics.radius)
            Box(
                Modifier.fillMaxWidth().height(metrics.height)
                    .toolBoxPressFeedback(interactionSource, shape = capsuleShape)
                    .clip(capsuleShape)
                    .background(lerp(colors.surface, style.bottom, 0.06f))
                    .testTag("catalog_group:" + group.id)
                    .semantics {
                        contentDescription = "分组${group.name}，${members.size} 个工具"
                        customActions = buildList {
                            add(CustomAccessibilityAction(editLabel) { onEdit(); true })
                            if (editing && index > 0) add(CustomAccessibilityAction("前移") { onMove(-1); true })
                            if (editing && index < total - 1) add(CustomAccessibilityAction("后移") { onMove(1); true })
                        }
                    }
                    .then(openGesture)
                    .padding(horizontal = metrics.padding),
                contentAlignment = Alignment.Center,
            ) {
                CatalogGroupCapsuleContent(group, members, metrics.slot, onLaunch = if (editing) null else onLaunch)
            }
            if (editing) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(44.dp)
                        .clickable(role = Role.Button, onClick = onEdit)
                        .semantics { contentDescription = editLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(colors.surfaceMuted)
                            .border(0.5.dp, colors.divider, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        ToolBoxIcon(ToolBoxIconKey.More, null, Modifier.size(16.dp), tint = colors.textPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            // The capsule already exposes the open action to TalkBack; tapping the name opens it too.
            Modifier.fillMaxWidth().clearAndSetSemantics { }.then(openGesture).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // Narrow three-up folders: icon + name only; the count stays in the folder's description.
            CatalogGroupStyleIcon(group, 14.dp, 10.dp)
            Spacer(Modifier.width(5.dp))
            AppText(group.name, Modifier.weight(1f, fill = false), maxLines = 1,
                textStyle = ToolBoxThemeTokens.textStyles.label.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

internal class GroupCapsuleMetrics(val slot: Dp, val padding: Dp, val height: Dp, val radius: Dp)

/** Three equal slots (two icons and the 2x2 cluster) inside a capsule of the given width. */
internal fun groupCapsuleMetrics(width: Dp): GroupCapsuleMetrics {
    val padding = 10.dp
    val slot = ((width - padding * 2 - GROUP_SLOT_GAP * 2) / 3).coerceIn(36.dp, 56.dp)
    val height = slot + padding * 2
    return GroupCapsuleMetrics(slot, padding, height, height / 2)
}

private val GROUP_SLOT_GAP = 10.dp

/**
 * One line: the first two tools as full icons that open the tool, then tools three onward as
 * quarter-size icons in the third slot; past six tools the last quarter shows "+N". An empty
 * group shows its style icon. Taps outside the two full icons open the group.
 */
@Composable
private fun CatalogGroupCapsuleContent(group: CatalogGroup, members: List<CatalogTool>, slot: Dp, onLaunch: ((CatalogTool) -> Unit)?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (members.isEmpty()) {
            Box(Modifier.size(slot), contentAlignment = Alignment.Center) { CatalogGroupStyleIcon(group, slot) }
            Spacer(Modifier.size(slot))
            Spacer(Modifier.size(slot))
            return@Row
        }
        repeat(2) { position ->
            val tool = members.getOrNull(position)
            Box(Modifier.size(slot), contentAlignment = Alignment.Center) {
                if (tool != null) {
                    Box(
                        if (onLaunch == null) Modifier.clearAndSetSemantics { }
                        else Modifier.clip(toolBoxShape(slot * 0.24f))
                            .clickable(role = Role.Button, onClickLabel = "打开" + tool.name) { onLaunch(tool) }
                            .semantics { contentDescription = tool.name }
                            .testTag("catalog_group_tool:" + tool.toolId),
                    ) { PreviewGlyph(tool, slot) }
                }
            }
        }
        Box(Modifier.size(slot).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            val rest = members.drop(2)
            if (rest.isNotEmpty()) PreviewCluster(rest, slot)
        }
    }
}

@Composable
private fun PreviewGlyph(tool: CatalogTool, size: Dp) {
    CatalogToolGlyph(toolId = tool.toolId, versionCode = tool.versionCode,
        visual = tool.visual(ToolBoxThemeTokens.colors.primary), size = size)
}

@Composable
private fun PreviewCluster(tools: List<CatalogTool>, size: Dp) {
    val gap = 4.dp
    val tiny = (size - gap) / 2
    // Four quarters; with more than four, the last quarter counts the tools not shown.
    val shown = if (tools.size > 4) tools.take(3) else tools.take(4)
    val hidden = tools.size - shown.size
    Column(Modifier.size(size), verticalArrangement = Arrangement.spacedBy(gap)) {
        repeat(2) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(2) { column ->
                    val slot = row * 2 + column
                    Box(Modifier.size(tiny), contentAlignment = Alignment.Center) {
                        when {
                            slot < shown.size -> PreviewGlyph(shown[slot], tiny)
                            slot == 3 && hidden > 0 -> Box(
                                Modifier.fillMaxSize().clip(toolBoxShape(tiny * 0.26f))
                                    .background(ToolBoxThemeTokens.colors.surfaceMuted),
                                contentAlignment = Alignment.Center,
                            ) {
                                AppText("+$hidden", maxLines = 1, color = ToolBoxThemeTokens.colors.textSecondary,
                                    textStyle = ToolBoxThemeTokens.textStyles.label.copy(
                                        fontSize = with(LocalDensity.current) { (tiny * 0.38f).toSp() },
                                        lineHeight = with(LocalDensity.current) { (tiny * 0.46f).toSp() },
                                        fontWeight = FontWeight.SemiBold,
                                    ))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewGroupFolder(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ToolBoxThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    BoxWithConstraints(modifier) {
        val metrics = groupCapsuleMetrics(maxWidth)
        val shape = RoundedCornerShape(metrics.radius)
        Row(
            Modifier.fillMaxWidth().height(metrics.height)
                .toolBoxGlassPress(interactionSource, shape = shape)
                .toolBoxClearGlass(shape)
                .clip(shape)
                .border(1.5.dp, colors.primary.copy(alpha = 0.45f), shape)
                .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
                .testTag("catalog_home_new_group_tile"),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBoxIcon(ToolBoxIconKey.Add, null, Modifier.size(24.dp), tint = colors.primary)
            AppText("新建分组", color = colors.primary, maxLines = 1,
                textStyle = ToolBoxThemeTokens.textStyles.label.copy(fontSize = 13.sp, lineHeight = 18.sp))
        }
    }
}

/** How long the group overlay waits for its page copy before opening with solid glass. */
private const val GlassCopyWaitMs = 250L

/** App Library style glass overlay listing one group's tools; organizing reorders them here. */
@Composable
internal fun CatalogGroupFolderOverlay(
    group: CatalogGroup,
    members: List<CatalogTool>,
    editing: Boolean,
    onAction: (CatalogAction) -> Unit,
    onOptions: (String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    onWindowView: (android.view.View?) -> Unit = {},
) {
    val colors = ToolBoxThemeTokens.colors
    val materials = ToolBoxThemeTokens.materials
    val dark = colors.surface.luminance() < 0.5f
    // Read outside the Dialog: this is the home page's view, which the panel glass copies.
    val glass = rememberToolBoxWindowGlass(LocalView.current)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val folderView = LocalView.current
        val window = (folderView.parent as? DialogWindowProvider)?.window
        DisposableEffect(folderView) {
            onWindowView(folderView)
            onDispose { onWindowView(null) }
        }
        // Liquid glass: the panel refracts a copy of the home page and carries all of the group's
        // text; around it the same copy is frosted under a light veil, so the home page reads as
        // colour and shape, never as text beside the group's own. With 实色材质 on, the veil is
        // near-solid and the panel opaque.
        val seeThrough = materials.realBlurEnabled
        DisposableEffect(window) {
            if (window == null) return@DisposableEffect onDispose { }
            val previousDim = window.attributes.dimAmount
            window.setDimAmount(0f)
            onDispose { window.setDimAmount(previousDim) }
        }
        val entrance = remember { Animatable(0f) }
        LaunchedEffect(entrance) {
            // Opens once the page copy is in, so the glass doesn't flip from solid mid-animation.
            if (seeThrough) withTimeoutOrNull(GlassCopyWaitMs) { snapshotFlow { glass.ready }.first { it } }
            entrance.animateTo(1f, ToolBoxMotion.DialogEnter)
        }
        val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
        val panelScale = { 0.6f + 0.4f * entrance.value }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = entrance.value.coerceIn(0f, 1f) }
                    .toolBoxWindowFrost(
                        glass,
                        tint = colors.background.copy(alpha = 0.3f),
                        fallback = colors.background.copy(alpha = 0.9f),
                    ),
            )
            Box(
                Modifier.fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = "关闭分组", onClick = onDismiss)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                val panelRadius = ToolBoxThemeTokens.radii.sheet
                val panelShape = toolBoxShape(panelRadius)
                Column(
                    Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        .graphicsLayer {
                            alpha = entrance.value.coerceIn(0f, 1f)
                            val scale = panelScale()
                            scaleX = scale
                            scaleY = scale
                        }
                        // Taps on the panel itself must not reach the dismissing scrim.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .semantics { paneTitle = "分组 " + group.name }
                        .testTag("catalog_group_folder")
                        .shadow(
                            elevation = 24.dp,
                            shape = panelShape,
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = if (dark) 0.6f else 0.22f),
                            spotColor = Color.Black.copy(alpha = if (dark) 0.6f else 0.22f),
                        )
                        .clip(panelShape)
                        // The copy is drawn unscaled, so it stays in register with the page as the panel zooms.
                        .toolBoxWindowGlassEffect(glass, panelRadius, layerScale = panelScale) { entrance.value }
                        .border(0.5.dp, materials.overlayBorder, panelShape)
                        .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CatalogGroupStyleIcon(group, 30.dp, 18.dp)
                        // Two lines: at 320dp the two round buttons leave only ~4 large glyphs per line.
                        AppText(group.name, Modifier.weight(1f).semantics { heading() }, maxLines = 2,
                            textStyle = ToolBoxThemeTokens.textStyles.title.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold))
                        FolderRoundButton(glass, ToolBoxIconKey.More, "编辑分组" + group.name, onEdit, Modifier.testTag("catalog_group_folder_edit"), panelScale)
                        FolderRoundButton(glass, ToolBoxIconKey.Close, "关闭分组", onDismiss, Modifier.testTag("catalog_group_folder_close"), panelScale)
                    }
                    Box(
                        // Takes only what the header and footer leave, so a two-line title in a short
                        // window shrinks the scrolling list instead of pushing the buttons off screen.
                        Modifier.weight(1f, fill = false).fillMaxWidth(),
                    ) {
                        if (members.isEmpty()) {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CatalogGroupStyleIcon(group, 64.dp, 32.dp)
                                AppText("这个分组还没有工具", color = colors.textPrimary.copy(alpha = 0.8f), align = TextAlign.Center,
                                    textStyle = ToolBoxThemeTokens.textStyles.metadata)
                                ProvideToolBoxWindowButtonBackdrop(glass, motion = { entrance.value }) {
                                    ToolBoxTextButton("添加工具", onEdit, Modifier.testTag("catalog_group_folder_add"), outlined = false)
                                }
                            }
                        } else {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val columns = homeGridColumnCount(maxWidth.value, LocalDensity.current.fontScale).coerceIn(1, 3)
                                val listState = rememberLazyListState()
                                val drag = rememberCatalogHomeDragState(editing, listState, PaddingValues(0.dp))
                                Box(Modifier.fillMaxWidth()) {
                                    LazyColumn(
                                        state = listState,
                                        userScrollEnabled = drag.active == null,
                                        modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight)
                                            .testTag("catalog_group_folder_list")
                                            .catalogDragSurface(drag, editing),
                                    ) {
                                        homeToolGrid(
                                            members, "member:" + group.id, "members:" + group.id, columns, editing, drag,
                                            onAction, onOptions,
                                            iconSize = 60.dp,
                                            onOpen = { tool ->
                                                onDismiss()
                                                onAction(CatalogAction.RequestRuntimeLaunch(tool.toolId))
                                            },
                                            onMove = { tool, offset -> onAction(CatalogAction.MoveMember(group.id, tool.toolId, offset)) },
                                        )
                                    }
                                    CatalogDragPreview(drag)
                                }
                            }
                        }
                    }
                    AppText(
                        if (editing) "长按拖动排序，点击工具可收藏、分组与管理" else "长按工具可收藏、分组与管理",
                        Modifier.fillMaxWidth(), color = colors.textPrimary.copy(alpha = 0.85f), align = TextAlign.Center,
                        textStyle = ToolBoxThemeTokens.textStyles.label.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRoundButton(
    glass: ToolBoxWindowGlass,
    icon: ToolBoxIconKey,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    layerScale: () -> Float = { 1f },
) {
    val colors = ToolBoxThemeTokens.colors
    val materials = ToolBoxThemeTokens.materials
    val interactionSource = remember { MutableInteractionSource() }
    // Liquid glass disc over the same page copy as the panel: a clearer lens with its own rim and
    // highlight, so it stands out from the panel glass around it.
    Box(
        modifier.size(44.dp)
            .toolBoxGlassPress(interactionSource)
            .shadow(6.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .toolBoxWindowGlassEffect(
                glass, 22.dp, blurRadius = 16.dp,
                fill = materials.glassTint,
                fallback = materials.glassTint.copy(alpha = 0.92f).compositeOver(colors.background),
                layerScale = layerScale,
            )
            .border(0.75.dp, materials.glassBorder, CircleShape)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ToolBoxIcon(icon, null, Modifier.size(18.dp), tint = colors.textPrimary)
    }
}

// ---------------------------------------------------------------------------------------------
// Tiles
// ---------------------------------------------------------------------------------------------

@Composable
internal fun CatalogHomeTile(
    tool: CatalogTool,
    editing: Boolean,
    onOpen: () -> Unit,
    onOptions: () -> Unit,
    showLabel: Boolean = true,
    labelMaxLines: Int = 1,
    iconSize: Dp = 56.dp,
    labelFontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    labelLineHeight: androidx.compose.ui.unit.TextUnit = 16.sp,
    labelSpacing: Dp = 6.dp,
    modifier: Modifier = Modifier,
    onMoveBefore: (() -> Unit)? = null,
    onMoveAfter: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = ToolBoxThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = ToolBoxMotion.pressSpec(pressed),
        label = "home tool press",
    )
    Column(
        modifier.heightIn(min = 48.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .semantics(mergeDescendants = true) {
                contentDescription = tool.name
                customActions = buildList {
                    add(CustomAccessibilityAction("收藏、分组与管理") { onOptions(); true })
                    onRemove?.let { add(CustomAccessibilityAction("移出常用") { it(); true }) }
                    onMoveBefore?.let { add(CustomAccessibilityAction("前移") { it(); true }) }
                    onMoveAfter?.let { add(CustomAccessibilityAction("后移") { it(); true }) }
                }
            }
            .then(
                if (editing) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "收藏、分组与管理",
                        onClick = onOptions,
                    )
                } else {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "打开" + tool.name,
                        onClick = onOpen,
                        onLongClickLabel = "收藏、分组与管理",
                        onLongClick = onOptions,
                    )
                },
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.testTag("catalog_home_icon:" + tool.toolId)) {
            CatalogToolGlyph(toolId = tool.toolId, versionCode = tool.versionCode,
                visual = tool.visual(colors.primary), size = iconSize)
            if (editing) {
                val remove = onRemove
                Box(
                    Modifier.align(if (remove != null) Alignment.TopStart else Alignment.TopEnd)
                        .offset(x = if (remove != null) (-6).dp else 6.dp, y = (-6).dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceMuted)
                        .border(0.5.dp, colors.divider, CircleShape)
                        .then(if (remove != null) Modifier.clickable(role = Role.Button, onClickLabel = "移出常用", onClick = remove)
                            .semantics { contentDescription = "移出常用" } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (remove != null) Box(Modifier.size(width = 10.dp, height = 2.dp).background(colors.textPrimary, CircleShape))
                    else ToolBoxIcon(ToolBoxIconKey.More, null, Modifier.size(15.dp), tint = colors.textPrimary)
                }
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(labelSpacing))
            AppText(tool.name, maxLines = labelMaxLines, align = TextAlign.Center,
                textStyle = ToolBoxThemeTokens.textStyles.label.copy(
                    fontSize = labelFontSize,
                    lineHeight = labelLineHeight,
                ))
        }
    }
}

@Composable
private fun HomeEmptySection(message: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(toolBoxShape(ToolBoxThemeTokens.radii.card)).background(ToolBoxThemeTokens.colors.surface)
        .heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText(message, Modifier.weight(1f), color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata)
        ToolBoxTextButton(action, onAction, outlined = false)
    }
}
