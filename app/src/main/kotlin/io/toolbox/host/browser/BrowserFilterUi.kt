package io.toolbox.host.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import kotlinx.coroutines.delay

@Composable
fun BrowserFilterControls(filters: BrowserFilterController, resumed: Boolean) {
    val state = filters.picker
    LaunchedEffect(state.active, resumed) {
        if (state.active && resumed) while (true) { filters.poll(); delay(350) }
    }
    if (state.active) {
        var advanced by remember { mutableStateOf(false) }
        var collapsed by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(ToolBoxThemeTokens.colors.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(9.dp).clip(RoundedCornerShape(50))
                        .background(ToolBoxThemeTokens.colors.primary)
                )
                Spacer(Modifier.width(10.dp))
                ToolBoxText(if (state.count == 0) "点击网页中的广告区域" else "已选择 · 匹配 ${state.count} 处",
                    Modifier.weight(1f), style = ToolBoxThemeTokens.textStyles.metadata)
                ToolBoxIconButton(ToolBoxIconKey.More, if (collapsed) "展开操作条" else "收起操作条", { collapsed = !collapsed })
                ToolBoxIconButton(ToolBoxIconKey.Close, "退出选择", filters::stopPicker)
            }
            if (!collapsed) {
                if (state.warning.isNotBlank()) ToolBoxText(state.warning, maxLines = 2,
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolBoxTextButton("缩小", { filters.adjust(-1) }, Modifier.weight(1f), enabled = state.canShrink)
                    ToolBoxTextButton("扩大", { filters.adjust(1) }, Modifier.weight(1f), enabled = state.canExpand)
                    ToolBoxTextButton(if (state.preview) "返回" else "预览", filters::preview, Modifier.weight(1f), enabled = state.count > 0)
                    ToolBoxTextButton("保存", filters::savePicker, Modifier.weight(1f), enabled = state.preview && !filters.saving, outlined = false)
                }
                if (state.selector.isNotBlank()) {
                    ToolBoxTextButton(if (advanced) "收起规则" else "高级编辑", { advanced = !advanced }, outlined = false)
                    if (advanced) FilterField("CSS 选择器", state.selector, filters::editPicker)
                }
            }
        }
    }
    filters.notice?.let { text ->
        Row(Modifier.fillMaxWidth().background(ToolBoxThemeTokens.colors.surface).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolBoxText(text, Modifier.weight(1f), style = ToolBoxThemeTokens.textStyles.metadata)
            if (filters.canUndo) ToolBoxTextButton("撤销", filters::undoLast, outlined = false)
            ToolBoxIconButton(ToolBoxIconKey.Close, "关闭提示", filters::dismissNotice)
        }
    }
}

@Composable
fun BrowserFilterSheet(filters: BrowserFilterController, canPick: Boolean, reload: () -> Unit) {
    if (!filters.sheet) return
    var screen by remember { mutableStateOf("home") }
    var editing by remember { mutableStateOf<BrowserFilterRule?>(null) }
    var resetConfirm by remember { mutableStateOf(false) }
    var blocked by remember { mutableIntStateOf(filters.blockedCount) }
    LaunchedEffect(Unit) { while (true) { blocked = filters.blockedCount; delay(500) } }
    val state = filters.snapshot
    val site = filters.site
    val siteRules = state.rules.filter { it.site == site }
    val closeOrBack = {
        when {
            editing != null -> editing = null
            resetConfirm -> resetConfirm = false
            screen != "home" -> screen = "home"
            else -> filters.sheet = false
        }
    }
    ToolBoxActionSheet(title = "广告过滤", onDismissRequest = { filters.sheet = false }, onBackRequest = closeOrBack) {
        Column(Modifier.heightIn(max = 620.dp)) {
            ToolBoxActionSheetHeader {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (screen != "home" || editing != null || resetConfirm) ToolBoxIconButton(ToolBoxIconKey.Back, "返回过滤设置", closeOrBack)
                    ToolBoxText(if (editing != null) "编辑规则" else if (screen == "all") "全部规则" else if (screen == "site") "此网站的规则" else "广告过滤",
                        Modifier.weight(1f), style = ToolBoxThemeTokens.textStyles.title)
                    ToolBoxIconButton(ToolBoxIconKey.Close, "关闭广告过滤", { filters.sheet = false })
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    editing != null -> key(editing!!.id) { FilterEditor(editing!!, filters) { editing = null } }
                    resetConfirm -> {
                        ToolBoxText("清除此网站的点选规则？手动添加的规则会保留。")
                        ToolBoxDestructiveButton("清除点选规则", { filters.resetPicked(); resetConfirm = false }, Modifier.fillMaxWidth())
                        ToolBoxSecondaryButton("取消", { resetConfirm = false }, Modifier.fillMaxWidth())
                    }
                    screen == "home" -> {
                        ToolBoxText(
                            site.ifEmpty { "当前页面不支持过滤" },
                            Modifier.padding(horizontal = 6.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
                        )

                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(ToolBoxThemeTokens.colors.primary.copy(alpha = 0.08f))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            ToolBoxSwitchSettingRow(
                                if (state.enabled) "过滤已开启" else "过滤已关闭",
                                state.enabled,
                                { value -> filters.change { it.copy(enabled = value) } },
                                summary = if (state.enabled) "本页已拦截 $blocked 次请求" else "广告过滤当前暂停",
                            )
                        }

                        FilterSectionLabel("当前网站")
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(ToolBoxThemeTokens.colors.surfaceMuted)
                        ) {
                            ToolBoxSwitchSettingRow(
                                "对此网站启用",
                                site !in state.exceptions,
                                { enabled ->
                                    filters.change { it.copy(exceptions = if (enabled) it.exceptions - site else it.exceptions + site) }
                                },
                                summary = if (site.isEmpty()) "当前页面不可用" else "仅影响 $site",
                                enabled = state.enabled && site.isNotEmpty(),
                            )
                            ToolBoxGroupDivider(startPadding = 16.dp, endPadding = 16.dp)
                            ToolBoxSettingRow(
                                "选择广告位",
                                summary = "直接点选页面元素并隐藏",
                                onClick = filters::startPicker,
                                enabled = canPick && state.active(site),
                            )
                        }

                        FilterSectionLabel("规则与拦截")
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(ToolBoxThemeTokens.colors.surfaceMuted)
                        ) {
                            ToolBoxSettingRow("此网站的规则", summary = "${siteRules.size} 条 · 点选与手动规则", onClick = { screen = "site" })
                            ToolBoxGroupDivider(startPadding = 16.dp, endPadding = 16.dp)
                            ToolBoxSettingRow("全部规则", summary = "${state.rules.size} 条 · 按网站管理", onClick = { screen = "all" })
                            ToolBoxGroupDivider(startPadding = 16.dp, endPadding = 16.dp)
                            ToolBoxSwitchSettingRow(
                                "基础广告请求拦截",
                                state.builtIn,
                                { value -> filters.change { it.copy(builtIn = value) } },
                                summary = "阻止常见广告资源主机",
                                enabled = state.enabled,
                            )
                        }

                        ToolBoxSecondaryButton("刷新网页", { filters.sheet = false; reload() }, Modifier.fillMaxWidth())
                        ToolBoxText(
                            "点选用于隐藏页面元素；网络规则用于阻止资源加载。网络规则变更后刷新页面生效。",
                            Modifier.padding(horizontal = 6.dp),
                            style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
                        )
                    }
                    else -> {
                        ToolBoxTextButton("添加规则", { editing = BrowserFilterRule(site = site, value = "") }, Modifier.fillMaxWidth(), outlined = false)
                        val rows = if (screen == "site") siteRules else state.rules
                        if (rows.isEmpty()) ToolBoxText("还没有规则。可以返回网页点选广告位，也可以手动添加。")
                        rows.groupBy { it.site }.toSortedMap().forEach { (host, rules) ->
                            ToolBoxText(host, style = ToolBoxThemeTokens.textStyles.title)
                            rules.forEach { rule -> key(rule.id) {
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ToolBoxThemeTokens.colors.surfaceMuted).padding(8.dp)) {
                                    ToolBoxSwitchSettingRow(if (rule.kind == BrowserFilterKind.Cosmetic) "隐藏区域" else "拦截请求域名", rule.enabled, { enabled ->
                                        filters.change { state -> state.copy(rules = state.rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }) }
                                    }, summary = if (rule.source == BrowserFilterSource.Picker) "点选生成" else "手动添加")
                                    ToolBoxText(rule.value, Modifier.padding(horizontal = 12.dp), maxLines = 3, overflow = TextOverflow.Ellipsis,
                                        style = ToolBoxThemeTokens.textStyles.metadata)
                                    Row {
                                        ToolBoxTextButton("编辑", { editing = rule }, Modifier.weight(1f), outlined = false)
                                        ToolBoxTextButton("删除", { filters.delete(rule) }, Modifier.weight(1f), outlined = false)
                                    }
                                }
                            } }
                        }
                        if (screen == "site" && rows.any { it.source == BrowserFilterSource.Picker }) {
                            ToolBoxDestructiveButton("清除此网站的点选规则", { resetConfirm = true }, Modifier.fillMaxWidth())
                        }
                    }
                }
                filters.notice?.let { message ->
                    ToolBoxText(message, style = ToolBoxThemeTokens.textStyles.metadata)
                    if (filters.canUndo) ToolBoxTextButton("撤销上次操作", filters::undoLast, outlined = false)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}


@Composable
private fun FilterSectionLabel(text: String) {
    ToolBoxText(
        text,
        Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
        style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
    )
}

@Composable
private fun FilterEditor(rule: BrowserFilterRule, filters: BrowserFilterController, done: () -> Unit) {
    var site by remember { mutableStateOf(rule.site) }
    var value by remember { mutableStateOf(rule.value) }
    var kind by remember { mutableStateOf(rule.kind) }
    var failure by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    FilterField("生效网站域名（不含 https://）", site, { site = it; failure = null })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolBoxTextButton("隐藏区域", { kind = BrowserFilterKind.Cosmetic }, Modifier.weight(1f), outlined = kind != BrowserFilterKind.Cosmetic)
        ToolBoxTextButton("请求域名", { kind = BrowserFilterKind.NetworkHost }, Modifier.weight(1f), outlined = kind != BrowserFilterKind.NetworkHost)
    }
    FilterField(if (kind == BrowserFilterKind.Cosmetic) "CSS 选择器，如 .ad-banner" else "广告请求域名，如 ads.example.com", value, { value = it; failure = null })
    ToolBoxText(if (kind == BrowserFilterKind.Cosmetic) "仅支持标准 CSS 选择器。当前网站会检查语法和影响范围；其他网站的规则需访问后验证。"
        else "仅在指定网站拦截此域名及其子域名的资源请求，不拦截主页面。",
        style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary))
    failure?.let { ToolBoxText(it, style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.danger)) }
    ToolBoxTextButton(if (saving) "正在保存…" else "保存规则", {
        saving = true
        filters.save(rule.copy(site = site, value = value, kind = kind)) { message ->
            saving = false
            if (message == null) done() else failure = message
        }
    }, Modifier.fillMaxWidth(), enabled = !saving && site.isNotBlank() && value.isNotBlank(), outlined = false)
    ToolBoxSecondaryButton("取消", done, Modifier.fillMaxWidth())
}

@Composable
private fun FilterField(label: String, value: String, change: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ToolBoxText(label, style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary))
        Spacer(Modifier.height(6.dp))
        BasicTextField(value, { change(it.take(1024)) },
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .background(ToolBoxThemeTokens.colors.surfaceMuted).padding(12.dp).semantics { contentDescription = label },
            textStyle = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textPrimary),
            cursorBrush = SolidColor(ToolBoxThemeTokens.colors.primary), maxLines = 4)
    }
}
