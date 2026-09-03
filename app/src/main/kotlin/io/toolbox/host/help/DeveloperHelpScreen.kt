package io.toolbox.host.help

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import io.toolbox.core.ui.component.ToolBoxDisclosureRow
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxSearchField
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.DetailScreen
import io.toolbox.tool.api.ToolBoxApiV1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal object DeveloperHelpTestTags {
    const val Screen = "developer_help_screen"
    const val InstallExamples = "developer_help_install_examples"
    const val Search = "developer_help_search"
    const val List = "developer_help_list"
    const val CopyAll = "developer_help_copy_all"
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
    )
}

@Composable
internal fun DeveloperHelpPage(
    state: HelpLoadState,
    onBack: () -> Unit,
    onInstallExamples: () -> Unit,
    onRetry: () -> Unit,
) {
    DetailScreen(
        title = "开发帮助",
        onBack = onBack,
        modifier = Modifier.testTag(DeveloperHelpTestTags.Screen),
    ) {
        val pageModifier = Modifier
            .widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
            .fillMaxSize()
            .align(Alignment.TopCenter)
        when (val loaded = state) {
            is HelpLoadState.Loaded -> DeveloperHelpContent(
                document = loaded.document,
                onInstallExamples = onInstallExamples,
                modifier = pageModifier,
            )
            HelpLoadState.Loading -> AppText(
                "正在读取离线手册…",
                modifier = pageModifier.padding(ToolBoxThemeTokens.spacing.two),
            )
            HelpLoadState.Failed -> Column(
                modifier = pageModifier.padding(ToolBoxThemeTokens.spacing.two),
                verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
            ) {
                AppText("离线手册暂时无法读取，请重试。持续失败时请更新或重新安装 ToolBox。")
                ToolBoxPrimaryButton("重新读取", onClick = onRetry)
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
                clipboard.setPrimaryClip(ClipData.newPlainText("ToolBox 开发帮助", text))
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
    LazyColumn(
        modifier = modifier.testTag(DeveloperHelpTestTags.List),
        contentPadding = PaddingValues(ToolBoxThemeTokens.spacing.two),
    ) {
        item("intro", contentType = "help-intro") {
            Column(verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one)) {
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
                AppText(document.introduction, textStyle = ToolBoxThemeTokens.textStyles.metadata)
                ToolBoxSearchField(
                    value = query,
                    onValueChange = {
                        query = it
                        expandedArticle = null
                    },
                    placeholder = "搜索接口、打包或错误码",
                    modifier = Modifier.testTag(DeveloperHelpTestTags.Search),
                )
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
        chapters.forEach { chapter ->
            if (!searching) {
                item("chapter:" + chapter.id, contentType = "help-chapter") {
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
                            .padding(top = ToolBoxThemeTokens.spacing.one)
                            .background(ToolBoxThemeTokens.colors.surface)
                            .testTag(DeveloperHelpTestTags.chapter(chapter.id)),
                    )
                }
            }
            if (searching || expandedChapter == chapter.id) {
                chapter.articles.forEach { article ->
                    item("article:" + article.id, contentType = "help-article") {
                        Column(Modifier.background(ToolBoxThemeTokens.colors.surface)) {
                            ToolBoxGroupDivider(startPadding = ToolBoxThemeTokens.spacing.oneHalf)
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
            }
        }
        item("examples", contentType = "help-action") {
            ToolBoxPrimaryButton(
                label = "安装四个范例",
                onClick = onInstallExamples,
                modifier = Modifier
                    .padding(top = ToolBoxThemeTokens.spacing.two)
                    .fillMaxWidth()
                    .testTag(DeveloperHelpTestTags.InstallExamples),
            )
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
                    .background(ToolBoxThemeTokens.colors.surface)
                    .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf)
                    .padding(bottom = ToolBoxThemeTokens.spacing.oneHalf),
            ) {
                if (block.code) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(ToolBoxThemeTokens.colors.surfaceMuted)
                            .padding(horizontal = ToolBoxThemeTokens.spacing.one),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppText(
                                block.label.ifBlank { "代码" },
                                modifier = Modifier.weight(1f),
                                textStyle = ToolBoxThemeTokens.textStyles.metadata,
                            )
                            ToolBoxTextButton(
                                label = if (copiedKey == key) "已复制" else "复制",
                                onClick = { onCopy(key, block.text) },
                                modifier = Modifier.semantics {
                                    contentDescription = "复制" + article.title + "中的" + block.label.ifBlank { "代码" }
                                },
                            )
                        }
                        SelectionContainer {
                            ToolBoxText(
                                text = block.text,
                                modifier = Modifier.padding(bottom = ToolBoxThemeTokens.spacing.one),
                                style = ToolBoxThemeTokens.textStyles.metadata.copy(
                                    color = ToolBoxThemeTokens.colors.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                } else {
                    SelectionContainer {
                        AppText(block.text, textStyle = ToolBoxThemeTokens.textStyles.body)
                    }
                }
            }
        }
    }
}

internal sealed interface HelpLoadState {
    data object Loading : HelpLoadState
    data object Failed : HelpLoadState
    data class Loaded(val document: HelpDocument) : HelpLoadState
}
