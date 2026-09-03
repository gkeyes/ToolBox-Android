# ToolBox Android：功能优先技术方案

> 版本：3.1-dev
> 目标：Android 13+，优先 Xiaomi / HyperOS
> 设计系统：Miuix `0.9.4-rc01`

## 1. 产品合同

ToolBox 是本地 `.tbx`（HTML/CSS/JavaScript ZIP）的小工具宿主。用户面对的主流程是：

```text
选择 .tbx → 内部检查 → 安装成功/失败 → 打开工具 → 管理权限/后台保障 → 删除
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
- 任意 shell、Dex、JNI、APK、JAR、class、动态原生代码、Root 命令接口或 WebAssembly JIT。
- `MANAGE_EXTERNAL_STORAGE`、`QUERY_ALL_PACKAGES`、无障碍、短信或联系人权限。
- Service Worker、无用户启动会话的后台 WebView，或没有持续通知和停止入口的无界后台执行。
- 宿主不增加行情、轨迹、行程、Token 专用表或业务通知判断；独立 `.tbx` 的业务状态仍由工具自己保存和解释。

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
5. 工具网络默认关闭；授权后由原生 HTTPS 代理访问 manifest 精确声明的公网 HTTPS 域名及
   合法端口。每跳重定向、DNS 解析地址和私网/回环/保留地址仍必须复验，
   且代理不使用宿主 cookie、缓存或认证状态。
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

0.3 不改变 Room schema，继续为 v1。持续会话与闹钟仅保存宿主恢复所需的最小描述符，并复用
现有 KV 物理存储；会话只保存 sessionId、启动时间和恢复选项，闹钟只保存 id 与调度时间，
不保存轨迹、行情、行程、通知正文或其他业务 payload。

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
`network`、`notifications`、`background.tasks`、`background.runtime`、`location.background`、
`alarms`、文件、分享、读取剪贴板、相机、定位和快捷方式默认关闭。切换系统型能力时使用 Activity Result API 请求真实系统授权；拒绝就不
写工具 grant，并提供前往系统设置的明确路径。回到前台和每次副作用调用前重算系统状态。

关闭 grant 立即停止新调用；关闭 `storage.secure` 同时销毁该工具 generation 的 key/数据；
关闭后台总开关会取消旧任务并停止所有持续环境、计时器、后台位置监听和对应通知；关闭
`background.runtime`、`location.background` 或 `alarms` 只清理对应运行资源。

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
JS 传入字段。没有持续会话时，工具切换或界面销毁会关闭运行时；存在持续会话时，Compose
仅卸载显示层，WebView、permit、bridge、generation、timer 与位置监听继续由应用级管理器拥有。

耗时计算使用随 `.tbx` 安装的同源静态 Web Worker，不占用页面的 DOM/渲染线程；CSP 仅允许
`worker-src 'self'`，远程、`blob:`、`data:` Worker 和 ServiceWorker 继续阻断。Worker 不暴露
ToolBox bridge，结果经 `postMessage` 返回顶层页面后再调用原生能力。宿主 RPC 的 UTF-8 计量、
JSON 解码、摘要与 handler 调度离开 UI 回调线程，并以保留系统渲染余量的有限并发执行；文件、
数据库和网络 handler 继续切换到 IO dispatcher。工具计算量不由该 RPC 并发上限约束。

运行容器不使用宿主底栏：顶部约 48dp，只放返回、标题、刷新/更多；其余区域由 WebView
占满，不显示 origin/API/安全技术副标题。仍必须保留 CSP、安全响应头、危险 scheme 和导航
阻断、renderer-gone 恢复、文件/content/mixed-content/popup 禁用等边界。

### 6.3 M3 App 式 API

- `clipboard.read`：一次性真实手势 + 原生确认，绝不记录读取内容。
- `share`：系统 Sharesheet，仅 text/允许的 FileToken，不构造任意 intent。
- `files.open` / `files.save`：SAF、短期不可伪造 FileToken，不暴露路径、不持久化 URI grant。
- `shortcuts`：显式 MainActivity intent 携带不透明 tool ID，启动时重新验证 generation。
- `camera`：系统拍照 contract + exported=false FileProvider 临时 URI，不开放 WebView 摄像头。
- `location`：前台单次定位和前台 watch；宿主只透传系统位置，不生成或保存轨迹。
- `location.background`：仅与 `location`、`background.runtime` 和 Android 后台位置授权同时
  生效，使已有 watch 可在持续环境中接收位置。
- `alarms`：登记、列出和取消精确闹钟；宿主只持久化 id 与时间，触发时发送事件或普通通知。

### 6.4 Host → Web 事件通道

所有 `background.restore`、`background.timer`、`location.onChanged` 和 `alarm` 使用同一个
`RuntimeEventChannel` envelope：事件名、runtime generation、时间戳和 data。事件只发送给当前
exact-origin 主 frame 的 bridge session。页面完成 `ToolBox.ready()` 后才冲刷 native 队列；若
JS 监听器尚未注册，document-start shim 还会保留有限早到事件，避免恢复事件在首屏加载时丢失。

## 7. 0.3 后台、网络和通知

### 7.1 持续网页运行环境

`RuntimeSessionManager` 是进程级资源所有者，内部状态为
`CREATING → ATTACHED → BACKGROUND_DETACHED → RESTORING → STOPPED/FAILED`。`background.start`
是当前 runtime 的幂等 promotion，重复调用返回原 sessionId，不创建第二个 WebView；
`background.listSessions()` 与旧任务 `background.list()` 分开。

所有持续环境由一个 `specialUse` 前台 Service 统一保障；存在后台位置 watch 时叠加 `location`
类型。Service 使用持续通知提供打开、停止单个环境和全部停止入口。系统回收后根据持久化描述符
重建页面并发送 restore 事件，重启恢复是 best effort；系统拒绝后台启动时使用普通通知引导用户
打开应用。每个连续会话运行 12 小时提醒一次，之后每 12 小时重复。不设置宿主固定会话数量，
但仍受 Android 内存、前台服务和权限规则约束。

工具更新或卸载前，包生命周期必须先停止该 toolId 的全部 session，释放 WebView、permit、
bridge、timer、位置监听和通知，再删除旧 bundle。没有持续会话时离开页面仍立即释放。

### 7.2 位置与精确闹钟

`location.watch/clearWatch` 直接映射 Android 精度、间隔、最小位移和后台选项，不建立轨迹模型。
后台 watch 需要同时声明并授权 `location`、`location.background`、`background.runtime`，且宿主
拥有前台与后台位置系统权限。停止 watch、会话、工具或授权时立即注销监听。

`alarms.schedule/list/cancel` 使用 `SCHEDULE_EXACT_ALARM` 和 `AlarmManager`。运行环境存在时发送
`alarm` 事件；不存在时显示不含业务内容的普通通知，点击重新打开对应工具。登记数据只有
alarmId、triggerAt、scheduledAt；开机和精确闹钟授权变化后重新调度。

### 7.3 通用公网 HTTPS 代理

`network.request` 支持 GET、POST、PUT、PATCH、DELETE、HEAD，自定义普通 Header、文本/JSON/
字节请求体、合法 HTTPS 端口、重定向、超时和响应上限。普通 `network` grant 还必须服从
`manifest.network.allowDomains` 的精确域名或子域通配声明。直接请求会把
4xx/5xx 状态和受限响应正文返回页面，不把 HTTP 错误伪装成安全阻断。

代理仍禁用自动重定向、缓存、自动 retry、系统代理和宿主认证状态。Host、Connection、
Content-Length、Transfer-Encoding、Upgrade 与 Proxy 系列协议 Header 由传输层控制；Authorization、
Cookie、X-API-Key、Accept、Content-Type 等可由工具提供。每次 DNS 与每跳重定向都重新验证
HTTPS、manifest allowlist 和解析地址；loopback、link-local、private、CGNAT、multicast、保留地址、
IP 字面量、IPv4-mapped 私网 IPv6 和 NAT64 私网映射均阻断。读取响应的有效上限是请求值、manifest
网络值、WebMessage 上限和宿主防 OOM 上限的最小值，不为文本预扣 Base64 膨胀空间。
0.3.5 允许 manifest 将消息上限从默认 256 KiB 提高到最多 8 MiB；返回前仍检查实际编码后的
完整消息。响应或消息过大返回 `QUOTA_EXCEEDED`，连接/读取失败与超时分别返回
`NETWORK_UNAVAILABLE`、`NETWORK_TIMEOUT`；只有地址或重定向策略拒绝才返回 `NETWORK_BLOCKED`。

### 7.4 旧后台任务兼容

WorkManager `background.enqueue/schedulePeriodic/list/getResult/cancel` 冻结为 0.2 legacy API。
其 `httpGet` 仍要求工具原有 allowlist，仅 2xx 成功；4xx 不重试，5xx/网络/超时最多退避三次。
每工具 8 个活动任务、4 个周期任务和 15 分钟周期下限只属于 legacy task，不适用于持续 runtime。

### 7.5 实时通知与 HyperOS 增强

普通 Android Notification 是 canonical 结果；`notifications.post/update/cancel` 始终使用它。
工具先用 `background.start` 获得当前工具和 generation 的 sessionId，再通过
`notifications.live.start/update/end` 为该持续会话维护一个实时展示。`LiveNotificationCoordinator`
只在内存中保存最新标题、主值、辅助信息、正文、短文本、更新时间、进度、强调色和 tone；500ms
合并窗口只减少 SystemUI 刷新，不限制页面计算或网络请求。停止会话、关闭通知授权、关闭后台总开关、
更新或删除工具时同步清理展示状态；进程恢复先显示通用占位，页面收到 `background.restore` 后重新发布。

前台 Service 使用固定通知 ID 和 `toolbox.runtime.live.v1` 稳定通道。单会话直接显示工具数据；多会话
由最近更新项作为主展示，展开正文列出其他会话。通知的打开、停止当前和全部停止操作均由宿主构造，
工具不能传入 PendingIntent。Android 16+ 在系统允许时请求 promoted ongoing；HyperOS 检测到焦点协议
就使用 Apache-2.0 `focus-api:1.4` 附加完整 Focus V3 ticker、AOD、焦点文本与大小岛数据，且
`filterWhenNoPermission=false`。`canShowFocus=false` 只作为返回状态，不阻止提交。增强接口的
`REQUESTED` 仅表示数据已交给系统；协议不存在、权限不足或系统不展示时，普通持续通知仍然成立。

实时活动使用深色展示面，宿主可控制的标题、主值、辅助信息、正文和操作文字固定为白色；
该规则同样覆盖准备中、进程恢复占位和多会话摘要。原生通知通过文字颜色 span 显式指定，Focus V3
的明暗两套文字字段均为 `#FFFFFF`，大岛文本不继承强调色。普通非实时通知仍遵循系统主题，
本规则不修改权限、通知内容或 API 合同；系统自绘的时间及其他装饰仍由 SystemUI 控制。

## 8. Miuix 页面与布局

- 使用 Miuix `0.9.4-rc01` 与 `miuix-nav`，所有业务页面只依赖 ToolBox 适配组件。一级页面使用
  Miuix Nav；二级页面由宿主常驻分层渲染，底页保持组合和测量结果，进入与返回只移动最上层，
  不叠加 Navigation3、`AnimatedContent` 或第二套双页动画。
- 主导航仅“工具”“设置”；导入放工具页顶部操作区。底栏视觉内容约 56dp，系统手势/导航
  inset 只由一个 surface 消费一次，保留设备自己的手势小白条。
- 工具列表使用 stable key/content type；一个滚动轴只有一个 Lazy 容器；图标/文件/数据库
  和网络不在主线程；Tab 切换保留页面滚动状态。
- 普通行 64–80dp、搜索框 48dp、最小触控目标 48dp。字体可换行/自然增高，禁止把顶栏、
  搜索框或底栏乘以 `fontScale`。
- 每页状态栏、cutout、IME、导航/手势 inset 只能消费一次；IME 只由有输入焦点的内容区处理。
- Tab 使用短淡入，详情进入/返回使用同一 Miuix easing。WebView 运行层不参与 Compose 页面
  变形：进入工具时先完成轻量原生页面壳转场，随后才创建 WebView，首帧完成后原地揭示；返回时
  由保留的原生源页面覆盖运行层后释放 WebView。系统关闭动画时禁用非必要动效。

设置最终只显示真实功能：主题、后台保障、工具权限、Developer Help。后台保障集中管理总开关、
持续会话、通知、后台定位、精确闹钟、电池策略、HyperOS 自启动/省电入口和实时通知增强状态。
Developer Help 是离线原生页面，使用 `sdk/help/manual.md` 作为 App 与仓库共用的正文来源。
章节、主题默认折叠且同级互斥展开，搜索匹配标题、正文和代码；代码可复制，隐藏内容不参与
组合。读取与解析在 IO dispatcher 完成，不引入 Markdown UI 框架。手册覆盖完整最小工程、
manifest、权限、网络、后台生命周期、普通/实时通知、系统能力、打包、导入和错误排查。
`scripts/check-developer-help.mjs` 校验嵌入源码与模板、通用打包器和 TypeScript SDK 一致，
并通过模拟桥执行基础与后台代码示例；它不替代 Android 验证。`scripts/package-tool.py`
独立打包任意静态工具目录，四个内置范例仍使用原打包路径。首页空状态与帮助页继续提供
“安装四个范例”，走同一导入器。

## 9. 四个示例

1. **仓位计算器**：真实计算、保存输入、复制结果、触觉反馈与 toast；使用 `storage`、
   `clipboard.write`、`haptics`。
2. **快速笔记**：创建、编辑、删除、重开恢复、复制；使用 `storage`、`clipboard.write`。
3. **后台任务演示**：创建/查看/取消受控 HTTP 与通知任务、显示最近结果；固定 URL 为
   `https://api.github.com/repos/gkeyes/ToolBox-Android`，allowlist 仅 `api.github.com`，使用
   固定 host User-Agent；使用 `background.tasks`、`network`、`notifications`。
4. **通知实验室**：验证普通通知发布/更新/取消、会话绑定实时通知、后台计时原位更新、
   Android Live 状态与 HyperOS 超级岛状态；使用 `storage`、`notifications`、`background.runtime`。

四个示例继续保留源码目录、manifest、integrity、可重复打包脚本和 APK 内置 `.tbx`，不把它们
作为本轮独立交付物。行情哨兵要求至少 0.3.2 宿主，继续独立交付且不加入 APK assets。

## 10. 最小验证与交付

每个自动化测试都必须在 `TESTING.md` 记录理由、方法和预期结果；使用最低忠实层和参数化
矩阵，删除审计、审核、发布者和旧迁移功能的测试，不保留纯文案/占位测试。

| 场景 | 理由 | 方法 | 预期 |
|---|---|---|---|
| 新鲜数据基线 | 防止无用兼容代码残留。 | 创建/重开 production Room/DataStore，写工具、grant、KV、任务、结果并检查 schema/keys。 | 真实状态持久化；没有 audit/publisher/旧设置/迁移。 |
| 导入与卸载 | 保证核心“成功或失败”和真实删除。 | 导入四个有效例子、损坏包与现有恶意 ZIP 矩阵，再从菜单删除。 | 有效包可打开；无效零残留；删除完整清理。 |
| 权限与 RPC | 防止开关和功能脱节。 | 逐 capability 调 production dispatcher，并关闭每一个授权层。 | 开启有真实结果；任一层缺失稳定拒绝。 |
| 后台与代理 | 防止持续环境丢失、旧 API 冲突或 SSRF。 | fake clock 验证 12 小时提醒；dispatcher 同时验证 task list/session list；可注入传输/DNS 覆盖公网 POST、HTTP 状态、私网、重定向和旧任务重试。 | runtime 与旧 task 语义分离；事件/提醒可恢复；无 SSRF、孤儿资源或协议回退。 |
| Miuix 真机旅程 | 验证卡顿、inset 和系统 UI。 | 小米机：干净安装、四个例子、权限、运行、复制、系统 surface、后台、删除，含大字体。 | 控件都有效；内容优先；无双 inset/明显卡顿。 |

GitHub Actions 的顺序固定为：协议一致性 → 安全静态检查 → Kotlin 编译 → 最小单元测试 →
APK/TBX 产物。0.3.4 上传 `toolbox-v0.3.4-debug.apk`、`stock-monitor-v1.1.1.tbx`、
`SHA256SUMS.txt` 和构建/测试回执；APK 内含四个范例。自动交付流程不启动模拟器；
回执必须明确设备测试和超级岛展示未执行。相机、SAF、
Sharesheet、持续 runtime、后台位置、精确闹钟和 HyperOS 展示由用户在候选 APK 上真机验证。

## 11. 完成条件

- 一步导入、工具列表、打开、权限切换、持续运行、旧后台任务和删除均为真实功能，无审核/审计/签名 UI
  或无效按钮。
- 四个范例可由干净安装的候选 APK 导入并完成各自声明功能。
- WebView、消息桥、包检查和网络代理符合第 2 节不变量。
- 每项保留测试有理由/方法/预期；静态候选门禁结果与用户真机结果分开记录。
- GitHub 只在静态门禁通过后发布 0.3.4 APK、独立行情哨兵、SHA256 和同提交回执，不伪造设备验证结论。
