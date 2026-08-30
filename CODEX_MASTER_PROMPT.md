# Codex 主执行提示词：ToolBox Android V2 轻量化重构

你正在修改现有仓库：

```text
https://github.com/gkeyes/ToolBox-Android
```

目标分支：

```text
refactor/lightweight-v2
```

审查基线：

```text
main@c0742c46de95d132b4591864dfbc429b9cd84015
```

开始前必须完整读取：

```text
AGENTS.md
PRODUCT.md
README.md
DESIGN.md
CODEX_REFACTOR_PLAN.md
TESTING.md
```

其中：

- `DESIGN.md` 是新的唯一视觉基线；
- `CODEX_REFACTOR_PLAN.md` 是阶段、边界、性能和验收基线；
- 当前旧文档如与这两份新文件冲突，先在变更说明中指出冲突，再以用户最新需求和新的两份文件为准；
- 安全结构边界不得因“自用”而未经说明直接删除。

---

## 一、任务目标

在保留已安装工具、目录数据库、包安装事务和必要 WebView 隔离的前提下，完成：

1. 修复顶部白色背景带、搜索图标贴边、工具卡过高、底栏过高；
2. 将宿主 UI 改为紧凑 grouped-list 风格，视觉接近 iOS 的克制与清晰，但保持 Android 原生行为；
3. 解决滚动、Tab 切换、搜索和启动的卡顿；
4. 删除空模块、无效占位、重复抽象和不必要三方 UI 依赖；
5. 增加“个人模式 / 严格模式”，让 owner-signed 自有工具减少确认；
6. 形成可复现的 before/after 真机性能证据；
7. 每个阶段独立提交、可独立回滚。

---

## 二、禁止事项

不得：

- 一次性重写整个项目；
- 删除用户数据库或使用 destructive migration；
- 伪造工具、数量、大小、签名、授权状态；
- 主线程执行文件、ZIP、hash、数据库、网络操作；
- 使用 `addJavascriptInterface`、`file://`、localhost/Ktor server；
- 取消 exact HTTPS origin、危险导航阻断、Zip Slip/Zip Bomb 防护；
- 引入 Hilt、Koin、Accompanist、Lottie、大型 icon 包；
- 根据缺失的 `toolbox-api.d.ts` 自行猜测 RPC；
- 只跑 build 就声称“丝滑/60fps”；
- 未人工检查前自动接受新的截图 golden；
- 将个人模式实现为“信任所有未签名包”；
- 把 `tool-package` 和 `tool-runtime` 粗暴合并进 `app`。

---

## 三、执行顺序

严格按阶段执行。每阶段：修改 → 编译 → 测试 → 真机/模拟器证据 → 提交 → 报告。上一阶段未通过，不进入下一阶段。

### Phase 0：建立基线

1. 创建分支和 pre-refactor tag。
2. 记录模块、依赖、数据库 schema、当前 WebView provider。
3. 新建：

```text
docs/refactor/CURRENT_STATE.md
docs/performance/BASELINE.md
scripts/perf/capture-host.sh
```

4. 固定测试流：冷启动、Tab 切换、20/50/100 项滚动、搜索、10 次开关工具。
5. 使用 gfxinfo；可用时捕获 Perfetto 和 meminfo。
6. 不改 UI 行为。

提交：

```text
chore(perf): capture baseline before lightweight refactor
```

### Phase 1：修复布局与 inset

重点文件：

```text
core-ui/.../ToolBoxLayout.kt
core-ui/.../ToolBoxInsets.kt
core-ui/.../ToolBoxRows.kt
app/.../HostNavigationChrome.kt
```

必须完成：

- 删除所有尺寸 `* fontScale`；
- 顶部内容区 52–56dp；
- 底栏内容区固定 56dp；
- navigation bar inset 只消费一次；
- IME 不扩大底栏；
- 主页面顶部与 canvas 同色；
- 搜索框 48dp，左侧 14–16dp；
- 手势导航、三键导航、字体 1.0/1.3/2.0 验证。

提交：

```text
fix(ui): correct chrome sizing and inset ownership
```

### Phase 2：重构首页、工具页、设置页

按 `DESIGN.md` 实现：

- 首页只保留常用、最近、导入；
- 删除首页汇总卡和“本机目录”；
- 工具页集中搜索、筛选、排序；
- 工具项改为 72–80dp `ToolRow`；
- 更多菜单取代“详情”文字按钮；
- 设置页删除两个 `StaticSettingsStatus` 占位；
- 引入 `GroupedSurface`，禁止每行一张大卡；
- 不做无行为控件。

提交：

```text
feat(ui): redesign host as compact grouped tool shelf
```

### Phase 3：目录数据流与 Compose 性能

必须完成：

- Room 单一 catalog projection JOIN query；
- 删除 per-tool `observeVersions()` Flow combine；
- query/category/sort 在 ViewModel combine；
- 搜索 debounce 100–150ms；
- `visibleTools` 作为状态字段，只计算一次；
- 合理使用 `@Immutable`；
- Lazy item 添加 `key` 和 `contentType`；
- 保留每个主 Tab 的 `LazyListState`；
- Tab 切换不重启目录订阅。

提交：

```text
perf(catalog): replace per-tool flows with one stable projection
```

### Phase 4：启动与 WebView 生命周期

必须完成：

- AppContainer 只创建必要依赖；
- 首页不等待 orphan profile/inspection/audit 维护；
- 维护任务首帧后执行；
- 维护失败不阻止进入宿主，只在相关操作时明确提示；
- 添加 trace sections；
- 10 次开关工具后验证 WebView/PSS 不线性增长；
- 若修改 profile 策略，先写 ADR。

提交：

```text
perf(startup): defer noncritical maintenance past first frame
```

### Phase 5：个人模式与严格模式

必须完成：

- 设置真实持久化；
- owner-signed 工具可简化审核和按白名单自动授权；
- 未签名包只能按具体 hash 记住信任；
- 文件或签名变化立即重新审核；
- always-on boundary 保留；
- 可一键撤销 owner trust 并恢复严格模式。

私钥方案不明确时不要自行生成并落盘。先写 ADR，说明 Android Keystore、外部签名和仅保存公钥的方案，再选择。

提交：

```text
feat(trust): add owner-signed personal mode without weakening runtime boundaries
```

### Phase 6：模块与依赖精简

顺序：

1. 删除空 `:tool-api`；
2. 在 `core-ui` 公共 API 不变的前提下，用稳定 Compose 实现替换 Miuix；
3. 移除 `material-icons-extended`，使用少量本地 vector；
4. 真机和截图通过后，把 design system 移入 `app`；
5. 删除 `core-ui` Gradle 模块；
6. 输出依赖、模块、APK 和构建耗时 before/after。

不要同时改数据库或运行时安全代码。

提交拆分为：

```text
build: remove dormant tool-api module
refactor(ui): replace experimental Miuix implementation
build: fold compact design system into app module
```

### Phase 7：CI、测试和最终证据

- 快速 CI：security scan、assemble、unit、lint；
- 完整 CI：截图、instrumentation、WebView、安全矩阵、benchmark；
- 保留高价值安全测试；
- 删除旧 UI screenshot golden 和重复低价值测试；
- 生成 Baseline Profile；
- 完成 `docs/performance/FINAL.md`。

---

## 四、性能门槛

以目标 HyperOS 真机为准，报告设备、Android、刷新率、构建类型和运行次数。

目标：

```text
稳定滚动 janky frames <= 3%
无单帧 > 50ms
Tab 切换 p95 <= 120ms
搜索 debounce 后更新 p95 <= 100ms
warm start 中位数 <= 450ms 或较基线改善 >= 30%
cold start 中位数 <= 900ms 或较基线改善 >= 30%
10 次开关工具后内存不持续线性增长，建议净增 <= 10MB
```

无法达到时不得修改数字或隐藏结果，必须给出 trace 证据、瓶颈和下一步。

---

## 五、测试命令

快速门槛：

```bash
./gradlew --no-daemon \
  verifySecurityInvariants \
  assembleDebug \
  testDebugUnitTest \
  lintDebug
```

完整门槛：

```bash
./gradlew --no-daemon \
  validateDebugScreenshotTest \
  :app:connectedDebugAndroidTest \
  :core-data:connectedDebugAndroidTest \
  :tool-runtime:connectedDebugAndroidTest
```

若任务名因版本变化不同，先列出可用 Gradle task 并在报告中说明，不要静默跳过。

---

## 六、每阶段输出格式

```markdown
## Phase N 完成报告

### 修改文件
- ...

### 行为变化
- ...

### 删除与简化
- ...

### 测试
- 命令：
- 结果：

### 性能
- 设备：
- 构建：
- 流程：
- Before：
- After：
- 证据路径：

### 数据兼容
- Schema/migration：
- 已安装工具影响：
- 回滚方法：

### 风险与未解决项
- ...
```

---

## 七、首轮执行范围

本轮优先完成并提交 Phase 0–4。Phase 5–7 在前四阶段真机结果稳定后继续。

不得只修改文档。必须形成可运行代码、测试结果、截图对比和性能证据。
