# ToolBox Android：功能优先技术方案

> 版本：2.0-dev
> 目标：Android 13+，优先 Xiaomi / HyperOS
> 设计系统：Miuix `0.9.4-rc01`

## 1. 产品合同

ToolBox 是本地 `.tbx`（HTML/CSS/JavaScript ZIP）的小工具宿主。用户面对的主流程是：

```text
选择 .tbx → 内部检查 → 安装成功/失败 → 打开工具 → 管理权限/后台任务 → 删除
```

宿主不展示安装审核、风险等级、签名/发布者身份、审计日志或恢复会话。它们不属于个人
小工具的可用性路径。内部检查仍是安全边界：不安全、损坏或无法验证的包必须安装失败，
并且不留下文件或数据库残留。

宿主 UI 为 Kotlin Compose + Miuix；导入工具内部 UI 为 HTML，不受宿主视觉系统约束。

### 1.1 当前开发基线

- 这是未发布产品的重构，不保留当前数据库、DataStore、工具、授权或设置。
- 新的 Room schema 是最终新建 schema，`version = 1`；不写 `Migration`、`AutoMigration`、
  `DataMigration`、`fallbackToDestructiveMigration`、旧字段读取或兼容适配器。
- 候选 APK 安装前卸载 `io.toolbox.host`，或明确清除其应用数据；不支持覆盖升级保留数据。
- 删除审计、发布者信任、安装审核会话、恢复审核和虚假设置的模型、repository、界面、
  文档与测试。

### 1.2 不做的事情

- 在线商店、账号、云同步、代码/数据导出、回滚 UI、工具迁移脚本。
- 任意 shell、Dex、JNI、APK、JAR、class、动态原生代码或 WebAssembly JIT。
- `MANAGE_EXTERNAL_STORAGE`、`QUERY_ALL_PACKAGES`、无障碍、短信、联系人、root、后台定位、
  精确闹钟、电池白名单或常驻前台服务。
- 后台 WebView、Service Worker 或任意工具 JavaScript 的无界后台执行。

## 2. 不可协商安全边界

以下规则与 `AGENTS.md` 一致，任何产品简化不得改变它们：

1. 不对导入内容使用 `addJavascriptInterface`。
2. 不以 `file://` 或 localhost/Ktor 加载工具；每个工具使用 `WebViewAssetLoader` 的唯一
   exact HTTPS origin。
3. 使用 `WebViewCompat.addWebMessageListener`；每次调用验证 exact source origin、main
   frame、session nonce、当前工具/版本、manifest 声明、工具 grant、Android 系统权限、
   真实手势、速率和配额。
4. 关闭 WebView file/content access、universal file URL access、mixed content、popup 与任意
   外部导航；不开放 WebView file chooser、媒体权限或自动跳转。
5. 工具网络默认关闭；网络仅由原生 HTTPS 代理执行，必须检查 manifest 域名白名单、每跳
   重定向、DNS 解析地址和私网/保留地址，且不共享 cookie、缓存或认证。
6. 安装是事务性、可补偿的；拒绝 Zip Slip、Zip Bomb、路径碰撞、符号链接、嵌套压缩包、
   原生或动态代码负载。
7. 无效签名必须阻止安装；未签名工具可正常安装，但不会获得默认关闭的外部能力。
8. 不记录 clipboard、文件、secure storage、HTTP 请求/响应 body 的内容。
9. 次进程仅用于崩溃/内存隔离，绝不表述为独立 UID 沙箱。

## 3. 模块与数据归属

```text
:app          Compose 路由、页面、Activity 结果和系统 UI 协调
:core-ui      Miuix 主题与 ToolBox 适配组件
:core-data    Room、DataStore、目录、grant、KV、任务与结果
:tool-package .tbx 复制、检查、完整性/签名和原子安装/卸载
:tool-runtime exact-origin WebView、AssetLoader、CSP、会话与导航
:tool-api     API v1 合同、消息桥、权限策略、capability handler、后台协调
```

Room 只包含：`tools`、`tool_versions`、`permission_grants`、`tool_kv`、
`install_transactions`、`background_tasks`、`task_results`。安全存储的
密钥材料留在 Android Keystore，不落为明文表。

DataStore 只包含 `theme` 和 `backgroundEnabled`。工具数量、KV 限额、速率、任务数量与
响应大小是代码中的内部常量，不伪装成用户设置。

所有文件、解压、哈希、Room、DataStore 和网络工作在 IO dispatcher；Compose 只观察
可投影的状态流。

## 4. `.tbx` 导入与生命周期

### 4.1 包格式与内部检查

基础包格式保持：`manifest.json`、入口 HTML、静态资源，`integrity.json` 和
`signature.json` 可选。检查器必须在 app-private 临时目录中执行：

- 规范化 ZIP 路径，拒绝绝对路径、`..`、反斜杠绕过、大小写/Unicode 冲突、链接和嵌套归档；
- 限制文件数、单文件、压缩比和总解压大小，拒绝 APK/DEX/SO/JAR/class 与动态代码；
- 严格验证 manifest、入口、允许的 capability、版本、网络域名和资源集合；
- 存在 `integrity.json` 时，验证其覆盖的原始文件 SHA-256；
- 不带签名时正常继续；存在 `signature.json` 时，使用包内 Ed25519 public key 验证
  `integrity.json` 原始字节。不存在、格式错误、key ID 不匹配或签名失败均为阻断。

不保存发布者、签名指纹、风险结论或审核会话。签名验证只回答“该包带来的签名是否有效”，
不建立发行者信任系统。

### 4.2 一步安装

1. `ACTION_OPEN_DOCUMENT` 选择单个 `.tbx`，拷贝到私有导入临时目录。
2. 在后台完成检查并生成仅供安装器使用的不可变结果。
3. 按 manifest 和默认策略生成初始工具 grants。
4. 在文件 staged 目录与 Room 事务都成功后才切换为 active version。
5. 成功时回到工具列表并显示简短成功反馈；失败显示可操作原因，删除 staged 目录、临时
   文件和未提交记录。

没有“确认审核”“选择安装权限”“继续恢复审核”页面。若应用启动时发现未完成安装事务，
内部清理它；无法恢复的外部 URI 只提示用户重新选择文件。

### 4.3 版本、卸载与清理

- 仅更高版本可更新；同版本或低版本显示简短失败，不写入任何状态。
- 更新原子替换 active version。普通 KV 保留；旧 generation 的 secure-storage key、临时
  file token、runtime session、后台任务/结果和后台通知清理。首版没有回滚。
- 卸载从真实详情菜单触发，并清理代码、KV、grants、Keystore key、WebView Profile/无状态
  记录、会话、后台 Work、任务结果、后台通知与快捷方式。

## 5. App 式每工具权限

Android 只能把运行时权限授予宿主包，不能把它直接授予单个 HTML 工具。因此 ToolBox
使用每工具虚拟 grant，而不是伪造 Android 系统权限页面。

```text
effective capability = manifest declaration
                     ∧ per-tool enabled grant
                     ∧ host Android/system availability
                     ∧ foreground/user-gesture/context rule
                     ∧ rate/quota policy
```

权限页读取当前安装版本的 manifest 声明 left join grant；缺失记录就是默认策略。每行是
Miuix `ToolBoxSwitchSettingRow`，整行与开关都可操作。只在 handler 已实现时显示开关；
未实现或系统不支持的 optional capability 显示为不可用，`required=true` 则令安装失败。

默认打开：`storage`、`storage.secure`、`device.basic`、`clipboard.write`、`haptics`。
`network`、`notifications`、`background.tasks`、文件、分享、读取剪贴板、相机、定位和
快捷方式默认关闭。切换系统型能力时使用 Activity Result API 请求真实系统授权；拒绝就不
写工具 grant，并提供前往系统设置的明确路径。回到前台和每次副作用调用前重算系统状态。

关闭 grant 立即停止新调用；关闭 `storage.secure` 同时销毁该工具 generation 的 key/数据；
关闭后台总开关或 `background.tasks` 取消工具任务并只撤销该路径创建的通知。

## 6. ToolBox API v1 与运行时

### 6.1 单一协议来源

`tool-api` 内的机器可读 API v1 文件是唯一合同来源。Gradle 生成或严格比对 Kotlin
capability descriptor、JS shim method table、`sdk/toolbox-api.d.ts` 和 manifest capability
枚举；CI 必须在生成物漂移时失败。不得依靠示例或旧缺失 SDK hash 阻断实现。

每个 handler 在 dispatcher 中按顺序检查：支持情况 → 声明 → grant → 系统状态 → 手势/
上下文 → 速率/配额 → handler。失败返回稳定结构化错误，不返回伪成功。

### 6.2 M1 基础 API

| API | capability | 关键限制 |
|---|---|---|
| `ready` | 无 | 返回 host/API/tool generation，不泄露设备标识。 |
| `ui.toast` | 无 | 文本长度限制，Toast 不代表业务完成。 |
| `crypto.sha256` | 无 | 对上限内 UTF-8/字节求摘要。 |
| `storage.*` | `storage` | key 长度、JSON 值与每工具 quota 限制。 |
| `storage.secure.*` | `storage.secure` | 每工具/版本 Keystore 隔离；关闭/更新/卸载销毁。 |
| `device.basic` | `device.basic` | 仅 API level、locale、timezone、screen class。 |
| `haptics.perform` | `haptics` | 枚举效果并限流，需要近期真实触摸。 |
| `clipboard.writeText` | `clipboard.write` | UTF-8 上限，需要近期真实触摸。 |

运行时使用 `WebViewAssetLoader` 的唯一 exact HTTPS origin 和
`WebViewCompat.addWebMessageListener`。document-start shim 只向当前源和 session 暴露
nonce 绑定消息接口。iframe、错误 source origin、非 main-frame、导航后的旧 nonce、未声明
方法、过期版本和缺失手势都必须被拒绝。真实手势由宿主记录 WebView `MotionEvent`，不是
JS 传入字段。会话结束、工具切换和 Activity 销毁取消未完成请求并删除临时句柄。

运行容器不使用宿主底栏：顶部约 48dp，只放返回、标题、刷新/更多；其余区域由 WebView
占满，不显示 origin/API/安全技术副标题。仍必须保留 CSP、安全响应头、危险 scheme 和导航
阻断、renderer-gone 恢复、文件/content/mixed-content/popup 禁用等边界。

### 6.3 M3 App 式 API

- `clipboard.read`：一次性真实手势 + 原生确认，绝不记录读取内容。
- `share`：系统 Sharesheet，仅 text/允许的 FileToken，不构造任意 intent。
- `files.open` / `files.save`：SAF、短期不可伪造 FileToken，不暴露路径、不持久化 URI grant。
- `shortcuts`：显式 MainActivity intent 携带不透明 tool ID，启动时重新验证 generation。
- `camera`：系统拍照 contract + exported=false FileProvider 临时 URI，不开放 WebView 摄像头。
- `location`：仅前台单次 coarse/fine，带超时，不支持后台定位。

## 7. 委派后台任务、网络和通知

后台是宿主能力，不是工具代码运行环境。采用固定版本的 WorkManager `2.11.2` 和 OkHttp
`5.3.0`，版本经版本目录锁定。WorkManager 合并的内部 receiver/service 可以存在，但应用
不自行从 boot broadcast 运行工具代码，不调用 `setForeground`，不申请长期后台权限。

### 7.1 JS API 与数据模型

```text
enqueue(spec) → taskId
schedulePeriodic(spec) → taskId
list() → TaskSummary[]
getResult(taskId) → TaskRunResult | null
cancel(taskId)
```

`TaskState` 为 `QUEUED | RUNNING | COMPLETED | CANCELLED`；`RunOutcome` 为
`SUCCEEDED | FAILED | CANCELLED`。一次性任务结束为 `COMPLETED` 或 `CANCELLED`；周期任务
每次运行结束回到 `QUEUED`，并保存最近一次 `TaskRunResult`。

首版 spec 仅支持：

- `httpGet`：HTTPS URL（最大 2048 字符）、文本/JSON、无请求 body/认证/cookie，受
  `network` + `background.tasks` 和 manifest allowlist 约束；请求和解码后响应各不超过
  256 KiB。
- `notify`：title ≤64、body ≤256、可指定 not-before；受 `notifications` +
  `background.tasks` + Android 通知授权约束。

每工具最多 8 个活动任务、4 个周期任务；周期下限 15 分钟；工具内串行、全局最多两个并发；
spec 上限 64 KiB、结果上限 256 KiB、每工具结果总量 2 MiB、结果保留 7 天。

### 7.2 调度、重试与清理

Room 是期望状态权威：先持久化任务/唯一 key，再 enqueue unique Work；启动 reconciler 修复
崩溃窗口。worker 在 IO 线程重新打开数据源，并在每个副作用前重新验证 active generation、
manifest、grant、后台总开关、系统能力与配额。

- HTTP 2xx 成功；4xx 记录失败且不自动重试；5xx、网络 I/O、超时最多指数退避三次。
- 周期任务失败记录本次失败，等待下一个周期，不进行紧密重试。
- 权限关闭、后台总开关关闭、版本替换、卸载或用户 cancel：标记取消、释放唯一 key、取消
  Work，不重试。
- 后台总开关仅取消后台任务产生的通知，不影响前台 notification API 的未来实现。

### 7.3 HTTPS 代理和 SSRF

代理禁用自动重定向、cookie、缓存、自动 retry 和宿主认证。每次请求使用受检查的 DNS 地址；
TLS hostname/SNI 与 manifest host 一致。每一跳重定向重新验证 HTTPS、显式 allowlist、端口
和全部解析地址，拒绝 loopback、link-local、private、CGNAT、multicast、unspecified、IPv4-
mapped 私网 IPv6 等地址，防止 DNS TOCTOU/SSRF。

## 8. Miuix 页面与布局

- 使用 Miuix `0.9.4-rc01` 与 `miuix-nav`，所有业务页面只依赖 ToolBox 适配组件；不叠加
  第二套导航动画。
- 主导航仅“工具”“设置”；导入放工具页顶部操作区。底栏视觉内容约 56dp，系统手势/导航
  inset 只由一个 surface 消费一次，保留设备自己的手势小白条。
- 工具列表使用 stable key/content type；一个滚动轴只有一个 Lazy 容器；图标/文件/数据库
  和网络不在主线程；Tab 切换保留页面滚动状态。
- 普通行 64–80dp、搜索框 48dp、最小触控目标 48dp。字体可换行/自然增高，禁止把顶栏、
  搜索框或底栏乘以 `fontScale`。
- 每页状态栏、cutout、IME、导航/手势 inset 只能消费一次；IME 只由有输入焦点的内容区处理。
- Tab 使用短淡入，详情进入/返回使用 Miuix 方向一致转场；系统关闭动画时禁用非必要动效。

设置最终只显示真实功能：主题、后台总开关、Developer Help。Developer Help 是离线原生页面，
从 API v1 合同和三个实际范例派生，说明包目录、manifest、permissions、API、打包、导入、
后台限制和错误码；首页空状态与帮助页可触发“安装三个范例”，走同一导入器。

## 9. 三个示例

1. **仓位计算器**：真实计算、保存输入、复制结果、触觉反馈与 toast；使用 `storage`、
   `clipboard.write`、`haptics`。
2. **快速笔记**：创建、编辑、删除、重开恢复、复制；使用 `storage`、`clipboard.write`。
3. **后台任务演示**：创建/查看/取消受控 HTTP 与通知任务、显示最近结果；固定 URL 为
   `https://api.github.com/repos/gkeyes/ToolBox-Android`，allowlist 仅 `api.github.com`，使用
   固定 host User-Agent；使用 `background.tasks`、`network`、`notifications`。

每个示例交付源码目录、manifest、integrity、可重复打包脚本和 `.tbx`。示例只能在真实 API
可用时声称功能完成，不以 feature-detect 后禁用按钮作为交付。

## 10. 最小验证与交付

每个自动化测试都必须在 `TESTING.md` 记录理由、方法和预期结果；使用最低忠实层和参数化
矩阵，删除审计、审核、发布者和旧迁移功能的测试，不保留纯文案/占位测试。

| 场景 | 理由 | 方法 | 预期 |
|---|---|---|---|
| 新鲜数据基线 | 防止无用兼容代码残留。 | 创建/重开 production Room/DataStore，写工具、grant、KV、任务、结果并检查 schema/keys。 | 真实状态持久化；没有 audit/publisher/旧设置/迁移。 |
| 导入与卸载 | 保证核心“成功或失败”和真实删除。 | 导入三个有效例子、损坏包与现有恶意 ZIP 矩阵，再从菜单删除。 | 有效包可打开；无效零残留；删除完整清理。 |
| 权限与 RPC | 防止开关和功能脱节。 | 逐 capability 调 production dispatcher，并关闭每一个授权层。 | 开启有真实结果；任一层缺失稳定拒绝。 |
| 后台与代理 | 防止虚假后台或 SSRF。 | WorkManager TestDriver + Room + 可注入传输/DNS，覆盖状态、重试、取消、私网/重定向和清理。 | 正确状态/结果；无 WebView、SSRF、孤儿 Work/通知。 |
| Miuix 真机旅程 | 验证卡顿、inset 和系统 UI。 | 小米机：干净安装、三个例子、权限、运行、复制、系统 surface、后台、删除，含大字体。 | 控件都有效；内容优先；无双 inset/明显卡顿。 |

GitHub Actions 的顺序固定为：编译/最小单元测试 → 模拟器关键宿主流程 → APK 产物。产物 job
必须依赖前两项成功，最终上传 `toolbox-v0.2.0-debug.apk`、三个 `.tbx`、`SHA256SUMS.txt` 和
构建/测试回执。模拟器不替代相机、SAF、Sharesheet、快捷方式和 Xiaomi 系统栏的真机组合旅程。

## 11. 完成条件

- 一步导入、工具列表、打开、权限切换、后台任务和删除均为真实功能，无审核/审计/签名 UI
  或无效按钮。
- 三个范例可由干净安装的候选 APK 导入并完成各自声明功能。
- WebView、消息桥、包检查和网络代理符合第 2 节不变量。
- 每项保留测试有理由/方法/预期；最小相关构建、测试和真实 Xiaomi 组合旅程有证据。
- GitHub 只在所有前置门通过后发布 APK 和范例产物。
