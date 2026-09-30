package io.toolbox.host.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
fun BrowserFilterSheet(filters: BrowserFilterController, canPick: Boolean, resumed: Boolean) {
    if (!filters.sheet) return
    var screen by remember { mutableStateOf("home") }
    var editing by remember { mutableStateOf<BrowserFilterRule?>(null) }
    var resetConfirm by remember { mutableStateOf(false) }
    var blocked by remember { mutableIntStateOf(filters.blockedCount) }
    var expandedHosts by remember(screen, filters.site) {
        mutableStateOf(if (screen == "all" && filters.site.isNotBlank()) setOf(filters.site) else emptySet())
    }
    var expandedRules by remember(screen) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(resumed) {
        if (resumed) while (true) { blocked = filters.blockedCount; delay(500) }
    }
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
    ToolBoxActionSheet(
        title = "广告过滤",
        onDismissRequest = { filters.sheet = false },
        onBackRequest = closeOrBack,
        containerColor = ToolBoxThemeTokens.colors.background,
    ) {
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
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(ToolBoxThemeTokens.colors.surface)
                        ) {
                            ToolBoxSwitchSettingRow(
                                "广告过滤总开关",
                                state.enabled,
                                { value -> filters.change { it.copy(enabled = value) } },
                                modifier = Modifier.padding(horizontal = 12.dp),
                                summary = if (state.enabled) "已开启 · 本页已拦截 $blocked 次请求" else "已关闭",
                            )
                        }

                        FilterSectionLabel("当前网站")
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(ToolBoxThemeTokens.colors.surface)
                        ) {
                            ToolBoxSwitchSettingRow(
                                "对此网站启用",
                                site !in state.exceptions,
                                { enabled ->
                                    filters.change {
                                        it.copy(exceptions = if (enabled) it.exceptions - site else it.exceptions + site)
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                                summary = if (site.isEmpty()) "当前页面不可用" else "仅影响当前网站",
                                enabled = state.enabled && site.isNotEmpty(),
                            )
                        }

                        FilterSectionLabel("规则与拦截")
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(ToolBoxThemeTokens.colors.surface)
                        ) {
                            ToolBoxSettingRow(
                                "此网站的规则",
                                modifier = Modifier.padding(horizontal = 12.dp),
                                summary = "${siteRules.size} 条 · 点选与手动规则",
                                onClick = { screen = "site" },
                            )
                            ToolBoxSettingRow(
                                "全部规则",
                                modifier = Modifier.padding(horizontal = 12.dp),
                                summary = "${state.rules.size} 条 · 按网站管理",
                                onClick = { screen = "all" },
                            )
                            ToolBoxSwitchSettingRow(
                                "基础广告请求拦截",
                                state.builtIn,
                                { value -> filters.change { it.copy(builtIn = value) } },
                                modifier = Modifier.padding(horizontal = 12.dp),
                                summary = "阻止常见广告资源主机",
                                enabled = state.enabled,
                            )
                        }
                    }
                    else -> {
                        ToolBoxTextButton("添加规则", { editing = BrowserFilterRule(site = site, value = "") }, Modifier.fillMaxWidth(), outlined = false)
                        val rows = if (screen == "site") siteRules else state.rules
                        if (rows.isEmpty()) {
                            ToolBoxText("还没有规则。可以返回网页点选广告位，也可以手动添加。")
                        } else if (screen == "site") {
                            ToolBoxText(
                                site,
                                Modifier.padding(horizontal = 6.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = ToolBoxThemeTokens.textStyles.title,
                            )
                            rows.forEach { rule -> key(rule.id) {
                                FilterCompactRule(
                                    rule = rule,
                                    filters = filters,
                                    expanded = rule.id in expandedRules,
                                    onToggleExpanded = {
                                        expandedRules = if (rule.id in expandedRules) expandedRules - rule.id else expandedRules + rule.id
                                    },
                                    onEdit = { editing = rule },
                                )
                            } }
                        } else {
                            val groups = rows.groupBy { it.site }.entries.sortedWith(
                                compareByDescending<Map.Entry<String, List<BrowserFilterRule>>> { it.key == site }
                                    .thenBy { it.key }
                            )
                            groups.forEach { (host, rules) ->
                                val expanded = host in expandedHosts
                                FilterHostAccordionHeader(
                                    host = host,
                                    rules = rules,
                                    expanded = expanded,
                                    current = host == site,
                                    onClick = {
                                        expandedHosts = if (expanded) expandedHosts - host else expandedHosts + host
                                    },
                                )
                                if (expanded) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        rules.forEach { rule -> key(rule.id) {
                                            FilterCompactRule(
                                                rule = rule,
                                                filters = filters,
                                                expanded = rule.id in expandedRules,
                                                onToggleExpanded = {
                                                    expandedRules = if (rule.id in expandedRules) expandedRules - rule.id else expandedRules + rule.id
                                                },
                                                onEdit = { editing = rule },
                                            )
                                        } }
                                    }
                                }
                            }
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
            if (screen == "home" && editing == null && !resetConfirm) {
                ToolBoxPrimaryButton(
                    label = "选择广告位",
                    onClick = filters::startPicker,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canPick && state.active(site),
                )
            }
        }
    }
}



@Composable
private fun FilterHostAccordionHeader(
    host: String,
    rules: List<BrowserFilterRule>,
    expanded: Boolean,
    current: Boolean,
    onClick: () -> Unit,
) {
    val enabledCount = rules.count { it.enabled }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(ToolBoxThemeTokens.colors.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ToolBoxText(
                if (current) "$host · 当前网站" else host,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ToolBoxThemeTokens.textStyles.title,
            )
            Spacer(Modifier.height(3.dp))
            ToolBoxText(
                "${rules.size} 条规则 · $enabledCount 条启用",
                style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
            )
        }
        ToolBoxIcon(
            if (expanded) ToolBoxIconKey.ChevronDown else ToolBoxIconKey.ChevronRight,
            if (expanded) "收起 $host" else "展开 $host",
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun FilterCompactRule(
    rule: BrowserFilterRule,
    filters: BrowserFilterController,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEdit: () -> Unit,
) {
    val kind = if (rule.kind == BrowserFilterKind.Cosmetic) "隐藏区域" else "拦截请求域名"
    val source = if (rule.source == BrowserFilterSource.Picker) "点选生成" else "手动添加"
    val status = if (rule.enabled) "已启用" else "已停用"
    val compactValue = if (rule.value.length > 54) rule.value.take(54) + "…" else rule.value

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(ToolBoxThemeTokens.colors.surfaceMuted)
    ) {
        ToolBoxSettingRow(
            title = kind,
            summary = "$status · $source · $compactValue",
            onClick = onToggleExpanded,
        )
        if (expanded) {
            ToolBoxGroupDivider(startPadding = 14.dp, endPadding = 14.dp)
            ToolBoxSwitchSettingRow(
                "启用此规则",
                rule.enabled,
                { enabled ->
                    filters.change { state ->
                        state.copy(rules = state.rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
                    }
                },
                summary = rule.value,
            )
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                ToolBoxTextButton("编辑", onEdit, Modifier.weight(1f), outlined = false)
                ToolBoxTextButton("删除", { filters.delete(rule) }, Modifier.weight(1f), outlined = false)
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
