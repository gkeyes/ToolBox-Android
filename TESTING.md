# ToolBox 测试准入与目录

本文件是自动化测试的单一说明目录。任何新增或保留的测试都必须先能回答“为什么必须测、怎样测、什么结果才算通过”。功能尚未实现时，不用占位测试制造虚假信心。

## 测试准入规则

测试至少保护以下一种真实边界：

1. `AGENTS.md` 的安全不变量或安全敏感分支；
2. 原子事务、并发、配额、持久化、恢复或真实 schema 迁移；
3. 用户报告且已定位到代码路径的缺陷；
4. 关键导航、系统 inset、滚动、触控尺寸、字体缩放或辅助功能行为；
5. 发布产物与候选提交之间可复验的证据链。

不为 data class/getter、框架默认行为、静态占位文案、实现细节或已经被更真实层覆盖的同一行为新增测试。同一边界的多组输入优先使用参数化矩阵。截图只证明指定渲染状态，不替代交互、真机 inset、运行时或安全验证。

每次修改测试时同步更新本文件。若测试失去对应风险，应删除或合并，而不是永久保留。

## 当前 JVM 测试

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `MainDestinationContractTest.topLevelDestinationsExposeTheThreeHostTabsInDesignOrder` | 顶级导航顺序是宿主信息架构合同。 | 读取 `MainDestination.entries` 的类型和标签。 | 顺序固定为首页、工具、设置，标签与类型一致。 |
| `MainDestinationContractTest.capabilityRoutesRetainTypedStateInsteadOfWholeScreenModels` | 导入、运行等能力路由必须携带最小类型化状态，避免硬编码整个页面模型。 | 构造不同 capability route 并比较其类型和值。 | 不同能力和不同 ID 不会被错误视为同一路由。 |
| `HostScreenLayoutContractTest.compactAndMediumWidthsSelectTheContentSpacingUsedByTheHost` | 用户已报告排版松散，宽度策略需要一个低成本合同。 | 向 `hostRouteLayoutFor` 输入 360dp 和 840dp。 | 紧凑/中宽判定正确，水平边距为 20dp/28dp，垂直节奏为 16dp。 |
| `ProductionEmptyStateTest.freshInstallShowsZeroToolsAndAnImportActionWithoutToolCards` | 防止假工具、假数量和默认仓位计算器回归。 | 构造 `ProductionHostState.freshInstall()` 并检查首页与管理页模型。 | 工具数为 0、列表为空、状态为 Empty，仅保留真实导入入口。 |
| `ToolBoxNavigationItemLayoutTest.navigationItemMinHeightExpandsWithLargeFontScaleWithoutDroppingBelowTouchTarget` | 已修复 200% 字体下底栏裁剪，且触控目标不得缩小。 | 输入 0.5x、1x、2x font scale 计算导航项最小高度。 | 高度分别不低于 64dp、64dp、128dp。 |
| `ToolBoxAppScaffoldInsetTest.compactScaffoldAssignsEachSystemInsetToOneSemanticOwner` | 紧凑布局曾出现顶部/底部未适配和重复 padding 风险。 | 检查有顶栏、底栏、FAB 时各类 inset 的 owner。 | status/cutout 仅由顶栏、navigation 仅由底栏、IME 仅由内容、FAB inset 仅由 FAB 消费。 |
| `ToolBoxAppScaffoldInsetTest.mediumScaffoldLeavesNavigationAndImeToContentInsteadOfTheSideNavigation` | 侧边导航不应错误吞掉底部手势区或 IME。 | 检查中宽布局的 inset policy。 | status/cutout 归顶栏，navigation/IME 归内容，侧栏不重复消费。 |
| `ToolBoxAppScaffoldInsetTest.scaffoldWithoutBottomBarKeepsContentAndFloatingActionButtonIndependentlyReachable` | 详情页无底栏时内容和 FAB 仍需避让系统区域。 | 检查顶栏+FAB、无底栏组合。 | navigation/IME 归内容，FAB 有独立安全 inset，不发生重叠。 |
| `ToolBoxAppScaffoldInsetTest.expandedScaffoldWithoutBarsKeepsCutoutNavigationAndImeWithContent` | 无固定 chrome 的展开布局仍需完整适配系统区域。 | 检查无顶栏、无底栏、无 FAB 组合。 | cutout/status/navigation/IME 全部由内容恰好消费一次。 |

## 当前设备/模拟器交互测试

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `HostNavigationTest.freshInstallNavigationOnlyExposesImplementedHostCapabilities` | 这是当前唯一跨页面真实 Compose 交互链，并防止未实现能力被伪装成可用。 | 启动全新状态，依次点击工具、导入、返回和设置。 | 入口可达；导入/设置明确显示尚不可用；页面中不存在仓位计算器。 |
| `HostAdaptiveScrollTest.longCatalogScrollsToTheLastStableKeyAndKeepsActionsTouchSafe` | 直接覆盖用户报告的滑动卡涩风险、稳定 key 与 48dp 触控目标。 | 注入 80 个测试卡片，滚动到 `tool-80`，读取末项和导入 FAB 的语义边界。 | 最后一项可稳定定位且可见，卡片和 FAB 宽高均至少 48dp。 |
| `HostAdaptiveScrollTest.freshInstallRemainsReachableAtTwoHundredPercentFontScale` | 保护 200% 字体下内容与底部导航不被裁剪。 | 使用 `fontScale=2f` 渲染全新状态并检查空状态、底栏和三个导航项。 | 全部内容可达；底栏容器至少 104dp；每个导航项至少 48dp。 |

## 当前截图测试

这些截图是阶段 1 的临时宿主视觉合同。对应真实功能落地时应替换而非叠加重复截图。

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `HomeCompactScreenshot` | 检查非空目录卡片在紧凑手机上的层级和密度；数据仅来自 Preview fixture。 | 以 411x891dp 渲染 `PreviewHostFixtures.home` 并与人工批准基线比较。 | 标题、卡片和操作无裁剪/重叠；不得被当作生产预装证明。 |
| `HomeFreshInstallCompactScreenshot` | 保护真实全新安装空状态的留白和导入引导。 | 以 411x891dp 渲染 `ProductionHostState.freshInstall()`。 | 显示 0 工具和空状态，不出现 fixture 工具。 |
| `HomeFreshInstallLargeTextScreenshot` | 大字体是已发生的底栏裁剪回归场景。 | 以 411x891dp、`fontScale=2f` 渲染全新状态。 | 标题、空状态和底栏标签完整，无裁剪和遮挡。 |
| `ToolManagerCompactScreenshot` | 工具管理列表需要在后续真实数据接入前保持紧凑卡片基线。 | 以 411x891dp 渲染 Preview 管理模型。 | 卡片层级、间距和操作位置一致，无假数据泄漏到生产。 |
| `ImportReviewCompactScreenshot` | 阶段 2 真实审核页将继承该信息层级，当前只保护“不可用”状态的布局。 | 以 411x891dp 渲染导入审核静态状态。 | 状态真实、可读、无越界；不宣称完成 SAF 或安装。 |
| `PermissionCenterCompactScreenshot` | 权限中心是用户已报告的关键路由，真实功能接入前需保留空状态视觉基线。 | 以 411x891dp 渲染权限中心静态状态。 | 不显示伪造授权，信息层级与触控空间无裁剪。 |
| `SettingsCompactScreenshot` | 设置曾为假操作，当前截图只保护明确不可用状态和排版。 | 以 411x891dp 渲染设置静态状态。 | 不把静态行表现为已持久化功能，布局无重叠。 |
| `RuntimeShellMediumScreenshot` | 用户要求工具内容优先；后续真实 WebView 外壳需要一个被替换的旧基线。 | 以 700x1024dp 渲染当前 truthful-unavailable 运行外壳。 | 不伪造工具运行；Task 16 实现后必须用内容优先外壳基线替换。 |

## 当前证据回执自测

五个 case 共用一个脚本和夹具，不再为相同规则增加独立测试文件。

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `scripts/qa/self-test.sh --case valid` | GitHub 下载后的门禁证据必须可从任意目录独立复验。 | 在临时目录生成完整相对路径回执并传入明确候选 SHA。 | 校验器返回 `EVIDENCE_VALID`。 |
| `scripts/qa/self-test.sh --case stale-sha` | 门禁结果必须绑定调用方期望的精确提交，不能复用旧候选回执。 | 对完整回执传入不同的 40 位候选 SHA。 | 校验器以 `EVIDENCE_STALE` 非零退出。 |
| `scripts/qa/self-test.sh --case missing-action-log` | 缺少动作记录时不能给出完整证据结论。 | 回执引用不存在的动作日志。 | 校验器以 `EVIDENCE_INCOMPLETE` 非零退出。 |
| `scripts/qa/self-test.sh --case missing-ui-tree` | 宿主门禁必须明确记录其 UI 证据范围，即使范围是未采集设备树。 | 回执引用不存在的 surface 记录。 | 校验器以 `EVIDENCE_INCOMPLETE` 非零退出。 |
| `scripts/qa/self-test.sh --case missing-cleanup-receipt` | 缺少清理证明时不能复用门禁结果。 | 回执引用不存在的清理记录。 | 校验器以 `EVIDENCE_INCOMPLETE` 非零退出。 |

## 非测试构建门禁

`verifySecurityInvariants`、`assembleDebug`/AndroidTest APK、`lintDebug` 和截图校验是候选构建门禁，不计作功能测试。它们分别预期：禁用 API/权限扫描为零违规、APK 可构建、Lint 无阻断问题、已登记截图与人工批准基线一致。它们不能替代上面的交互测试或未来包/运行时安全测试。
