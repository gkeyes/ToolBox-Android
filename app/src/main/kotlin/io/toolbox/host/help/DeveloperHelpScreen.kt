package io.toolbox.host.help

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxDisclosureRow
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.host.settings.SettingsIconTint
import io.toolbox.host.settings.SettingsSwitchRow
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.CatalogStatusState
import io.toolbox.host.ui.mergePadding
import io.toolbox.host.ui.rememberTitleBoundListState
import io.toolbox.core.ui.component.ToolBoxContinuousShape
import io.toolbox.core.ui.component.ToolBoxMotion
import io.toolbox.core.ui.component.rememberToolBoxEntrance
import io.toolbox.core.ui.component.toolBoxEntrance
import io.toolbox.core.ui.component.toolBoxShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import io.toolbox.tool.api.ToolBoxApiV1
import io.toolbox.tool.runtime.RuntimeWebViewDebugging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.toolbox.core.ui.component.ToolBoxPinnedSearchField
import io.toolbox.core.ui.component.toolBoxPinnedSearchScroll
import io.toolbox.core.ui.component.rememberToolBoxListBackdrop
import io.toolbox.core.ui.component.glassRendersLive
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding

internal object DeveloperHelpTestTags {
    const val Screen = "developer_help_screen"
    const val InstallExamples = "developer_help_install_examples"
    const val Search = "developer_help_search"
    const val List = "developer_help_list"
    const val CopyAll = "developer_help_copy_all"
    const val WebViewDebugging = "developer_help_webview_debugging"
    fun chapter(id: String) = "developer_help_chapter_" + id
    fun article(id: String) = "developer_help_article_" + id
}

@Composable
internal fun DeveloperHelpScreen(
    onBack: () -> Unit,
    onInstallExamples: () -> Unit,
    onReady: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val webViewDebuggingByUser by RuntimeWebViewDebugging.enabledByUser.collectAsState()
    val webViewDebugging = remember(context, webViewDebuggingByUser) { RuntimeWebViewDebugging.status(context) }
    var webViewDebuggingUnavailable by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableStateOf(0) }
    val state by produceState<HelpLoadState>(HelpLoadState.Loading, context, loadAttempt) {
        value = HelpLoadState.Loading
        value = try {
            withContext(Dispatchers.IO) {
                context.assets.open("manual.md").bufferedReader(Charsets.UTF_8).use { reader ->
                    HelpLoadState.Loaded(parseHelpDocument(reader.readText()))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HelpLoadState.Failed
        }
    }
    LaunchedEffect(state) {
        if (state != HelpLoadState.Loading) onReady()
    }
    DeveloperHelpPage(
        state = state,
        onBack = onBack,
        onInstallExamples = onInstallExamples,
        onRetry = { loadAttempt += 1 },
        webViewDebuggingEnabled = webViewDebugging.enabled,
        webViewDebuggingForced = webViewDebugging.forcedByPlatform,
        webViewDebuggingToggleable = webViewDebugging.userToggleable,
        webViewDebuggingUnavailable = webViewDebuggingUnavailable,
        onWebViewDebuggingChange = { enabled ->
            webViewDebuggingUnavailable = !RuntimeWebViewDebugging.setEnabledByUser(context, enabled)
        },
    )
}

@Composable
internal fun DeveloperHelpPage(
    state: HelpLoadState,
    onBack: () -> Unit,
    onInstallExamples: () -> Unit,
    onRetry: () -> Unit,
    webViewDebuggingEnabled: Boolean = false,
    webViewDebuggingForced: Boolean = false,
    webViewDebuggingToggleable: Boolean = false,
    webViewDebuggingUnavailable: Boolean = false,
    onWebViewDebuggingChange: (Boolean) -> Unit = {},
) {
    DetailScreen(
        title = "开发者帮助",
        onBack = onBack,
        modifier = Modifier.testTag(DeveloperHelpTestTags.Screen),
    ) { chromePadding ->
        val pageModifier = Modifier
            .widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
            .fillMaxSize()
            .align(Alignment.TopCenter)
        val header: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.xs)) {
                ToolBoxGroupedSurface {
                    SettingsSwitchRow(
                        title = "小工具 WebView 调试",
                        summary = when {
                            webViewDebuggingForced -> "当前系统强制开启接口；应用内不可切换。"
                            webViewDebuggingUnavailable -> "WebView 不可用，无法切换。"
                            !webViewDebuggingToggleable && webViewDebuggingEnabled -> "调试版：编译时开启，应用内无法关闭。"
                            webViewDebuggingEnabled -> "已开启：连接电脑的人可读取小工具页面数据，应用重启后自动关闭。"
                            else -> "默认关闭；开启后电脑 Chrome 可检查小工具页面，应用重启后自动关闭。"
                        },
                        icon = ToolBoxIconKey.Code,
                        tint = SettingsIconTint.Graphite,
                        checked = webViewDebuggingEnabled,
                        onCheckedChange = { if (webViewDebuggingToggleable) onWebViewDebuggingChange(it) },
                        enabled = webViewDebuggingToggleable,
                        modifier = Modifier.testTag(DeveloperHelpTestTags.WebViewDebugging),
                    )
                }
                if (webViewDebuggingEnabled) {
                    AppText(
                        "电脑 Chrome 打开 chrome://inspect/#devices，选择小工具页面的 Inspect。",
                        modifier = Modifier.padding(horizontal = ToolBoxThemeTokens.spacing.md),
                        textStyle = ToolBoxThemeTokens.textStyles.label,
                        color = ToolBoxThemeTokens.colors.textSecondary,
                    )
                }
            }
        }
        val padding = mergePadding(chromePadding, PaddingValues(ToolBoxThemeTokens.spacing.md))
        when (val loaded = state) {
            is HelpLoadState.Loaded -> DeveloperHelpContent(
                document = loaded.document,
                onInstallExamples = onInstallExamples,
                modifier = pageModifier,
                contentPadding = padding,
                header = header,
            )
            else -> LazyColumn(
                modifier = pageModifier.testTag(DeveloperHelpTestTags.List),
                state = rememberTitleBoundListState(),
                contentPadding = padding,
            ) {
                item("debugging") { header() }
                item("status") {
                    if (loaded == HelpLoadState.Loading) {
                        CatalogStatusState("正在读取离线手册…", loading = true)
                    } else {
                        CatalogStatusState(
                            "离线手册暂时无法读取",
                            icon = ToolBoxIconKey.Note,
                            detail = "请重试。持续失败时请更新或重新安装 ToolBox。",
                        )
                        ToolBoxPrimaryButton("重新读取", onClick = onRetry, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeveloperHelpContent(
    document: HelpDocument,
    onInstallExamples: () -> Unit,
    modifier: Modifier = Modifier,
    onCopy: ((String) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(ToolBoxThemeTokens.spacing.two),
    header: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var expandedChapter by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedArticle by rememberSaveable { mutableStateOf<String?>(null) }
    var copiedKey by remember { mutableStateOf<String?>(null) }
    var copyError by remember { mutableStateOf(false) }
    val chapters = remember(document, query) { document.search(query) }
    val searching = query.isNotBlank()
    val copyText: (String, String) -> Unit = { key, text ->
        copyError = try {
            if (onCopy != null) {
                onCopy(text)
            } else {
                val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                clipboard.setPrimaryClip(ClipData.newPlainText("ToolBox 开发者帮助", text))
            }
            copiedKey = key
            false
        } catch (_: Exception) {
            true
        }
    }
    LaunchedEffect(copiedKey) {
        if (copiedKey != null) {
            delay(1500)
            copiedKey = null
        }
    }
    val entrance = rememberToolBoxEntrance("developer-help")
    val listState = rememberTitleBoundListState()
    // The search field is chrome: right under the large title, then pinned under the top bar as
    // glass over the manual scrolling beneath it.
    val listBackdrop = rememberToolBoxListBackdrop()
    val searchGlassLive = glassRendersLive()
    val density = LocalDensity.current
    var searchHeightPx by remember { mutableIntStateOf(0) }
    val direction = LocalLayoutDirection.current
    // The page's top padding adds spacing.md under the title: pin that much higher, so the field
    // meets the end of the top bar's fade with no sharp strip of text above it.
    val pinLift = ToolBoxThemeTokens.spacing.md
    val pinScrollPx = with(density) { toolBoxPinnedSearchScroll(pinLift).roundToPx() }
    // A new query reshapes the list. If the field is pinned, keep it pinned and show the first
    // result under it, instead of jumping back to the top and dropping the focused field.
    // While searching, the list ends in just enough room to scroll the field to its pinned spot:
    // a screen until measured (so a short result list never clamps the scroll), then exactly
    // what the results leave, so there is no screen of blank to scroll into.
    var searchRoomPx by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(query) {
        searchRoomPx = null
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > pinScrollPx) {
            listState.scrollToItem(0, pinScrollPx)
        }
    }
    LaunchedEffect(searching, pinScrollPx) {
        if (!searching) return@LaunchedEffect
        snapshotFlow { searchRoom(listState.layoutInfo, pinScrollPx) }.collect { room ->
            if (room != null) searchRoomPx = room
        }
    }
    Box(modifier) {
        ToolBoxPinnedSearchField(
            value = query,
            onValueChange = {
                query = it
                expandedArticle = null
            },
            placeholder = "搜索接口与错误码",
            listState = listState,
            listBackdrop = listBackdrop,
            contentTop = contentPadding.calculateTopPadding(),
            onHeightChanged = { searchHeightPx = it },
            pinLift = pinLift,
            modifier = Modifier
                .padding(
                    start = contentPadding.calculateStartPadding(direction),
                    end = contentPadding.calculateEndPadding(direction),
                )
                .toolBoxEntrance(entrance, 0)
                .testTag(DeveloperHelpTestTags.Search),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize()
                .testTag(DeveloperHelpTestTags.List)
                .then(if (searchGlassLive) Modifier.layerBackdrop(listBackdrop) else Modifier),
            state = listState,
            contentPadding = contentPadding,
        ) {
            // Holds the place of the pinned search field drawn over the list.
            item("search", contentType = "help-search") {
                val height = if (searchHeightPx > 0) with(density) { searchHeightPx.toDp() } else ToolBoxThemeTokens.sizes.touchTarget
                Spacer(Modifier.fillMaxWidth().height(height + ToolBoxThemeTokens.spacing.sm))
            }
            // Results come right under the search field: the introduction steps aside while searching.
            if (!searching) item("intro", contentType = "help-intro") {
                Column(Modifier.toolBoxEntrance(entrance, 1).padding(bottom = ToolBoxThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(
                            "离线手册 · API " + ToolBoxApiV1.API_VERSION,
                            modifier = Modifier.weight(1f),
                            textStyle = ToolBoxThemeTokens.textStyles.metadata,
                            color = ToolBoxThemeTokens.colors.textSecondary,
                        )
                        ToolBoxTextButton(
                            label = if (copiedKey == "all") "已复制" else "复制全部",
                            onClick = { copyText("all", document.source) },
                            modifier = Modifier.testTag(DeveloperHelpTestTags.CopyAll),
                        )
                    }
                    AppText(document.introduction, textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        color = ToolBoxThemeTokens.colors.textSecondary)
                }
            }
            if (copyError) {
                item("copy-error", contentType = "help-message") {
                    AppText(
                        "复制失败，请重试；正文也可以长按选择。",
                        modifier = Modifier
                            .padding(vertical = ToolBoxThemeTokens.spacing.one)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = ToolBoxThemeTokens.colors.danger,
                    )
                }
            }
            // Each chapter reads as one rounded card: header, any open content, then an end cap.
            if (searching) {
                item("search-summary", contentType = "help-message") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(
                            if (chapters.isEmpty()) "没有找到相关内容" else "找到 " +
                                chapters.sumOf { it.articles.size } + " 项内容",
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        )
                        ToolBoxTextButton("清除搜索", onClick = { query = ""; expandedArticle = null })
                    }
                }
            }
            chapters.forEachIndexed { chapterIndex, chapter ->
                val open = searching || expandedChapter == chapter.id
                if (searching) {
                    item("chapter-top:" + chapter.id, contentType = "help-cap") { HelpCardCap(top = true, Modifier.padding(top = ToolBoxThemeTokens.spacing.xs)) }
                } else {
                    item("chapter:" + chapter.id, contentType = "help-chapter") {
                        val radius = ToolBoxThemeTokens.radii.denseSurface
                        ToolBoxDisclosureRow(
                            title = chapter.title,
                            summary = chapter.summary,
                            expanded = expandedChapter == chapter.id,
                            sectionHeading = true,
                            onClick = {
                                expandedChapter = if (expandedChapter == chapter.id) null else chapter.id
                                expandedArticle = null
                            },
                            modifier = Modifier
                                .toolBoxEntrance(entrance, chapterIndex + 2)
                                .padding(top = ToolBoxThemeTokens.spacing.xs)
                                .clip(ToolBoxContinuousShape(radius, radius, if (open) 0.dp else radius, if (open) 0.dp else radius))
                                .background(ToolBoxThemeTokens.colors.surface)
                                .testTag(DeveloperHelpTestTags.chapter(chapter.id)),
                        )
                    }
                }
                if (open) {
                    chapter.articles.forEach { article ->
                        item("article:" + article.id, contentType = "help-article") {
                            Column(Modifier.animateItem(
                                fadeInSpec = ToolBoxMotion.effects(), placementSpec = ToolBoxMotion.ItemPlacement, fadeOutSpec = null,
                            ).background(ToolBoxThemeTokens.colors.surface)) {
                                ToolBoxDisclosureRow(
                                    title = article.title,
                                    summary = chapter.title.takeIf { searching },
                                    expanded = expandedArticle == article.id,
                                    onClick = {
                                        expandedArticle = if (expandedArticle == article.id) null else article.id
                                    },
                                    modifier = Modifier
                                        .padding(start = ToolBoxThemeTokens.spacing.one)
                                        .testTag(DeveloperHelpTestTags.article(article.id)),
                                )
                            }
                        }
                        if (expandedArticle == article.id) {
                            helpArticleBlocks(article, copiedKey, copyText)
                        }
                    }
                    item("chapter-end:" + chapter.id, contentType = "help-cap") { HelpCardCap(top = false, Modifier.animateItem(fadeInSpec = null, placementSpec = ToolBoxMotion.ItemPlacement, fadeOutSpec = null)) }
                }
            }
            header?.let { content ->
                item("debugging", contentType = "help-debugging") {
                    Box(Modifier.padding(top = ToolBoxThemeTokens.spacing.lg)) { content() }
                }
            }
            item("examples", contentType = "help-action") {
                ToolBoxPrimaryButton(
                    label = "安装四个范例",
                    onClick = onInstallExamples,
                    modifier = Modifier
                        .padding(top = ToolBoxThemeTokens.spacing.lg)
                        .fillMaxWidth()
                        .testTag(DeveloperHelpTestTags.InstallExamples),
                )
            }
            // A short (or empty) result list could not scroll far enough to keep the field pinned:
            // leave room below the page while searching (see searchRoom).
            if (searching) item(SearchRoomKey, contentType = "help-room") {
                val room = searchRoomPx
                Spacer(if (room == null) Modifier.fillParentMaxHeight() else Modifier.height(with(density) { room.toDp() }))
            }
        }
    }
}

private fun LazyListScope.helpArticleBlocks(
    article: HelpArticle,
    copiedKey: String?,
    onCopy: (String, String) -> Unit,
) {
    article.blocks.forEachIndexed { index, block ->
        val key = article.id + ":" + index
        item(key = "block:" + key, contentType = if (block.code) "help-code" else "help-prose") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(fadeInSpec = ToolBoxMotion.effects(), placementSpec = ToolBoxMotion.ItemPlacement, fadeOutSpec = null)
                    .background(ToolBoxThemeTokens.colors.surface)
                    .padding(horizontal = ToolBoxThemeTokens.spacing.md)
                    .padding(bottom = ToolBoxThemeTokens.spacing.sm),
            ) {
                if (block.code) {
                    HelpCodeBlock(block, article.title, key, copiedKey == key) { onCopy(key, block.text) }
                } else {
                    SelectionContainer {
                        AppText(block.text, textStyle = ToolBoxThemeTokens.textStyles.body)
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpCodeBlock(block: HelpBlock, articleTitle: String, key: String, copied: Boolean, onCopy: () -> Unit) {
    val spacing = ToolBoxThemeTokens.spacing
    val copyButton: @Composable () -> Unit = {
        ToolBoxTextButton(
            label = if (copied) "已复制" else "复制",
            onClick = onCopy,
            outlined = false,
            modifier = Modifier.testTag("help_code_copy:$key").semantics {
                contentDescription = "复制" + articleTitle + "中的" + block.label.ifBlank { "代码" }
            },
        )
    }
    val container = Modifier.fillMaxWidth()
        .clip(toolBoxShape(ToolBoxThemeTokens.radii.badge))
        .background(ToolBoxThemeTokens.colors.background)
        .padding(horizontal = spacing.sm)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val codeStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontFamily = FontFamily.Monospace)
    val buttonStyle = ToolBoxThemeTokens.textStyles.body
    val codeWidth = remember(block.text, codeStyle, measurer) {
        if ('\n' in block.text) null else measurer.measure(block.text, codeStyle, softWrap = false).size.width
    }
    // Reserve the longer feedback label too, so copying does not change the layout.
    val buttonTextWidth = remember(buttonStyle, measurer) {
        measurer.measure("已复制", buttonStyle, softWrap = false).size.width
    }
    BoxWithConstraints(container) {
        val inline = codeWidth != null && codeWidth + buttonTextWidth + with(density) { (32.dp + spacing.one).roundToPx() } <= constraints.maxWidth
        if (inline) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.one), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = spacing.one)) {
                    if (block.label.isNotBlank()) AppText(block.label, textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        color = ToolBoxThemeTokens.colors.textSecondary)
                    HelpCodeText(block.text)
                }
                copyButton()
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText(block.label.ifBlank { "代码" }, Modifier.weight(1f), textStyle = ToolBoxThemeTokens.textStyles.metadata)
                    copyButton()
                }
                HelpCodeText(block.text, Modifier.padding(bottom = spacing.one))
            }
        }
    }
}

@Composable
private fun HelpCodeText(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        ToolBoxText(
            text = text,
            modifier = modifier,
            style = ToolBoxThemeTokens.textStyles.metadata.copy(
                color = ToolBoxThemeTokens.colors.textPrimary,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

/** Rounded top or bottom edge that closes a chapter card around its lazily listed rows. */
@Composable
private fun HelpCardCap(top: Boolean, modifier: Modifier = Modifier) {
    val radius = ToolBoxThemeTokens.radii.denseSurface
    Box(
        modifier.fillMaxWidth().height(ToolBoxThemeTokens.spacing.xs)
            .clip(if (top) ToolBoxContinuousShape(radius, radius, 0.dp, 0.dp) else ToolBoxContinuousShape(0.dp, 0.dp, radius, radius))
            .background(ToolBoxThemeTokens.colors.surface),
    )
}

internal sealed interface HelpLoadState {
    data object Loading : HelpLoadState
    data object Failed : HelpLoadState
    data class Loaded(val document: HelpDocument) : HelpLoadState
}

private const val SearchRoomKey = "search-room"

/**
 * The search room's height for the list to scroll exactly [pinScrollPx] at most, or null while it
 * can't be measured (the first item or the room is off screen in a way that hides the content's
 * length). A list long enough to scroll past the pin by itself needs none.
 */
private fun searchRoom(info: LazyListLayoutInfo, pinScrollPx: Int): Int? {
    val items = info.visibleItemsInfo
    val first = items.firstOrNull()?.takeIf { it.index == 0 } ?: return null
    val room = items.firstOrNull { it.key == SearchRoomKey } ?: run {
        // The room is below the screen: only drop it once the content on screen alone already
        // reaches past the pin; otherwise keep the provisional room until it can be measured.
        val last = items.last()
        val shown = info.beforeContentPadding + (last.offset + last.size - first.offset) + info.afterContentPadding
        return if (shown >= info.viewportSize.height + pinScrollPx) 0 else null
    }
    val contentBeforeRoom = info.beforeContentPadding + (room.offset - first.offset) + info.afterContentPadding
    return (info.viewportSize.height + pinScrollPx - contentBeforeRoom).coerceAtLeast(0)
}
