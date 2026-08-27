package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxFloatingActionButton
import io.toolbox.core.ui.component.ToolBoxIconButton
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxNavigationBar
import io.toolbox.core.ui.component.ToolBoxNavigationItem
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxPermissionRow
import io.toolbox.core.ui.component.ToolBoxRiskBadge
import io.toolbox.core.ui.component.ToolBoxRiskLevel
import io.toolbox.core.ui.component.ToolBoxSearchField
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.component.ToolBoxStatusRow
import io.toolbox.core.ui.component.ToolBoxTopBar

object HostTestTags {
    const val BottomHome = "bottom_home"
    const val BottomTools = "bottom_tools"
    const val BottomSettings = "bottom_settings"
    const val ImportFab = "import_fab"
    const val PermissionCenter = "permission_center"
    const val RuntimeShell = "runtime_shell"
}

enum class MainDestination(val label: String, val symbol: String) {
    Home("首页", "⌂"),
    Tools("工具", "▦"),
    Settings("设置", "⚙"),
}

private data class ToolSample(
    val name: String,
    val shortName: String,
    val meta: String,
    val symbol: String,
    val status: String = "可信",
    val risk: RiskTone = RiskTone.Safe,
)

private enum class RiskTone { Safe, Warning, Danger }

private val installedTools = listOf(
    ToolSample("仓位计算器", "仓位计算", "v1.2.0 · 8.2 MB · 今天使用", "▦"),
    ToolSample("JSON 格式化", "JSON 工具", "v1.0.3 · 5.9 MB · 昨天使用", "{ }"),
    ToolSample("二维码工具", "二维码", "v1.1.0 · 4.6 MB · 3 天前", "▦", "未签名", RiskTone.Warning),
    ToolSample("文本处理", "文本处理", "v2.0.1 · 7.1 MB · 5 天前", "T"),
    ToolSample("日期计算", "日期计算", "v1.0.0 · 3.8 MB · 1 周前", "◷"),
    ToolSample("网络诊断", "IP 工具", "v0.9.2 · 6.4 MB · 从未使用", "◎", "高权限", RiskTone.Danger),
)

@Composable
fun HomeScreen(
    onDestination: (MainDestination) -> Unit,
    onImport: () -> Unit,
    onLaunchTool: (String) -> Unit,
) {
    PrimaryScreen(
        selected = MainDestination.Home,
        onDestination = onDestination,
        title = "ToolBox",
        topActions = "搜索，更多",
        onImport = onImport,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(ToolBoxThemeTokens.colors.primary)
                        .padding(18.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppText("我的工具箱", color = ToolBoxThemeTokens.colors.onPrimary, size = 14)
                        AppText("12 个工具，随用随开", color = ToolBoxThemeTokens.colors.onPrimary, size = 28, weight = FontWeight.Bold)
                        AppText("离线 · 最近 2 分钟 · 68.2 MB · 权限可控", color = ToolBoxThemeTokens.colors.onPrimary, size = 12)
                    }
                }
            }
            item { SearchPlaceholder("搜索工具、分类或标签") }
            item { SectionHeader("最近使用", "查看全部") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(installedTools.take(2)) { tool ->
                        SurfaceCard(
                            modifier = Modifier
                                .width(178.dp)
                                .clickable(role = Role.Button) { onLaunchTool(tool.name) }
                                .semantics { contentDescription = "打开${tool.name}" },
                        ) {
                            ToolIdentity(tool, compact = true)
                        }
                    }
                }
            }
            item { SectionHeader("全部工具", "编辑排序") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("全部", "计算", "文本", "开发")) { label ->
                        FilterChip(label, selected = label == "全部")
                    }
                }
            }
            item {
                ToolGrid(
                    tools = installedTools,
                    onLaunchTool = onLaunchTool,
                    modifier = Modifier.heightIn(min = 260.dp, max = 520.dp),
                )
            }
        }
    }
}

@Composable
fun ToolManagerScreen(
    onDestination: (MainDestination) -> Unit,
    onImport: () -> Unit,
    onLaunchTool: (String) -> Unit,
) {
    PrimaryScreen(
        selected = MainDestination.Tools,
        onDestination = onDestination,
        title = "工具管理",
        topActions = "搜索",
        onImport = onImport,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SurfaceCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            AppText("12", size = 28, weight = FontWeight.Bold)
                            AppText("已安装工具 · 68.2 MB", size = 13, color = ToolBoxThemeTokens.colors.textSecondary)
                        }
                        FilterChip("列表", true)
                        Spacer(Modifier.width(6.dp))
                        FilterChip("网格", false)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    listOf("已安装", "分类", "可更新").forEach { FilterChip(it, it == "已安装") }
                }
            }
            items(installedTools) { tool ->
                SurfaceCard(
                    modifier = Modifier
                        .clickable(role = Role.Button) { onLaunchTool(tool.name) }
                        .semantics { contentDescription = "打开${tool.name}，${tool.status}" },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolIdentity(tool, Modifier.weight(1f))
                        RiskBadge(tool.status, tool.risk)
                        AppText("⋮", size = 24, color = ToolBoxThemeTokens.colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
fun ImportReviewScreen(onBack: () -> Unit) {
    DetailScreen(title = "导入工具", onBack = onBack, modifier = Modifier.testTag("import_review")) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = padding, end = 20.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolGlyph("◇")
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            AppText("仓位计算器", size = 18, weight = FontWeight.Bold)
                            AppText("position-calculator.tbx · v1.2.0", size = 12, color = ToolBoxThemeTokens.colors.textSecondary)
                            AppText("发布者：示例开发者 · 签名指纹：无", size = 12, color = ToolBoxThemeTokens.colors.textSecondary)
                        }
                        RiskBadge("未签名", RiskTone.Warning)
                    }
                }
            }
            item { StatusBanner("✓", "结构检查通过，未发现 Zip Slip 或危险文件", RiskTone.Safe) }
            item { SectionHeader("工具信息", "查看文件") }
            item {
                SurfaceCard(contentPadding = 0.dp) {
                    ToolBoxStatusRow("入口与兼容性", "index.html · ToolBox API 1.0", ToolBoxRiskLevel.Trusted, statusLabel = "通过", contained = false)
                    Divider()
                    ToolBoxStatusRow("安全配置", "Strict CSP · 禁止外部脚本", ToolBoxRiskLevel.Trusted, statusLabel = "严格", contained = false)
                    Divider()
                    ToolBoxStatusRow("结构扫描", "48 个文件 · 解压后 2.6 MB · 无路径冲突", ToolBoxRiskLevel.Trusted, statusLabel = "通过", contained = false)
                }
            }
            item { SectionHeader("申请权限", "3 项") }
            item {
                SurfaceCard(contentPadding = 0.dp) {
                    ToolBoxPermissionRow("工具专属存储", "保存计算参数，配额 2 MB · 必需", ToolBoxRiskLevel.Low, icon = ToolBoxIconKey.Folder, contained = false)
                    Divider()
                    ToolBoxPermissionRow("写入剪贴板", "复制计算结果，不读取现有内容 · 可选", ToolBoxRiskLevel.Medium, icon = ToolBoxIconKey.Clipboard, contained = false)
                    Divider()
                    ToolBoxPermissionRow("网络访问", "精确域名 api.example.com · HTTPS · 可选", ToolBoxRiskLevel.Medium, icon = ToolBoxIconKey.Globe, contained = false)
                }
            }
            item {
                AppText(
                    "安装后可在“权限中心”逐项撤销。未签名工具默认使用严格隔离配置。",
                    size = 13,
                    color = ToolBoxThemeTokens.colors.textSecondary,
                )
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(ToolBoxThemeTokens.colors.background)
                .navigationBarsPadding()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionButton("取消", onBack, primary = false, modifier = Modifier.weight(1f))
            ActionButton(
                "阶段 2 才可安装",
                onClick = {},
                primary = true,
                enabled = false,
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

@Composable
fun PermissionCenterScreen(onBack: () -> Unit) {
    val permissions = listOf(
        Triple(installedTools[0], "2 项已允许 · 最近调用 2 分钟前", listOf("专属存储", "剪贴板写入")),
        Triple(installedTools[1], "1 项已允许 · 最近调用昨天", listOf("专属存储")),
        Triple(installedTools[2], "3 项已允许 · 未签名工具", listOf("专属存储", "文件保存", "相机")),
        Triple(installedTools[5], "4 项已允许 · 高风险调用被阻止 1 次", listOf("网络", "设备信息", "通知", "专属存储")),
    )
    DetailScreen(
        title = "权限中心",
        onBack = onBack,
        modifier = Modifier.testTag(HostTestTags.PermissionCenter),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = padding, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RiskSummary("8", "低风险工具", RiskTone.Safe, Modifier.weight(1f))
                    RiskSummary("3", "需关注", RiskTone.Warning, Modifier.weight(1f))
                    RiskSummary("1", "高权限", RiskTone.Danger, Modifier.weight(1f))
                }
            }
            item { SearchPlaceholder("搜索工具或权限") }
            item { SectionHeader("按工具管理", "审计日志") }
            items(permissions) { (tool, meta, chips) ->
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolIdentity(tool.copy(meta = meta), Modifier.weight(1f))
                        if (tool.risk == RiskTone.Danger) RiskBadge("检查", RiskTone.Danger) else AppText("⋮", size = 24)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEach { FilterChip(it, selected = false, warning = it in setOf("剪贴板写入", "文件保存", "相机", "网络", "设备信息")) }
                    }
                }
            }
            item { SectionHeader("授权方式", "") }
            item {
                SurfaceCard {
                    AppText("仅本次 · 使用期间 · 始终允许 · 每次询问 · 拒绝", size = 13, color = ToolBoxThemeTokens.colors.textSecondary)
                }
            }
            item { SectionHeader("全局策略", "") }
            item {
                ToolBoxStatusRow(
                    title = "未签名工具严格模式",
                    summary = "禁用直接网络、内联脚本与敏感权限常驻授权",
                    status = ToolBoxRiskLevel.Trusted,
                    statusLabel = "开启",
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    onDestination: (MainDestination) -> Unit,
    onPermissionCenter: () -> Unit,
) {
    val sections = listOf(
        "外观" to listOf("主题" to "明亮", "主题种子色" to "ToolBox 蓝"),
        "运行" to listOf("默认安全配置" to "Strict", "退出时清理" to "按工具设置", "后台音频" to "禁止"),
        "数据" to listOf("导出与备份" to "阶段 5", "清理缓存" to "0 B", "每工具配额" to "2 MB"),
        "安全" to listOf("权限中心" to "按工具管理", "可信发布者" to "尚未配置", "网络策略" to "默认关闭", "审计日志保留" to "30 天"),
        "开发者" to listOf("调试工具" to "关闭", "控制台与 Bridge Inspector" to "阶段 3", "未签名调试包" to "不允许"),
        "关于" to listOf("ToolBox 版本" to "0.1.0", "开源许可" to "查看", "WebView 版本" to "运行时检测", "JS API 版本" to "1.0 设计稿"),
    )
    PrimaryScreen(
        selected = MainDestination.Settings,
        onDestination = onDestination,
        title = "设置",
        topActions = "",
        onImport = null,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            sections.forEach { (title, rows) ->
                item { SectionHeader(title, "") }
                item {
                    SurfaceCard(contentPadding = 0.dp) {
                        rows.forEachIndexed { index, (label, value) ->
                            ToolBoxSettingRow(
                                title = label,
                                summary = value,
                                onClick = if (label == "权限中心") onPermissionCenter else null,
                                modifier = if (label == "权限中心") {
                                    Modifier
                                        .testTag(HostTestTags.PermissionCenter)
                                        .semantics { contentDescription = "打开权限中心" }
                                } else {
                                    Modifier
                                },
                            )
                            if (index != rows.lastIndex) Divider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RuntimeShellScreen(onBack: () -> Unit, onPermissionCenter: () -> Unit) {
    DetailScreen(
        title = "仓位计算器",
        subtitle = "严格隔离 · 本地运行",
        onBack = onBack,
        modifier = Modifier.testTag(HostTestTags.RuntimeShell),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 20.dp, top = padding, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusBanner("●", "运行容器尚未启用 · ToolBox API 1.0 设计稿", RiskTone.Warning)
            SurfaceCard(modifier = Modifier.weight(1f), contentPadding = 0.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ToolBoxThemeTokens.colors.background)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText("▣", color = ToolBoxThemeTokens.colors.primary, size = 20)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        AppText("示例独立 HTTPS Origin", size = 13, weight = FontWeight.Bold)
                        AppText("独立 Origin · CSP Strict（阶段 3 实现）", size = 11, color = ToolBoxThemeTokens.colors.textSecondary)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .border(2.dp, ToolBoxThemeTokens.colors.divider, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolGlyph("</>", size = 68.dp)
                        AppText("HTML 小工具内容区域", size = 17, weight = FontWeight.Bold)
                        AppText(
                            "宿主仅提供运行容器、权限提示与 JS API\n这里不规定小程序自身的 UI 风格",
                            size = 13,
                            color = ToolBoxThemeTokens.colors.textSecondary,
                            align = TextAlign.Center,
                        )
                    }
                }
            }
            SurfaceCard(contentPadding = 2.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    RuntimeAction("盾", "权限", onPermissionCenter)
                    RuntimeAction("↗", "外部打开", {})
                    RuntimeAction("虫", "调试", {})
                    RuntimeAction("⚙", "详情", {})
                }
            }
        }
    }
}

@Composable
private fun PrimaryScreen(
    selected: MainDestination,
    onDestination: (MainDestination) -> Unit,
    title: String,
    topActions: String,
    onImport: (() -> Unit)?,
    content: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background)
            .statusBarsPadding(),
    ) {
        val compact = maxWidth < 600.dp
        if (compact) {
            ToolBoxAppScaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = { TopBar(title, topActions) },
                bottomBar = { DestinationBar(selected, onDestination, compact = true) },
                floatingActionButton = {
                    if (onImport != null) {
                        ToolBoxFloatingActionButton(
                            contentDescription = "导入 .tbx 工具包",
                            onClick = onImport,
                            modifier = Modifier.testTag(HostTestTags.ImportFab),
                        )
                    }
                },
            ) { scaffoldPadding ->
                Box(Modifier.fillMaxSize().padding(scaffoldPadding)) {
                    content(PaddingValues(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                DestinationBar(selected, onDestination, compact = false)
                Column(Modifier.weight(1f)) {
                    TopBar(title, topActions)
                    Box(Modifier.weight(1f).widthIn(max = 1040.dp).align(Alignment.CenterHorizontally)) {
                        content(PaddingValues(horizontal = 28.dp, vertical = 16.dp))
                    }
                }
            }
        }
        if (!compact && onImport != null) {
            ToolBoxFloatingActionButton(
                contentDescription = "导入 .tbx 工具包",
                onClick = onImport,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 22.dp, bottom = 22.dp)
                    .testTag(HostTestTags.ImportFab),
            )
        }
    }
}

@Composable
private fun DestinationBar(selected: MainDestination, onDestination: (MainDestination) -> Unit, compact: Boolean) {
    val modifier = if (compact) {
        Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 72.dp)
    } else {
        Modifier.fillMaxHeight().navigationBarsPadding().width(96.dp).padding(vertical = 20.dp)
    }
    val arrangement = if (compact) Arrangement.SpaceAround else Arrangement.spacedBy(18.dp)
    if (compact) {
        ToolBoxNavigationBar(
            items = mainNavigationItems,
            selectedId = selected.name,
            onItemSelected = { item -> onDestination(MainDestination.valueOf(item.id)) },
            modifier = modifier,
        )
    } else {
        Column(modifier.background(ToolBoxThemeTokens.colors.surface), verticalArrangement = arrangement, horizontalAlignment = Alignment.CenterHorizontally) {
            MainDestination.entries.forEach { destination -> DestinationItem(destination, selected == destination, onDestination) }
        }
    }
}

@Composable
private fun DestinationItem(destination: MainDestination, selected: Boolean, onDestination: (MainDestination) -> Unit) {
    val color = if (selected) ToolBoxThemeTokens.colors.primary else ToolBoxThemeTokens.colors.textSecondary
    Column(
        modifier = Modifier
            .size(width = 80.dp, height = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Tab) { onDestination(destination) }
            .testTag(
                when (destination) {
                    MainDestination.Home -> HostTestTags.BottomHome
                    MainDestination.Tools -> HostTestTags.BottomTools
                    MainDestination.Settings -> HostTestTags.BottomSettings
                },
            )
            .semantics { contentDescription = "${destination.label}标签${if (selected) "，已选择" else ""}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppText(destination.symbol, size = 20, color = color)
        AppText(destination.label, size = 12, color = color, weight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun DetailScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    content: @Composable androidx.compose.foundation.layout.BoxScope.(Dp) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background)
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        ToolBoxAppScaffold(
            modifier = Modifier.fillMaxSize().widthIn(max = 1040.dp),
            topBar = {
                ToolBoxTopBar(
                    title = title,
                    subtitle = subtitle,
                    navigationIcon = ToolBoxIconKey.Back,
                    onNavigationClick = onBack,
                    actions = {
                        ToolBoxIconButton(ToolBoxIconKey.More, "更多", {})
                    },
                )
            },
        ) { scaffoldPadding ->
            Box(Modifier.fillMaxSize()) {
                content(scaffoldPadding.calculateTopPadding())
            }
        }
    }
}

@Composable
private fun TopBar(title: String, actions: String) {
    ToolBoxTopBar(
        title = title,
        actions = {
            if (actions.contains("搜索")) ToolBoxIconButton(ToolBoxIconKey.Search, "搜索", {})
            if (actions.contains("更多")) ToolBoxIconButton(ToolBoxIconKey.More, "更多", {})
        },
    )
}

private val mainNavigationItems = listOf(
    ToolBoxNavigationItem(
        MainDestination.Home.name,
        MainDestination.Home.label,
        ToolBoxIconKey.Home,
        HostTestTags.BottomHome,
    ),
    ToolBoxNavigationItem(
        MainDestination.Tools.name,
        MainDestination.Tools.label,
        ToolBoxIconKey.Tools,
        HostTestTags.BottomTools,
    ),
    ToolBoxNavigationItem(
        MainDestination.Settings.name,
        MainDestination.Settings.label,
        ToolBoxIconKey.Settings,
        HostTestTags.BottomSettings,
    ),
)

@Composable
private fun ToolGrid(tools: List<ToolSample>, onLaunchTool: (String) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth < 600.dp -> 3
            maxWidth <= 840.dp -> 4
            else -> 6
        }
        LazyVerticalGrid(columns = GridCells.Fixed(columns), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tools) { tool ->
                SurfaceCard(
                    modifier = Modifier
                        .heightIn(min = 118.dp)
                        .clickable(role = Role.Button) { onLaunchTool(tool.name) }
                        .semantics { contentDescription = "打开${tool.name}，${tool.status}" },
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolGlyph(tool.symbol)
                        AppText(tool.shortName, size = 13, weight = FontWeight.SemiBold, maxLines = 2, align = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolIdentity(tool: ToolSample, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ToolGlyph(tool.symbol)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            AppText(if (compact) tool.shortName else tool.name, size = 15, weight = FontWeight.Bold, maxLines = 1)
            AppText(if (compact) tool.meta.substringAfterLast("·").trim() else tool.meta, size = 11, color = ToolBoxThemeTokens.colors.textSecondary, maxLines = 2)
        }
    }
}

@Composable
private fun ToolGlyph(symbol: String, size: Dp = 48.dp) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(ToolBoxThemeTokens.colors.primary),
        contentAlignment = Alignment.Center,
    ) {
        AppText(symbol, color = ToolBoxThemeTokens.colors.onPrimary, size = if (size > 50.dp) 18 else 16, weight = FontWeight.Bold)
    }
}

@Composable
private fun SurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    ToolBoxCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(contentPadding),
        content = content,
    )
}

@Composable
private fun SearchPlaceholder(label: String) {
    ToolBoxSearchField(
        value = "",
        onValueChange = {},
        placeholder = label,
        contentDescription = label,
    )
}

@Composable
private fun SectionHeader(title: String, action: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppText(title, modifier = Modifier.weight(1f).semantics { heading() }, size = 18, weight = FontWeight.Bold)
        if (action.isNotEmpty()) AppText(action, size = 13, color = ToolBoxThemeTokens.colors.primary, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, warning: Boolean = false) {
    val background = when {
        warning -> ToolBoxThemeTokens.colors.warning.copy(alpha = 0.12f)
        selected -> ToolBoxThemeTokens.colors.primary.copy(alpha = 0.12f)
        else -> ToolBoxThemeTokens.colors.surface
    }
    val color = when {
        warning -> ToolBoxThemeTokens.colors.warning
        selected -> ToolBoxThemeTokens.colors.primary
        else -> ToolBoxThemeTokens.colors.textSecondary
    }
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(background)
            .border(1.dp, ToolBoxThemeTokens.colors.divider, RoundedCornerShape(13.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { AppText(label, size = 12, color = color, weight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun RiskBadge(label: String, tone: RiskTone) {
    ToolBoxRiskBadge(
        level = when (tone) {
            RiskTone.Safe -> ToolBoxRiskLevel.Trusted
            RiskTone.Warning -> if (label == "未签名") ToolBoxRiskLevel.Unsigned else ToolBoxRiskLevel.Medium
            RiskTone.Danger -> ToolBoxRiskLevel.High
        },
        label = label,
    )
}

@Composable
private fun StatusBanner(symbol: String, label: String, tone: RiskTone) {
    val color = riskColor(tone)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppText(symbol, color = color, weight = FontWeight.Bold)
        AppText(label, modifier = Modifier.weight(1f), size = 13, color = color, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun Divider() = Spacer(Modifier.fillMaxWidth().height(1.dp).background(ToolBoxThemeTokens.colors.divider))

@Composable
private fun RiskSummary(number: String, label: String, tone: RiskTone, modifier: Modifier) {
    SurfaceCard(modifier) {
        AppText(number, modifier = Modifier.fillMaxWidth(), size = 24, color = riskColor(tone), weight = FontWeight.Bold, align = TextAlign.Center)
        AppText(label, modifier = Modifier.fillMaxWidth(), size = 11, color = ToolBoxThemeTokens.colors.textSecondary, align = TextAlign.Center)
    }
}

@Composable
private fun RuntimeAction(symbol: String, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.size(width = 72.dp, height = 56.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppText(symbol, size = 18, color = ToolBoxThemeTokens.colors.primary)
        AppText(label, size = 10, color = ToolBoxThemeTokens.colors.textSecondary)
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (primary) {
        ToolBoxPrimaryButton(
            label = label,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
        )
        return
    }
    val background = if (primary) ToolBoxThemeTokens.colors.primary else ToolBoxThemeTokens.colors.surface
    val foreground = if (primary) ToolBoxThemeTokens.colors.onPrimary else ToolBoxThemeTokens.colors.textPrimary
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) background else ToolBoxThemeTokens.colors.divider)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { AppText(label, color = if (enabled) foreground else ToolBoxThemeTokens.colors.textSecondary, weight = FontWeight.Bold, align = TextAlign.Center) }
}

@Composable
private fun riskColor(tone: RiskTone): Color = when (tone) {
    RiskTone.Safe -> ToolBoxThemeTokens.colors.success
    RiskTone.Warning -> ToolBoxThemeTokens.colors.warning
    RiskTone.Danger -> ToolBoxThemeTokens.colors.danger
}

@Composable
private fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 14,
    color: Color = ToolBoxThemeTokens.colors.textPrimary,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight, textAlign = align),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Preview(name = "首页", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun HomePreview() = ToolBoxTheme { HomeScreen({}, {}, {}) }

@Preview(name = "工具管理", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ToolManagerPreview() = ToolBoxTheme { ToolManagerScreen({}, {}, {}) }

@Preview(name = "导入审核", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ImportReviewPreview() = ToolBoxTheme { ImportReviewScreen {} }

@Preview(name = "权限中心", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun PermissionCenterPreview() = ToolBoxTheme { PermissionCenterScreen {} }

@Preview(name = "设置", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun SettingsPreview() = ToolBoxTheme { SettingsScreen({}, {}) }

@Preview(name = "运行外壳", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun RuntimeShellPreview() = ToolBoxTheme { RuntimeShellScreen({}, {}) }
