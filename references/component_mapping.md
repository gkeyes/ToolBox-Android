# ToolBox Miuix 组件映射

## 1. 结论

- Miuix `0.9.4-rc01` 是宿主的主设计系统；业务代码只依赖 ToolBox 适配层。
- 使用 `miuix-nav` 的导航栈、预测性返回和侧滑转场；不得再叠加另一套 Navigation3 动画。
- HTML 小工具内部 UI 不使用 Miuix。宿主只提供紧凑运行壳、权限入口和原生能力。
- HyperX 不是当前依赖。只有未来固定 commit 的源码经审查后才能经 `:core-ui` 适配层进入，
  永不跟踪其 `main`。

```kotlin
implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4-rc01")
implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4-rc01")
implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4-rc01")
implementation("top.yukonga.miuix.kmp:miuix-squircle:0.9.4-rc01")
implementation("top.yukonga.miuix.kmp:miuix-nav:0.9.4-rc01")
```

## 2. 页面映射

| 页面/区域 | Miuix 基础组件 | ToolBox 适配组件 | 行为合同 |
|---|---|---|---|
| App 根与主题 | `MiuixTheme`、`Scaffold` | `ToolBoxAppShell` | 全局 token、system bar/inset 单一归属。 |
| 主导航 | `NavigationBar`、`NavigationBarItem` | `MainDestinationBar` | 仅工具/设置；约 56dp 视觉区，手势 inset 只消费一次。 |
| 顶栏 | `SmallTopAppBar`、`IconButton` | `ToolBoxTopBar` | 普通页 48–52dp 内容区；大字体自然增高。 |
| 工具列表 | surface/card、menu、search | `GroupedSurface`、`ToolRow`、`ToolSearchField` | 48dp 搜索、72–80dp 行、stable key、整行打开。 |
| 导入反馈 | progress、snackbar、dialog | `ImportFeedback` | 选择 → 内部检查 → 成功/失败；没有审核卡/风险徽标。 |
| 工具详情 | grouped surface、menu、dialog | `ToolDetailSection` | 打开、权限、后台任务、删除必须真实。 |
| 工具权限 | preference switch | `ToolBoxSwitchSettingRow` | 整行与开关可点；映射实际 manifest + handler。 |
| 后台任务 | grouped rows、status、dialog | `TaskRow` | 显示真实任务、结果和取消，不展示审计/恢复。 |
| 设置 | arrow preference | `SettingsSection` | 主题、后台保障、工具权限、Developer Help。 |
| 后台保障 | preference switch、grouped rows | `BackgroundSafeguardsScreen` | 总开关、持续会话停止按钮和真实系统设置入口。 |
| Developer Help | top bar、search、grouped list、text button | `DeveloperHelpScreen`、`ToolBoxDisclosureRow` | 同一份离线 Markdown；章节/主题折叠、搜索、代码复制与四个范例入口。 |
| 运行容器 | `SmallTopAppBar`、menu、snackbar | `RuntimeToolBar`、`MiniAppWebView` | 顶部约 48dp；无底栏/安全条，WebView 填满其余空间。 |

## 3. ToolBox 适配层

```text
core-ui/
├── theme/ToolBoxTheme.kt
├── component/ToolBoxTopBar.kt
├── component/GroupedSurface.kt
├── component/ToolRow.kt
├── component/ToolBoxSwitchSettingRow.kt
├── component/MainDestinationBar.kt
├── component/ToolSearchField.kt
└── navigation/ToolBoxNavigation.kt
```

适配层负责 Miuix API 变化、主题 token、语义、最小命中目标、inset 和动效契约。业务页面
不得直接散落 Miuix 尺寸、颜色或导航实现。

## 4. 尺寸、inset 和动效合同

- `NavigationBar` 不按 `fontScale` 乘高；视觉内容约 56dp，系统导航/手势 inset 仅由其
  surface 消费一次。目的地可在大字体时转为 icon-only，但保留 TalkBack 标签。
- 顶栏、状态栏、cutout、IME、底部导航各有一个所有者；不要在外层 `Scaffold`、页面和组件中
  重复追加 `systemBars` 或 `ime` padding。
- 搜索 48dp，常规设置行 64–72dp，工具行 72–80dp，任意交互目标至少 48dp。文字可换行，
  不把容器乘以字体缩放。
- Tab 使用短淡入；页面转场与返回只使用 `miuix-nav`。系统关闭动画时停用装饰性动效。
- 运行页无 `MainDestinationBar`、固定权限栏、floating toolbar 或技术状态条；仅运行顶栏。

## 5. 视觉禁止项

- 不使用审核、风险、未签名、发布者、审计或恢复 UI 填充信息密度。
- 不对每一个列表行重复包卡；同组行放在一个 grouped surface 并用 separator 区分。
- 不出现无 handler 的开关、纯文本“允许/拒绝”操作或静态设置状态。
- 不使用额外沉浸式小白条、重阴影、玻璃拟态、长弹簧或嵌套滚动。
