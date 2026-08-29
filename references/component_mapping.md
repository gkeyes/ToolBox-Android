# UI 组件映射与引入策略

## 1. 结论

- **Miuix 是主设计系统**：负责主题、页面骨架、导航、卡片、按钮、搜索、弹层、设置项和反馈。
- **HyperX Compose 只作为可选的源码组件层**：优先借用其页面壳、图标、集成输入框等成熟实现；不要让业务层直接依赖其全部 API。
- **HTML 小工具内部界面不受宿主设计系统约束**：宿主只提供 WebView 运行外壳、安全状态、权限入口和 JS API。

## 2. 依赖策略

```kotlin
// Miuix：使用正式 Maven 坐标并锁定版本。
implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4-rc01")
implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4-rc01")
implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4-rc01")
implementation("top.yukonga.miuix.kmp:miuix-squircle:0.9.4-rc01")
implementation("top.yukonga.miuix.kmp:miuix-nav:0.9.4-rc01")
// blur 仅在确有视觉收益且性能测试通过后再加入。
```

v0.9.4-rc01 的导航使用 `miuix-nav`，替代已移除的
`miuix-navigation3-ui`，不再引入 `androidx.navigation3`。业务路由通过
`rememberNavBackStack`、`NavDisplay` 和 `NavController` 接入连续栈深度
（`animatedTop`）、滑动/模态转场、预测性返回和侧滑关闭；业务层不得再叠加一套
Navigation3 转场。

HyperX Compose 没有稳定发布物和完整文档，推荐二选一：

1. 将其作为 Git submodule 固定到一个 commit，并包装在 `:vendor-hyperx`；
2. 仅复制经过审查的组件源码到 `:core-ui`，保留 Apache-2.0 LICENSE 与来源说明。

禁止直接跟随 `main` 分支构建生产版。

## 3. 页面到组件映射

| 页面/区域 | 首选 Miuix 组件 | 可选 HyperX 组件 | 自定义组件 |
|---|---|---|---|
| App 根布局 | `MiuixTheme`, `Scaffold` | `HyperXAppLayout`, `HyperXScaffold` | `ToolBoxAppShell` |
| 顶栏 | `SmallTopAppBar`, `IconButton` | `HyperXPage` | `ToolBoxTopBar` |
| 底部导航 | `NavigationBar`, `NavigationBarItem` | — | `MainDestinationBar` |
| 首页工具宫格 | `Card`, `Badge`, `SearchBar` | `AdaptiveIcon` | `ToolGridCard` |
| 工具管理列表 | `Card`, `TabRow`, `PullToRefresh` | `AdaptiveIcon` | `InstalledToolRow` |
| 导入审核 | `Card`, `Badge`, `Button`, `OverlayDialog` | `Hint` | `RiskSection`, `PermissionRequestRow` |
| 权限中心 | `Card`, `Switch`, `OverlayBottomSheet` | Preference 组件 | `PermissionGrantCard`, `AuditLogRow` |
| 设置 | `ArrowPreference`, `SwitchPreference`, `CheckboxPreference` | Preference 组件 | `SettingsSection` |
| 运行外壳 | `SmallTopAppBar`, `Snackbar`, `FloatingToolbar` | `FullScreenDialog` | `MiniAppWebView`, `SecurityStatusStrip` |
| 导入入口 | `FloatingActionButton` | — | — |
| 搜索与筛选 | `SearchBar`, `TabRow`, `OverlayDropdownMenu` | `IntegratedTextField` | — |

## 4. 主题约束

```kotlin
val controller = remember {
    ThemeController(
        mode = ColorSchemeMode.Light,
        keyColor = Color(0xFF3482FF),
    )
}
MiuixTheme(controller = controller) { /* app */ }
```

第一版默认明亮模式，并保留 `System / Light / Dark / MonetSystem / MonetLight / MonetDark` 切换能力。所有业务颜色必须经 `ToolBoxColorScheme` 语义化，不允许在业务 Composable 中散落十六进制颜色。

### 导航与系统栏尺寸合同

- Miuix `NavigationBar` 的每个 `NavigationBarItem` 内容区固定为官方
  `NavigationBarDefaults.ItemHeight = 64.dp`；这不是随 `fontScale` 倍增的尺寸。
- `NavigationBar` 内部单独消费底部系统导航/手势 inset；`ToolBoxAppShell`、页面内容和
  feature 页不得再次追加同一 inset，避免底部出现双倍空白或小白条被遮挡。
- 紧凑页面使用 Miuix `SmallTopAppBar` 的 52.dp collapsed 内容高度；状态栏 inset
  由该 surface 自己消费。
- 大字体或窄屏时使用 `IconWithSelectedLabel`：未选项保持图标，选中项显示标签；标签
  保留在语义树中，不能通过增大整个导航栏高度解决排版问题。

## 5. 需要建立的适配层

```text
core-ui/
├── theme/ToolBoxTheme.kt
├── component/ToolBoxCard.kt
├── component/ToolBoxTopBar.kt
├── component/ToolIcon.kt
├── component/RiskBadge.kt
├── component/PermissionRow.kt
└── vendor/HyperXAdapters.kt
```

目的：Miuix 与 HyperX 均处在快速演进阶段，业务页面只能依赖 ToolBox 自己的稳定组件接口。升级外部组件时，改动限制在适配层。
