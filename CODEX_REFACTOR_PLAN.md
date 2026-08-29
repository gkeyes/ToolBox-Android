# ToolBox Android 轻量化重构、性能治理与 Codex 执行计划

> 可直接交给 Codex 执行。
> 审核仓库：`gkeyes/ToolBox-Android`
> 审核基线：`main@c0742c46de95d132b4591864dfbc429b9cd84015`
> 配套设计：`DESIGN.md`
> 配套草图：`toolbox_ui_wireframe_v2.png`
> 核心目标：在不破坏已安装工具、目录数据和必要运行边界的前提下，显著降低 UI 层级、工程复杂度和滚动/切换卡顿。

---

# 0. 执行摘要

当前 App 的“页面很简单但工程很大”并非单一问题，而是三类问题叠加：

1. **产品层重复**：首页与工具管理页都包含搜索、筛选和工具集合，设置页还暴露未实现的占位项。
2. **UI 层级偏重**：Miuix `Scaffold/Card/NavigationBar/TextField/Preference` 外再叠加自定义高度、系统栏和卡片包装，造成顶部、底部过高，卡片松散，背景割裂。
3. **状态与数据流低效**：目录列表在 Composable getter 中反复过滤/排序；ViewModel 为每个工具单独订阅版本 Flow；启动前执行数据库读取和 WebView Profile 清理，可能放大首屏等待和滚动重组成本。

本计划不建议“把所有代码粗暴塞回一个模块”。`.tbx` 解包、安装事务和 WebView 运行边界具有真实复杂度。合理目标是：

- UI 与普通业务保持简单；
- 包处理和运行时保留清晰边界；
- 删除空模块、占位功能和重复抽象；
- 对自有工具放宽交互确认，而不是取消结构性防护；
- 所有性能结论先有真机证据，再做删除或替换。

推荐最终模块：

```text
:app                 页面、导航、主题、轻量手工 DI
:core-data           Room、DataStore、目录投影查询
:tool-package        .tbx 解析、校验、原子安装
:tool-runtime        WebView、Origin、生命周期
:benchmark           可选；仅用于 Macrobenchmark/Baseline Profile
```

删除：

```text
:tool-api            当前为空，权威协议恢复前不参与构建
```

`core-ui` 的四个生产文件在完成组件替换后移入 `:app/designsystem`，再删除该 Gradle 模块。此调整减少模块和三方 UI 依赖，但不合并真正需要独立测试的包处理与运行时边界。

---

# 1. 审核范围与结论等级

## 1.1 已审查的关键区域

- 根工程与版本目录：`settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml`
- App 启动与依赖：`MainActivity.kt`、`HostDependencies.kt`
- 导航与布局：`ToolBoxNavigation.kt`、`HostNavigationChrome.kt`、`ToolBoxLayout.kt`、`ToolBoxInsets.kt`
- 目录与 UI：`CatalogUiState.kt`、`CatalogViewModel.kt`、`HostCatalogScreens.kt`、`HostCatalogComponents.kt`
- 设置：`SettingsScreen.kt`、`SettingsViewModel.kt`
- 主题与基础组件：`ToolBoxTheme.kt`、`ToolBoxRows.kt`
- Room 查询：`Daos.kt`、`RoomRepositories.kt`
- 构建、测试和 CI：各模块 `build.gradle.kts`、`.github/workflows/android.yml`
- 产品与安全基线：`PRODUCT.md`、`AGENTS.md`、现有 `DESIGN.md`、`README.md`

## 1.2 结论等级

- **C：代码已确认**——从现有实现可直接确认。
- **H：高概率假设**——代码结构与截图高度吻合，但仍需 Perfetto/gfxinfo 验证。
- **M：必须测量**——不能只凭静态代码认定。

Codex 不得把 H/M 项写成“已找到根因”。必须先生成性能基线报告。

---

# 2. 代码审核发现

## 2.1 UI 与布局

### F-UI-01：底部导航高度被多层叠加【C，P0】

当前实现：

- `HostNavigationChrome.kt`：普通字体下 `compactHeight = 72.dp`；
- `ToolBoxLayout.kt`：每个 `NavigationBarItem` 又使用 `64.dp * fontScale` 最小高度；
- `ToolBoxAppScaffold`：底栏再追加 `navigationBars.union(ime)` 的 inset；
- Miuix `NavigationBar` 本身还存在内部布局和 padding。

结果：视觉内容区与系统导航区混在一起，截图中的底栏明显过高。字体比例大于 1.0 时，高度进一步被放大。

修复：

- 底栏内容固定 56dp；
- 系统导航区只通过 `navigationBarsPadding()` 追加一次；
- 删除 `compactHeight` 分段和 `64.dp * fontScale`；
- IME 弹出时隐藏底栏或由内容区适配，禁止 `navigationBars.union(ime)` 扩大底栏；
- 用实机手势导航、三键导航各验收一次。

### F-UI-02：顶部标题栏与页面背景割裂【C，P0】

`ToolBoxTopBar` 强制使用 `surface`，而页面容器使用 `background`。截图中的白色标题带正是这一结构的直接表现。

修复：

- 主页面标题区与 `canvas` 同色；
- 大标题放入页面内容或轻量 header；
- 详情页可保留 compact top bar，但背景仍应与 canvas 协调；
- 禁止用一整块纯白区域横切主页、工具页和设置页。

### F-UI-03：顶部、搜索、导航尺寸错误地乘以字体比例【C，P0】

当前存在：

```kotlin
toolBoxNavigationItemMinHeight(fontScale) = 64.dp * fontScale
toolBoxTopBarMinHeight(fontScale) = 64.dp * fontScale
toolBoxSearchFieldMinHeight(fontScale) = 52.dp * fontScale
```

这是布局膨胀的明确来源。字体已经通过 `sp` 进行缩放，不应再将整个容器按相同比例放大。

修复：

- 导航、搜索、按钮只保留 48/52/56dp 的最小触控尺寸；
- 字体放大后通过换行、内容测量和有限增加 padding 适配；
- 2.0 字体下单独做截图和可达性验收。

### F-UI-04：系统栏和 IME 可能存在重复消费【H，P0】

当前 `Scaffold` 的 `contentWindowInsets` 包含 `systemBars + displayCutout + ime`，顶部和底部 slot 又单独 `windowInsetsPadding()`，内容再消费 `scaffoldPadding`。这套“所有层都参与”的策略复杂且容易双算。

修复目标：

```text
status bar  -> header 单一消费
navigation  -> bottom bar 单一消费
IME         -> 当前输入内容单一消费
Scaffold    -> 不再额外注入完整 systemBars/IME
```

Codex 必须通过布局检查和不同导航模式截图证明没有双倍空白。

### F-UI-05：首页信息架构与工具页重复【C，P1】

`HomeScreen` 与 `ToolManagerScreen` 都使用相同目录状态，并都包含搜索、筛选和工具集合。首页又加统计卡，因此视觉上成为工具页的重复版本。

修复：

- 首页只保留常用、最近、导入；
- 搜索、分类、排序仅在工具页；
- 数量信息放入工具页小标题 `已安装 · N`；
- 删除首页汇总卡。

### F-UI-06：“本机目录”是静态装饰，语义不明确【C，P0】

`CompactCatalogSummary()` 中“本机目录”只是 `AppText`，无点击、无路径、无解释。用户无法判断它是标签、按钮还是存储位置。

修复：直接删除。若未来确需目录管理，应放在“工具存储”详情页，并明确说明使用 App 私有目录，不能让用户误以为可直接浏览系统路径。

### F-UI-07：工具卡结构导致高度松散【C，P0】

`HomeToolCard` 由两段垂直内容组成：

1. 图标 + 更多按钮；
2. 独立的最小 48dp 可点击标题区。

再叠加 10dp padding 和大圆角 Card，单项自然变成高卡片。它不是工具内容需要的高度，而是布局结构造成的。

修复：改成 72–80dp 单行 `ToolRow`：图标、标题/元数据、chevron/更多同一行。首页可放入一个 grouped surface，而不是每项一张 Card。

### F-UI-08：搜索框左侧余量依赖三方内部实现【C，P0】

`ToolBoxSearchField` 直接使用 Miuix `TextField`，仅提供 `leadingIcon`，没有明确的宿主水平 padding。截图中搜索图标靠左，说明当前三方默认值与目标视觉不符。

修复：重写搜索框，明确：

- 高 48dp；
- 左右 14–16dp；
- 图标 18–20dp；
- 图标与文字 8–10dp；
- 聚焦和清除按钮状态可控。

### F-UI-09：设置页展示无实际作用的占位行【C，P0】

`StaticSettingsStatus()` 渲染：

- “严格策略固定”；
- “工具配额与开发者工具”。

它们只是静态文字，不可点击，也没有对应状态写入。现有 `PRODUCT.md` 明确反对看似可操作但无行为的控件。

修复：

- 第一版直接删除；
- 只有实际实现“个人/严格”模式后才显示运行模式行；
- 开发者工具未实现时完全隐藏，不显示“后续开放”。

### F-UI-10：每行都包 Miuix Card，形成 Card wall【C，P1】

`SurfaceCard` 对多数条目统一使用 `ToolBoxCard`，后者固定 22dp 圆角。设置、详情、列表和状态都倾向独立成卡，视觉与 Compose 节点均偏重。

修复：

- 引入 `GroupedSurface`；
- 同一语义组只创建一个 surface；
- 行间使用 separator；
- 独立 Card 仅用于错误、风险、确认和空状态。

---

## 2.2 Compose 状态与目录性能

### F-PERF-01：`visibleTools` 在重组时反复过滤和排序【C，P0】

`CatalogUiState.visibleTools` 是计算属性，每次读取都会：

1. 创建 sequence；
2. 分类过滤；
3. 查询过滤；
4. 排序；
5. 转为新 List。

`HomeScreen` 和 `CatalogScreen` 在一个组合过程中会多次读取它，例如空状态判断和列表渲染。这会创建多份临时列表，并让 UI 状态对象在输入期间不断发生全量计算。

修复：

- 将 query/category/sort 与目录 Flow 在 ViewModel 中 `combine`；
- 生成一次 `visibleTools`，作为稳定状态字段；
- 搜索输入 100–150ms debounce；
- `distinctUntilChanged()`；
- Composable 只读取已计算列表。

### F-PERF-02：目录使用“一工具一版本 Flow”的 N+1 订阅【C，P0】

`CatalogViewModel.observeCatalog()` 先观察工具列表，再对每个工具调用 `observeVersions(toolId)`，最后使用 `combine(flows)`。工具越多，Room 观察者、映射和列表重建越多。

修复：在 Room 中增加单一投影查询：

```sql
SELECT
  t.id,
  t.name,
  t.signatureState,
  t.activeVersionCode,
  t.lastOpenedAt,
  t.categoryId,
  t.pinnedOrder,
  v.version,
  v.bundleBytes,
  v.launchState
FROM tools t
LEFT JOIN tool_versions v
  ON v.toolId = t.id
 AND v.versionCode = t.activeVersionCode
ORDER BY t.pinnedOrder IS NULL, t.pinnedOrder, t.lastOpenedAt DESC, t.id
```

新增 `CatalogRowProjection`，DAO 只暴露一个 `Flow<List<CatalogRowProjection>>`。ViewModel 不再组合 N 个版本 Flow。

### F-PERF-03：目录状态未声明 Compose 稳定性【H，P1】

`CatalogTool`、`CatalogUiState` 未标注 `@Immutable`。它们字段本身可保持不可变，但 Compose 可能保守判断为 unstable，扩大重组范围。

修复：

- 状态类加 `@Immutable`；
- 不暴露可变集合；
- 列表项只传入自身状态和稳定回调；
- 使用 Layout Inspector/Compose trace 验证重组次数，而不是只加注解后宣称完成。

### F-PERF-04：Tab 切换可能重建页面并丢失列表状态【H，P1】

`navigateMain()` 清理 back stack，再重新加入工具/设置 route。当前页面内没有显式保留 `LazyListState`，底部 Tab 频繁切换可能重建内容、重置滚动位置，并重新触发布局。

修复：

- 主 Tab 使用稳定的顶层 scaffold；
- 每个 Tab 通过 `rememberSaveable`/`SaveableStateHolder` 保留滚动状态；
- 详情、导入、运行页作为独立 push route；
- Tab 切换不执行复杂页面进出动画。

### F-PERF-05：列表缺少 `contentType` 和图标加载策略【H，P1】

当前有稳定 key，但没有 `contentType`。目录项使用字母占位，未来接入真实 icon 时若主线程读取文件，会直接造成滚动卡顿。

修复：

- Lazy items 同时提供 `key` 和 `contentType`；
- 图标在 IO 线程解码；
- 使用小型 LRU 缓存；
- 禁止滚动时同步文件读取；
- 首屏图标加载失败时显示稳定占位，不引发布局尺寸变化。

---

## 2.3 启动和运行时

### F-START-01：首屏 Ready 前执行了非首屏必要维护【C，P0】

`HostDependenciesViewModel.initialize()` 在返回 Ready 前执行：

- 创建 Room/DataStore；
- 创建 inspector/lifecycle；
- 读取全部已安装工具 ID；
- 创建 `RuntimeProfileManager`；
- 扫描并回收孤儿 WebView Profile。

这些工作虽在 IO dispatcher，但会阻塞宿主进入真实首页。Profile 清理失败甚至阻止整个 App 启动。

修复：将启动拆为两层：

### Essential bootstrap

- 打开设置与目录数据库；
- 建立最小 AppContainer；
- 立即显示真实首页/加载骨架。

### Deferred maintenance

- inspection session 清理；
- orphan profile 清理；
- 历史恢复检查；
- 旧审计裁剪。

维护任务在首帧后启动。若运行时维护失败，只在“打开工具”时阻断对应功能，不应让用户无法进入首页或管理目录。

### F-START-02：Profile 管理是复杂度与启动成本的重要来源【M，P1】

`RuntimeProfileManager.kt` 体量大，启动还会执行 orphan cleanup。当前 README 已支持“独立 Profile”和“Origin-only fallback”。对于用户自有、离线、小型工具，可考虑将 Origin-only 设为默认个人模式，独立 Profile 作为高级严格模式。

不可直接删除。Codex 必须先：

1. 记录当前 Profile/Origin 模式；
2. 测量启动和打开工具耗时；
3. 列出 localStorage、cookies、cache、service worker、卸载清理影响；
4. 通过 ADR 决定是否默认 Origin-only。

始终保留：唯一 exact HTTPS Origin、导航限制、file/content access 禁用、renderer gone 恢复。

---

## 2.4 工程架构与依赖

### F-ARCH-01：六模块中一个模块为空【C，P0】

`:tool-api` 只有 build 文件，没有生产代码。README 也说明权威 TypeScript 声明缺失，桥接尚未恢复。

修复：

- 从 `settings.gradle.kts` 删除 `:tool-api`；
- 保留 `sdk/PROVENANCE.md` 与协议恢复说明；
- 等权威 `toolbox-api.d.ts` 恢复后再通过单独 PR 重建模块；
- 不根据文档猜测 RPC 协议。

### F-ARCH-02：`core-ui` 模块收益低于当前维护成本【C，P1】

`core-ui` 生产层主要是 4 个组件/主题文件，却引入：

- `miuix-ui`
- `miuix-preference`
- `miuix-icons`
- `miuix-squircle`
- `material-icons-extended`

这个包装层原本用于隔离实验性 Miuix，但当前视觉问题也集中在这些包装组件。

推荐迁移：

1. 在 `core-ui` 内先保持 API 不变，替换内部实现；
2. 使用稳定 Compose Material3/Foundation + 本地少量 vector icon；
3. 截图和真机通过后，将组件移入 `app/designsystem`；
4. 删除 `core-ui` 模块和 Miuix 依赖；
5. 不添加 Hilt、Koin、Accompanist 等新框架抵消“轻量化”。

说明：Miuix 是否是卡顿根因属于 M 项，不能只凭依赖名称定罪。但当前高度、颜色和卡片层级问题已足以支持更换宿主 UI 实现。

### F-ARCH-03：依赖注入不需要 Hilt【C，P1】

当前手工 `HostDependenciesViewModel` 和多个 factory 偏长，但引入 Hilt 会增加编译插件、生成代码和概念数量，不符合个人小工具宿主的轻量目标。

修复：

- 建立单一 `AppContainer`/`DefaultAppContainer`；
- `Application` 持有懒加载 container；
- 使用标准 `viewModelFactory { initializer { ... } }` 或小型显式 factory；
- 不引入 DI 框架。

### F-ARCH-04：包检查模块存在可选的“企业级恢复”复杂度【M，P2】

`tool-package` 包含 resumable inspection、recovery store、verified receipt、session disposal 等多套机制。它们对不可信、大包、进程中断恢复有价值，但对“前台导入、包小于 10MB、自有工具”为主的场景可能过度。

此项必须由用户确认边界后执行：

```text
最大压缩包：10MB
最大解压体积：30MB
最大文件数：200
导入必须在前台完成
进程被杀后允许重新选择文件，不承诺恢复审核会话
```

若接受，可将导入链收敛为：

```text
TbxInspector
TbxManifestValidator
TbxInstaller
TbxInstallTransaction
```

仍保留 Zip Slip、Zip Bomb、路径冲突、禁止类型、完整性和原子 rename。该简化不得与 UI 重构混在同一 PR。

### F-ARCH-05：CI 与测试流程对个人项目偏重，但运行时不受其影响【C，P2】

当前每次 push/PR 都运行构建、测试、lint、截图，之后再启动 emulator instrumentation。它不影响 APK 运行速度，但明显增加开发周转时间。

建议：

- `pull_request/push`：安全扫描、assemble、unit test、lint；
- `workflow_dispatch/release/nightly`：截图、instrumentation、真实 WebView；
- 保留包攻击矩阵与运行时边界测试；
- 精简 `TESTING.md` 为测试矩阵，不为每个普通 getter 编写说明。

---

## 2.5 安全与权限定位

### F-SEC-01：用户需要的是“减少自有工具摩擦”，不是“关闭所有防护”【设计决策，P0】

建议新增两种运行模式：

### 个人模式（默认自用）

- 用户导入自己的 Ed25519 公钥或生成本机 owner key；
- 与 owner key 匹配的签名工具自动标记为 `OWNER_TRUSTED`；
- 更新时不重复展示完整风险长页；
- 可按白名单自动授予工具已声明的低/中风险宿主能力；
- Android 系统运行时权限仍由系统按需首次确认；
- 设置页允许“始终允许我的签名工具”并可随时撤销。

### 严格模式

- 对未知签名、未签名和外部来源保持完整审核；
- 高风险能力每次确认；
- 网络和文件域名/范围必须显式展示。

### 永远不能关闭的结构性边界

即使是个人模式，也必须保留：

- Zip Slip、Zip Bomb、路径碰撞和文件数量/体积限制；
- 禁止 Dex/JNI/APK/可执行原生载荷；
- 唯一 exact HTTPS Origin；
- 禁止 `file://` 与 `addJavascriptInterface`；
- 禁止任意外部导航和默认直接联网；
- 安装原子性与失败回滚；
- 工具之间的数据隔离；
- 具名卸载和可验证清理。

理由：自有代码也可能因依赖、复制、误打包或更新错误携带风险。结构性防护对用户交互几乎无感，不应为了“放宽权限”一并删除。

---

# 3. 目标架构

## 3.1 Gradle 模块

### 目标状态

```text
ToolBox-Android/
├── app/
│   └── host/
│       ├── app/                Activity、AppContainer、启动
│       ├── navigation/         顶层 Tab + 二级 route
│       ├── designsystem/       tokens、page、group、row、search、nav
│       ├── feature/home/
│       ├── feature/catalog/
│       ├── feature/settings/
│       ├── feature/importreview/
│       ├── feature/detail/
│       ├── feature/permissions/
│       └── feature/runtime/
├── core-data/
├── tool-package/
├── tool-runtime/
└── benchmark/                  可选
```

### 依赖方向

```text
app -> core-data
app -> tool-package
app -> tool-runtime
tool-package -> core-data
tool-runtime -> core-data + tool-package
```

禁止反向依赖 UI。禁止工具模块依赖 `app`。

## 3.2 AppContainer

建议：

```kotlin
interface AppContainer {
    val repositories: CoreDataRepositories
    val packageInspector: ToolPackageInspector
    val packageLifecycle: ToolPackageLifecycle
    val runtimePreparer: ToolRuntimePreparer
    val runtimeDataCleaner: RuntimeDataCleaner
    val maintenance: HostMaintenance
}
```

`DefaultAppContainer` 只创建对象，不执行耗时维护。耗时工作由 `HostMaintenance.runDeferred()` 在首帧后启动。

## 3.3 UI 状态

建议将目录状态拆为：

```kotlin
@Immutable
data class CatalogScreenState(
    val loading: Boolean,
    val allTools: List<CatalogItemUi>,
    val visibleTools: List<CatalogItemUi>,
    val query: String,
    val category: String?,
    val sort: CatalogSort,
    val feedback: CatalogFeedback?,
)
```

所有排序和筛选只在 ViewModel 执行一次。Home 使用自己的 `HomeScreenState`，不直接承担完整目录管理状态。

## 3.4 Room 投影

新增：

```kotlin
@Immutable
data class CatalogProjection(
    val toolId: String,
    val name: String,
    val signatureState: String,
    val activeVersionCode: Int?,
    val activeVersionName: String?,
    val bundleBytes: Long?,
    val launchState: String?,
    val lastOpenedAt: Long?,
    val categoryId: String?,
    val pinnedOrder: Int?,
)
```

DAO 返回单 Flow，Repository 映射为领域模型。不得在 UI 层再查询版本表。

---

# 4. 性能研究与证据计划

## 4.1 先测后改

Codex 第一阶段不得直接宣布“已解决卡顿”。必须在用户真机或可用 Android 设备上形成：

```text
docs/performance/BASELINE.md
docs/performance/AFTER_PHASE_1.md
docs/performance/FINAL.md
artifacts/perf/<run-id>/gfxinfo.txt
artifacts/perf/<run-id>/*.pftrace
artifacts/perf/<run-id>/meminfo.txt
```

仓库不必长期提交大型 `.pftrace`；报告中记录文件路径、设备信息和关键结论即可。

## 4.2 固定测试流

### Flow A：冷启动

1. `am force-stop`；
2. 启动 MainActivity；
3. 等待首页第一个真实工具行可交互；
4. 连续 10 次，前 2 次作为预热说明，不混入 warm 数据。

### Flow B：主 Tab 切换

1. 首页稳定；
2. 首页 → 工具 → 设置 → 首页；
3. 连续 20 轮；
4. 记录主线程、frame timeline 和状态保存。

### Flow C：目录滚动

使用真实或测试注入的 20、50、100 个目录项，分别：

1. 顶部到底部；
2. 底部回顶部；
3. 每组重复 5 次。

测试数据不得写入用户正式目录；使用 debug fixture database 或专用测试构建。

### Flow D：搜索

输入：

```text
仓
仓位
position
不存在的关键词
```

观察输入响应、列表更新时间和 Room/CPU 工作。

### Flow E：打开与关闭工具

1. 打开仓位计算器；
2. 等待 WebView 首个 main frame；
3. 返回；
4. 重复 10 次；
5. 检查 PSS、WebView 销毁和 retained Activity/WebView。

## 4.3 工具

- `dumpsys gfxinfo ... framestats`：快速帧统计；
- Perfetto：frame timeline、main thread、binder、scheduler、I/O；
- Simpleperf：仅在 debug/profileable 构建上定位 CPU 热点；
- `dumpsys meminfo`：打开/关闭工具前后；
- Compose tracing/Layout Inspector：重组和布局；
- Macrobenchmark：在稳定后建立回归；
- Baseline Profile：最终阶段生成，不在根因未解决前用来掩盖问题。

## 4.4 建议性能门槛

以用户 HyperOS 真机为准，先记录基线；最终同时满足相对改进和绝对门槛：

| 场景 | 门槛 |
|---|---|
| 主列表稳定滚动 | janky frames ≤ 3%，无单帧 > 50ms |
| Tab 切换 | 输入到稳定首帧 p95 ≤ 120ms |
| 搜索 | debounce 后结果更新 p95 ≤ 100ms |
| warm start | 中位数 ≤ 450ms，或较基线改善 ≥ 30% |
| cold start | 中位数 ≤ 900ms，或较基线改善 ≥ 30% |
| 10 次开关工具 | 退出后 PSS 增量稳定，不持续线性增长；建议净增 ≤ 10MB |
| 首屏 | 不等待 orphan profile 全量清理后才显示目录 |

设备性能差异较大。若绝对门槛不适用，报告中必须说明硬件、刷新率、系统版本和相对改善，不得伪造通过。

---

# 5. 分阶段实施计划

# Phase 0：基线、分支与保护（必须先做）

## 目标

建立可回滚分支和性能事实，不改变视觉与安全行为。

## 任务

1. 创建分支：`refactor/lightweight-v2`。
2. 记录当前 commit、Gradle/AGP/Kotlin、设备、WebView provider。
3. 新建 `docs/refactor/CURRENT_STATE.md`：
   - 模块清单；
   - 关键依赖；
   - 现有功能清单；
   - 数据库 schema 版本；
   - 已知未实现能力。
4. 新建 `scripts/perf/capture-host.sh`：
   - 支持 `SERIAL`、`PACKAGE`、`FLOW`；
   - 自动保存 gfxinfo、meminfo、logcat；
   - 可选 Perfetto；
   - 不修改系统全局动画设置，除非脚本结束时恢复。
5. 完成 A–E 流程至少一次，写 `BASELINE.md`。
6. 打包用户现有 DB/schema 和安装目录结构的只读清单，禁止复制敏感正文到日志。

## 验收

- 构建、unit test、lint 通过；
- 有可复现的真机基线；
- 没有 UI 或数据行为变化。

## 提交

`chore(perf): capture baseline before lightweight refactor`

---

# Phase 1：修复高度、Insets 和明显视觉错误

## 目标

先解决截图中最明确的问题，不同时改数据层。

## 修改文件

- `core-ui/.../ToolBoxLayout.kt`
- `core-ui/.../ToolBoxInsets.kt`
- `core-ui/.../ToolBoxRows.kt`
- `app/.../HostNavigationChrome.kt`
- 相关布局测试和截图测试

## 任务

1. 删除所有容器高度 `* fontScale`。
2. 底栏内容固定 56dp；系统导航栏只消费一次。
3. 顶部标题区 52–56dp，不含状态栏。
4. `Scaffold` 不再同时给 content 和 bar 注入完整 system/IME insets。
5. 键盘出现时底栏隐藏或不参与 IME 上移。
6. TopBar 改用 canvas 背景。
7. 搜索框固定 48dp，并增加明确左 padding。
8. 更新截图基准前，先输出 before/after 对比图供人工审核。

## 验收

- 普通字体、1.3、2.0 字体；
- 手势导航、三键导航；
- 竖屏、横屏；
- 搜索框聚焦/键盘弹出；
- 底栏无双倍白区；
- phase 1 gfxinfo 不得比基线恶化。

## 提交

`fix(ui): correct chrome sizing and inset ownership`

---

# Phase 2：按 `DESIGN.md` 重构首页、工具页和设置页

## 目标

形成 V2 信息架构和紧凑 grouped-list 风格。

## 任务

### 首页

- 删除 `CompactCatalogSummary`；
- 删除搜索与筛选；
- 新建 `HomeScreenState`；
- 显示常用/最近；
- `+` 导入移到标题区；
- 单个工具也使用紧凑行。

### 工具页

- 搜索、筛选、排序集中到此页；
- `已安装 · N` 作为 section title；
- 工具项改为 `ToolRow`；
- 删除“详情”文字按钮，右侧使用更多菜单；
- 整行点击打开工具。

### 设置页

- 删除 `StaticSettingsStatus` 两个占位行；
- 只保留主题和审计日志；
- 个人模式尚未实现前，不显示运行模式假控件；
- 增加“性能诊断”入口可先指向真实诊断页面或隐藏，不得 no-op。

### 组件

新增：

```text
ToolBoxPage
PageHeader
GroupedSurface
GroupedRow
ToolRow
SearchField
FilterChip
BottomDestinationBar
```

## 验收

- 与 `toolbox_ui_wireframe_v2.png` 的层级、尺寸、密度一致；
- 一屏显示能力明显提升；
- 无嵌套卡片墙；
- 无假按钮；
- TalkBack 主操作和更多操作分离；
- 所有截图先人工确认再更新 golden。

## 提交

`feat(ui): redesign host as compact grouped tool shelf`

---

# Phase 3：目录投影、筛选与 Compose 稳定性

## 目标

消除目录 N+1 Flow、重组时重复排序和 Tab 状态丢失。

## 修改文件

- `core-data/.../Daos.kt`
- `core-data/.../Entities.kt` 或新增 projection 文件
- `core-data/.../RoomRepositories.kt`
- `app/.../CatalogUiState.kt`
- `app/.../CatalogViewModel.kt`
- `app/.../ToolBoxNavigation.kt`
- 相关 migration/DAO/ViewModel tests

## 任务

1. 添加单一 catalog projection DAO query。
2. Repository 暴露 `observeCatalogProjection()`。
3. 删除 ViewModel 中 per-tool `observeVersions()` combine。
4. query/category/sort 使用 StateFlow；
5. query 使用 100–150ms debounce；
6. 只生成一次 visible list；
7. `CatalogTool`、ScreenState 添加真实可成立的 `@Immutable`；
8. Lazy list 添加 `key`、`contentType`；
9. 每个主 Tab 保存 `LazyListState`；
10. 不因 Tab 切换重启目录订阅。

## 数据迁移

若仅增加 projection query，不改变 schema，无需 DB migration。若新增字段，必须：

- 更新 Room schema JSON；
- 编写真实 migration test；
- 不使用 destructive migration；
- 验证用户现有目录和授权保留。

## 验收

- 100 个 debug fixture 工具滚动符合帧率门槛；
- 搜索不阻塞输入；
- 工具版本更新后目录只发生必要变更；
- Tab 来回切换后滚动位置保持；
- DAO 单元/仪器测试通过。

## 提交

`perf(catalog): replace per-tool flows with one stable projection`

---

# Phase 4：启动与 WebView 生命周期治理

## 目标

尽快显示宿主，维护任务延后；确保 WebView 无泄漏。

## 任务

1. 将 `HostDependenciesViewModel` 改为轻量 AppContainer bootstrap。
2. 首屏 Ready 只依赖数据库和必要 repositories。
3. orphan profile、inspection cleanup、audit prune 在首帧后执行。
4. 维护失败在设置/诊断或打开工具时展示，不阻塞首页。
5. 给以下操作添加 trace section：
   - `coreData.create`
   - `catalog.firstEmission`
   - `runtimeProfile.cleanup`
   - `runtime.prepare`
   - `webView.create`
   - `webView.firstMainFrame`
6. 检查 `AndroidView` onRelease/DisposableEffect；确保 WebView 只销毁一次。
7. 10 次打开/返回后做 meminfo/heap 分析。
8. 若 Profile cleanup 是主要耗时，提交 ADR 比较：
   - dedicated profile；
   - origin-only personal mode；
   - 清理和隔离差异。

## 验收

- 首页不等待维护扫描；
- warm/cold start 达到门槛或至少改善 30%；
- WebView 不线性泄漏；
- 安全运行时测试全部保留通过。

## 提交

`perf(startup): defer noncritical maintenance past first frame`

---

# Phase 5：个人模式与严格模式

## 目标

满足“我自用、基本自己开发、可以放宽权限”的真实需求，同时不取消无感的结构保护。

## 数据模型

建议：

```kotlin
enum class HostRunMode { PERSONAL, STRICT }

data class OwnerTrustSettings(
    val ownerKeyId: String?,
    val autoGrantDeclaredCapabilities: Boolean,
    val allowUnsignedLocalHashTrust: Boolean,
)
```

## 任务

1. 设置页实现真实运行模式选择。
2. 支持导入/生成 owner public key；私钥处理必须有明确方案，不能把明文私钥写入普通 DataStore。
3. owner-signed 工具：
   - 简化导入确认；
   - 更新时保持签名连续性；
   - 可自动授权声明能力；
   - 仍记录最小审计元数据。
4. 未签名工具：
   - 可按包 hash 记住一次信任；
   - 文件发生变化后必须重新确认；
   - 不得把“一次信任”扩展为任意未签名包全局信任。
5. Android runtime permission 仍按系统要求处理。
6. 保留所有 always-on boundary。
7. 增加撤销 owner trust、重置授权和恢复严格模式。

## 验收

- 自有签名工具安装/更新流程明显缩短；
- 未知签名仍进入完整审核；
- 签名变化、hash 变化立即失去自动信任；
- 权限撤销实时生效；
- 安全测试覆盖两个模式。

## 提交

`feat(trust): add owner-signed personal mode without weakening runtime boundaries`

---

# Phase 6：依赖与模块轻量化

## 目标

在 UI 和性能已经稳定后，删掉真正没有价值的模块与依赖。

## 顺序

### 6.1 删除空 `tool-api`

- 修改 `settings.gradle.kts`；
- 检查无依赖；
- 更新 README/架构图；
- 保留协议恢复 ADR。

### 6.2 替换 Miuix 实现

- `core-ui` 公共 API 先不变；
- 内部用 Compose Material3/Foundation 实现；
- 仅引入 `material3`，不引入 `material-icons-extended`；
- 所需 8–12 个图标改成本地 vector 或小型自有 icon set；
- 运行截图、帧率、APK size 对比。

### 6.3 合并 `core-ui` 到 app

- 迁移到 `app/.../designsystem`；
- 删除 `core-ui` module；
- 只保留必要 component tests 和 5–7 张代表性截图。

## 不做

- 不合并 `tool-package` 和 `tool-runtime`，除非单独评估证明边界无价值；
- 不添加 Hilt/Koin；
- 不为了减少模块数牺牲包安全测试；
- 不在同一提交同时替换 UI 框架、数据库和运行时。

## 验收

输出 `docs/refactor/DEPENDENCY_DIFF.md`：

- 模块数 before/after；
- Gradle 依赖数；
- debug/release APK 大小；
- clean build 与 incremental build 时间；
- 主流程性能对比；
- 删除文件与保留边界说明。

## 提交

```text
build: remove dormant tool-api module
refactor(ui): replace experimental Miuix implementation
build: fold compact design system into app module
```

---

# Phase 7：测试、CI、文档与发布门槛

## 目标

减少开发摩擦，但保留高价值安全和回归证据。

## 快速 CI

每次 push/PR：

```bash
./gradlew --no-daemon \
  verifySecurityInvariants \
  assembleDebug \
  testDebugUnitTest \
  lintDebug
```

## 完整 CI

手动、nightly 或 release：

```bash
./gradlew --no-daemon \
  validateDebugScreenshotTest \
  :app:connectedDebugAndroidTest \
  :core-data:connectedDebugAndroidTest \
  :tool-runtime:connectedDebugAndroidTest
```

外加：

- Macrobenchmark；
- Baseline Profile；
- 真机性能脚本；
- 安装、升级、卸载、授权、运行完整路径。

## 测试保留优先级

必须保留：

- Zip Slip/Zip Bomb/路径碰撞/完整性；
- 安装事务与回滚；
- exact origin、危险 scheme、导航阻断；
- 权限撤销；
- 工具 A/B 数据隔离；
- WebView renderer recovery；
- 数据库 migration；
- 已报告的 UI inset/底栏/字体问题；
- 目录 projection 与搜索行为。

可合并或删除：

- 重复层级测试；
- 框架默认行为；
- 静态文案 getter；
- 已移除旧 UI 的 screenshot golden。

## 文档

最终更新：

- `README.md`
- `PRODUCT.md`
- `DESIGN.md`
- `AGENTS.md`
- `TESTING.md`
- `docs/performance/FINAL.md`
- 必要 ADR

---

# 6. Codex 约束与边界

## 6.1 强制约束

1. 不得删除或重建用户 Room 数据库来“解决”问题。
2. 不得使用 destructive migration。
3. 不得伪造工具数量、大小、签名或权限状态。
4. 不得把耗时文件、ZIP、hash、数据库或网络操作放到主线程。
5. 不得使用 `addJavascriptInterface`、`file://`、localhost server。
6. 不得允许任意外部导航和任意网络。
7. 不得添加 Hilt、Koin、Accompanist、Lottie 或大型 icon 包。
8. 不得一次性重写所有模块。
9. 不得在性能数据缺失时声称“60fps/丝滑”。
10. 不得自动更新 screenshot golden 来掩盖视觉回归。
11. 不得依据缺失的 `toolbox-api.d.ts` 猜测 RPC。
12. 不得把个人模式实现为“信任所有未签名包”。

## 6.2 可接受的行为变化

- 首页不再显示搜索、排序、分类；
- 删除“本机目录”静态标签；
- 删除未实现设置占位行；
- 导入入口从 FAB 移到标题区；
- 工具卡改为列表行；
- 默认运行模式可切换为个人模式，但首次升级保持现有严格行为，必须由用户显式选择个人模式。

## 6.3 需要用户确认后才能执行

- 简化/删除 resumable inspection；
- Origin-only 作为默认运行隔离；
- 自动授权哪些能力；
- 私钥是否由本机生成或外部签名；
- 是否彻底移除深色/Monet 某些模式；
- 是否保留 screenshot test 模块。

---

# 7. 回滚策略

每个 Phase 单独提交，不跨阶段 squash，直到真机验收结束。

建议 tag：

```text
pre-refactor-c0742c4
v2-layout-fixed
v2-ui-grouped
v2-catalog-projection
v2-startup-optimized
v2-personal-mode
v2-lightweight-final
```

数据库变化必须先备份 schema。若某阶段失败：

- UI 阶段：回滚对应 commit，不碰数据库；
- projection 阶段：保留旧 repository 接口一提交周期，支持快速切回；
- startup 阶段：维护任务可恢复为 blocking，但不得破坏目录；
- personal mode：默认关闭 feature flag，立即恢复严格模式；
- Miuix 替换：保留上一 tag，可独立回滚。

---

# 8. 最终 Definition of Done

## 产品

- 首页、工具、设置职责清晰；
- 自用模式减少重复确认；
- 未实现能力不再出现在正常 UI；
- 工具少时不显得空洞，工具多时仍可检索管理。

## 视觉

- 符合 `DESIGN.md`；
- 顶部无突兀白色带；
- 搜索框左侧余量正确；
- 工具行紧凑；
- 底栏内容 56dp + 单一系统导航 inset；
- 无卡片墙和嵌套卡。

## 性能

- 有 baseline/final 真机报告；
- 滚动、Tab、搜索、启动达到门槛或有明确设备解释；
- 无主线程 I/O；
- 无 WebView 线性泄漏；
- Baseline Profile 在根因修复后生成。

## 工程

- 删除空 `tool-api`；
- `core-ui` 合并后模块目标为 4 个核心模块；
- 无 DI 框架；
- 三方 UI 依赖显著减少；
- release minify 通过；
- 文档和代码一致。

## 安全

- always-on boundary 全部保留；
- 个人模式仅对 owner-signed/hash-pinned 工具放宽；
- 未知工具仍审核；
- 权限可撤销；
- 安装、更新、卸载、回滚测试通过。

---

# 9. Codex 每阶段报告格式

Codex 每完成一个 Phase，必须输出：

```markdown
## Phase N 完成报告

### 修改
- 文件：
- 关键行为：

### 删除/简化
- 删除内容：
- 为什么安全：

### 测试
- 命令：
- 结果：

### 性能
- 设备/系统/刷新率：
- 流程：
- Before：
- After：
- 证据路径：

### 数据与兼容性
- DB migration：
- 已安装工具影响：
- 回滚方式：

### 未解决
- 明确列出，不得用“已优化”笼统替代。
```

---

# 10. 首轮执行优先级

Codex 首轮只执行以下内容，避免“大爆炸重写”：

```text
P0-1  建立真机性能 baseline
P0-2  修复 top/bottom/search 高度与 inset
P0-3  删除本机目录和设置占位行
P0-4  将工具卡改为紧凑 ToolRow
P0-5  目录改为单 projection Flow
P0-6  将启动维护延后
```

完成并验证后，再执行 Miuix 替换、模块删除和个人模式。这样可以先解决用户能直接感受到的问题，同时控制回归范围。
