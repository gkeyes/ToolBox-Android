# 运行时性能实现记录

本次实现以宿主 0.8.27 / 60 为源码基线，性能阶段宿主为 0.8.28 / 61；整合媒体与调试状态后的当前目标为 0.8.29 / 62，配套 NextFlux 为 1.0.35 / 56。此文件记录当前机制、参数与可验证边界，以及按检查范围保留的云端证据。源码变化可以证明删除了哪些工作，不能直接证明耗电、CPU、内存驻留或设备手感的变化。

## 1. 状态、事件与关闭协议

主要调用关系：

```text
RuntimeShellScreen → RuntimeViewModel → RuntimeSessionManager
  → HardenedRuntimeWebView → RuntimeBridgeSession
  → RuntimeRpcDispatcher → 原生能力 handler

Activity / 原生页面 → RuntimePresentationCoordinator
  → 文档启动 SDK → ToolBox.runtime.onStateChanged → 各工具前台展示
```

每个 WebView generation 只有一个 coordinator。同一 WebView 的后续主文档导航只重置一次 document epoch：清空旧事件并释放预算，保留单调序号与现有 generation / close token；取消旧非写入请求，已准入但尚未解析的请求解析一次后分类，写入继续完成，旧回复丢弃并归还资源。首次入口加载保留 ready 前事件。原生维护 `{generation, revision, foreground, closing}`；状态实际改变才递增 revision。`ready()` 自动初始化且缓存同一个 Promise。原生保存最新状态，SDK 订阅时立即回放最新状态，丢弃旧 generation 和重复、倒退 revision。`foreground` 由 Activity 已恢复且原生工具页面处于 attached 状态共同决定；同一 WebView 进入后台仍保留原来的业务运行方式。

### 准入和事件参数

| 项目 | 当前值 | 计费 / 调度过程 | 选取依据和范围 |
| --- | --- | --- | --- |
| 原生会话事件 | 1 MiB，最多 4096 个未 ACK 事件 | 每条 `UTF-16 字符数 × 2 + 128` 逻辑字节；排队、投递后至收件 ACK 都保留预算 | 两个条件共同限制；不预分配队列，不是 renderer 实际内存上限 |
| 原生进程事件 | 8 MiB，并检查当前可分配 heap | 所有桥共享一次预留；收件 ACK 或 generation 释放后归还 | 8 MiB 是逻辑计费上限，取消 heap/64 二次缩放；不限制运行工具个数 |
| JS 订阅前积压 | 每个文档独立 1 MiB / 4096 条 | 保存编码后的消息，按序号放入一个 Map；注册监听后回放 | 与原生预算独立，不增加跨层 credit 协议 |
| 投递、回放批次 | 最多 32 个，或检查工作达到 2 ms | 原生 Main Handler / JS `setTimeout(0)` 开下一任务；JS 预算按检查次数计算 | 2 ms 是软目标，单个用户回调和平台调度仍可能更长 |
| 收件 ACK | 32 条即发，否则 50 ms 单次定时 | 累计确认序号，独立控制预算；BUSY 后 50 ms 再试 | 收件而非业务处理确认；空闲时没有 ACK 周期定时器 |
| 控制请求 | 会话 4 KiB，进程共享 64 KiB | ready、getState、ackEvents、flushComplete、closeMedia 走小型预算；仍解析并验证身份 | 控制通道不会被大请求队列挤满；不允许借此发大数据 |

原生 FIFO 始终使用同一 event pump，ready 前后不会形成两条投递链。JS 未订阅事件保持序号；超过本地上限会发出 `toolbox:runtime.error`，该事件后续订阅明确抛出 `EVENT_BACKLOG_OVERFLOW`。原生 timer 积压触发原有有限恢复；位置积压停止受影响 watch 并呈现原因。ACK 失败不会当作已确认而释放原生预算。

### 保存截止点

1. `beginClose` 创建唯一 token，状态进入 closing。原生 timer 等待恢复开放事件；位置回调不再产生桥事件。原生主动取消当前 generation 的网络控制器，包括尚未建立公开 stream 的 complete 请求。
2. SDK 通知工具停止新生产工作，等待工具登记的 flush handler。非空 storage 写入仍走原来的普通请求预算；关闭期间遇到 BUSY 按 50 ms 软目标等待容量释放，保持同一请求 ID / 已编码内容，不能占用控制预算。
3. 普通请求在解析前登记。解析后非写入请求退出 drain 计数；写入直到原生 handler 和回复投递完成才退出。这样 token 到达前已准入但尚未解析的写入仍在截止点内。
4. SDK 最终保存成功发 `runtime.flushComplete`。coordinator 在同一同步区验证 token 并封住新普通请求；原生还要等截止点内写入全部结束。保存失败不会封口。
5. 正常返回将结束运行环境、或用户重载时，保留页面等待保存；2 秒后出现继续等待、取消退出、明确放弃保存后退出三个选择。取消使旧 token 失效、恢复原 generation；明确放弃才强制结束。若返回仍有后台会话，沿用 detach 路径继续运行。重载建立新 WebView、nonce 和 generation，再按现行业务恢复路径恢复后台任务。
6. 后台最后一个会话被外部停止：停止生产，在等待已取消的恢复任务结束之前启动唯一的 2 秒保存 / 关闭窗口，窗口结束后销毁仍无会话且不可见的原 WebView；没有分阶段累加的多个 2 秒。恢复任务 join 和会话记录持久化不计入保存窗口，整个 stopSession API 不承诺 2 秒内返回。未完成保存有原生日志。更新、撤权、数据清理、崩溃使用立即失效路径，旧 ACK 无法延长或重开旧 generation。

每个 flush handler 必须等真实写入完成；只触发一次保存然后立即返回不满足截止点。取消后旧 handler 可能结束其已准入写入，但不能确认新 token。此协议不是崩溃时保存承诺。

## 2. RPC、网络与 Worker

RPC 在预算准入后只创建一次 UTF-8 请求内容，复用同一个解析结果和字节数。版本 / generation 新鲜度在入口、授权后、最终副作用前验证；授权和系统权限在准入与副作用前验证。跨挂起等待后再次检查，保持拒绝撤权请求的边界。响应已编码文本和长度一起传递，避免重复序列化和 UTF-8 转换。

网络继续按当前可分配 heap 和既有保守成本计费：请求操作成本 64 KiB，body 工作副本为两倍请求体，响应沿用八倍成本。此轮不增加固定并发槽位、不降低业务轮询、不改 tombstone 身份边界。

等待结构为 `owner → FIFO 请求队列`。有释放或新请求时在同一锁中 drain：按 owner 次序扫描队首，选择当前容量容得下的请求；准入后该 owner 移到队尾。大请求不阻塞其他 owner 的可容纳队首，同一 owner 不插队。只有队列非空才有一个 50 ms heap 探测器，队列清空立即取消；旧探测器退出时用 Job 身份判断，避免取消新探测器。此策略提供轮转和容量选择，不宣称大请求一定在指定时间内得到容量。

active 请求控制器与公开 stream entries / tombstones 分开。关闭、取消工具或清理环境时，锁内复制 active 集合、锁外取消，complete 请求和取消 / 建流交错都受覆盖。tombstone 不做 TTL / LRU 缩短，避免重用 ID 时改变现有正确性。

Worker 获取执行身份 claim 后立即进入覆盖授权、通知准备、请求与提交的 `try/finally`；取消发生在授权阶段也释放 claim。同一进程可再次执行，不会因残留 claim 永久跳过。通知准备等挂起工作之后再验证版本 / 权限，避免撤权后的通知副作用。

## 3. 后台任务数据库与页面

数据库版本 1 → 2，增加 `(toolId, state)` 和 `(toolId, createdAt DESC, taskId ASC)` 复合索引，删除被替代的 toolId / state 单列索引，不清表、不改数据格式。活动任务由 SQL 按活动状态查出；历史单独按 `(createdAt DESC, taskId ASC)` 查询，每页 50 条，以末行键做 keyset。创建时间是稳定排序键，周期任务的状态和 updatedAt 更新不会令游标跨页跳动。首屏取 51 条只用于判断是否有下一页。

加载更多后，观察范围截止于已加载最旧键。Room invalidation 触发该范围内 SQL 重查并替换当前范围，因此已加载旧周期任务的状态、更新时间仍能更新。一次出现超过 50 条新历史时不会把旧页清空，也不会跳过范围中间的记录。活动任务与历史身份去重，终态进入历史后不保留第二套活跃状态。备份通过专门的完整查询取得所有后台记录，不借用页面观察器。

真实旧版 schema 在 GitHub 从固定基线生成，迁移测试使用它创建 v1 数据库，再通过生产迁移打开 v2；云端同时保留 v1 / v2 schema 供追溯。测试覆盖已有记录、索引、分页、已加载旧行更新与大批新记录，无 destructive migration。

## 4. 通知、图片、WebView 和浏览器

| 路径 | 实现过程 |
| --- | --- |
| 实时通知 | 请求带显式 updatedAt 且全部内容相同才跳过；未带时间继续更新 receivedAt。不能只按文本判断同一业务时刻 |
| 会话投影 | 一次取得 live snapshot 并建立 sessionId 索引，避免每个会话重新复制 / 扫描整张表；通知与页面共同发布该次投影 |
| FGS | 支持性查询挂起后取最新状态再渲染；empty / stop 分支再检查当前状态；成功 stopSelfResult 后清除旧 start 标记，后续新会话能够重新启动 |
| MIUI 支持性 | 有效期 30 秒；Activity 恢复和显式查询使缓存失效。系统通知权限仍实时检查 |
| 工具图片 | 解码并发 `min(2, CPU 数)` 且至少 1；边长 256 px；4 MiB 缓存。界面调用统一图片加载入口 |
| WebView 初始化 | 按宿主 APK debuggable 标志设置调试状态、默认 / stateless ServiceWorker 进程内一次；专用 Profile 使用其自身 ServiceWorkerController，按 Profile 生命周期初始化，删除后撤销初始化记录 |
| 生命周期 | 每个 WebView 使用 onPause / onResume；不调用全局 pauseTimers。前后台保持原有 WebView / FGS 模型 |
| 浏览器规则 | 候选规则索引和预处理，保留异常规则优先、IDN 归一化与过滤结果；没有增加全量扫描 fallback |

## 5. 六个工具的当前逻辑

| 工具版本 | 具体过程 | 保留的业务行为 |
| --- | --- | --- |
| GitHub Actions Watcher 1.1.8 / 19 | dirty revision + 单一 writer；写入签名只对已完成写入提交；jobsSynced 记录实际同步；前台展示计时 1 秒，后台取消；flush 等待运行中的恢复 / poll / 保存 | 原有工作流轮询、通知和停止规则 |
| Stock Monitor 1.1.4 / 6 | dirty card 集合合并到单个 100 ms 展示任务；后台不做卡片 DOM 更新；每轮行情仍保存并评估提醒；close 阻止新轮次并等待当前轮次 | 行情频率、阈值提醒与后台监测 |
| Notification Lab 1.0.3 / 4 | 日志 200 条环形保留，前台增量追加；后台累计，回前台补展示；close 等待已有动作、live 更新、恢复后保存 | 原生 1 / 2 / 5 秒实验计时 |
| Kegel Trainer 1.0.4 / 5 | 使用宿主 foreground；前台结束时显式暂停训练展示，取消 RAF、释放 WakeLock；用户操作恢复 | 训练状态、休息与结束语义 |
| SocialCoach 1.0.6 / 7 | raw revision 缓存角色回复解析；前台 50 ms preview，250 ms 内容变化 checkpoint；消息 ID 稳定；最终回复和回合元数据一次写入；close 取消网络并等待 Chat cleanup、真实 storage writer | 回复文本、业务持久化和异常回滚；附解析 / 序列化 / 原生写入计数，不以渲染批数替代 I/O 次数 |
| NextFlux 1.0.35 / 56 | 宿主状态统一暂停列表 / 阅读前台工作；nativeTimerEffective 与设置 intent 分开；自动同步共用到期 claim，同周期迟到触发不重复同步；状态文件打断循环导入 | 同步间隔、已读 / 摘要、语音与音频；原有 48 项 / 4 ms 调度及语音容量参数 |

Watcher / Stock / Lab / Kegel / SocialCoach 的 minHostVersion 为 0.8.28；整合后的 NextFlux 为 0.8.29，因为同时需要运行时协议和媒体接口。

## 6. GitHub 验证及交付边界

本地仅作源码、引用、差异和语法检查，不编译或执行测试。GitHub 按变更选择 JVM 类 / 方法、SDK 行为、工具测试文件、Android 场景及本次交付 APK / TBX 构建。自动路径映射缺失会失败；全量测试仅显式手动选择。后续修复只重跑失败项和该修复直接影响的检查。

复用旧 CI 要核对同仓库、完整运行、具体通过的检查、提交 ancestry 与所有相关构建 / 测试执行输入。workflow、行为脚本、SDK 或内置工具改变后不豁免。targeted 证据保留 targeted 范围，不能升级为 full。

证据层次分别为源码、GitHub tests、Android 模拟器、构建产物和签名、GitHub Release、真机运行。此次不操作手机，也不把模拟器和 CI 描述成实际设备 CPU / 电池验证。发布 Release 需单独执行；文档与源码提交后，临时 clone 在远端保存完成后清理。

### 当前验证结果

通过证据按检查及其相关源码复用。下表的部分检查来自整体失败的 run；只复用其中实际通过、随后未改变输入的步骤，不能将整个失败 run 记作通过。

| 范围 | 实际证据 |
| --- | --- |
| Stock / Lab / Kegel / SocialCoach | [TBX run 36742777153](https://github.com/gkeyes/ToolBox-Android/actions/runs/36742777153)：这四个工具各自选中的 Node / 浏览器检查、构建和 TBX 完整性通过。该 PR run 的 GITHUB_SHA 为合成提交 `76a84f205652c97b01409fbd18eccb6b0192f113`，实际 checkout 为 PR head `6387af1`；Watcher / NextFlux 的失败另行修复，没有重跑这四个工具 |
| Watcher | [run 36743195025](https://github.com/gkeyes/ToolBox-Android/actions/runs/36743195025) 的 reliability 文件通过；更新测试启动标记后，[run 36743606513](https://github.com/gkeyes/ToolBox-Android/actions/runs/36743606513) 的布局 14 / 14 场景与打包通过 |
| NextFlux | 上述初次 TBX run 的 reading 18 项通过；[run 36743799365](https://github.com/gkeyes/ToolBox-Android/actions/runs/36743799365) 的 article-navigation 19 项通过。补齐 notice 校验值后，[run 36745292362](https://github.com/gkeyes/ToolBox-Android/actions/runs/36745292362) 只执行构建 / 打包且通过，没有重复两组浏览器测试 |
| 宿主已有定向单元证据 | [run 36747288250](https://github.com/gkeyes/ToolBox-Android/actions/runs/36747288250)，提交 `de3067a0ff8222a125e5a70c20a9471dd58dfcf8`：app 68 项执行，67 通过、1 失败、0 跳过；失败为通知测试的异步等待，保留原断言并改为等待测试调度器完成。SDK、lint、debug / release 和测试 APK 编译通过；后续模块与 Android 场景尚未执行 |
| 宿主补充单元证据 | [run 36749624183](https://github.com/gkeyes/ToolBox-Android/actions/runs/36749624183)，提交 `c84b11a0da00ade0d150788d14e4905b76eb8677`：只执行修正后的通知方法及首次执行的 tool-package 2 类、tool-runtime 5 类，共 31 项通过、0 失败、0 跳过。结合前次未受修改影响的 67 项，累计 98 个不同定向单元用例通过；没有重跑那 67 项 |
| 宿主 Android 首次运行 | 同一 run 实际执行 app 26 项，25 通过、1 失败、0 跳过。最终非空 Room 写入、封口拒绝晚写、取消后恢复写入、两种关闭弹窗、Worker、备份及选中浏览器场景通过。迁移测试读取无索引表时错误要求必填 indices 字段，已改为读取 Room 的可选数组；保留全部记录 / 索引 / 分页断言。tool-runtime 场景因前一步失败尚未开始 |
| 宿主迁移及运行时首次执行 | [run 36754104266](https://github.com/gkeyes/ToolBox-Android/actions/runs/36754104266)，提交 `3bd78e9335a84e4915d6a76c53db66770b896c34`：使用旧版实际 `SUCCEEDED` / `STRICT` 枚举创建迁移夹具后，真实迁移方法 1 / 1 通过。tool-runtime 实际执行 8 项，6 通过、1 失败、1 跳过；通过的为两种 JavaScript dialog、非空最终写入 BUSY / 控制准入、3 个 Wasm 场景。生命周期夹具的随机 ID 末段可能以数字开头，第二次加载被真实 ID 校验拒绝，已修为字母前缀。Profile 在该 API 35 镜像的 WebView 124.0.6367.219 上缺少所需能力，跳过不计通过 |
| 宿主剩余定向场景 | [run 36758324823](https://github.com/gkeyes/ToolBox-Android/actions/runs/36758324823)，提交 `652dee8779f700525ce619091c2ee67ace95c5fa`：只选择生命周期、专用 Profile 删除后重建，以及同样修正 ID 的真实 Room 最终写入方法，共 3 项。API 36 镜像实际 WebView 为 `com.google.android.webview 133.0.6943.137`；3 / 3 通过、0 失败、0 跳过。专用 Profile 创建、删除 / 重建和自身 ServiceWorker 设置由真实平台执行。选择证据记录每个方法恰好执行 1 次。结合此前输入未变的通过项，累计 34 个不同定向 Android 用例通过 |

六个交付 TBX 的 manifest 版本 / minHost、外部 SHA256SUMS 和包内完整性已按云端产物实际字节核对。性能阶段 [签名 run 36760399026](https://github.com/gkeyes/ToolBox-Android/actions/runs/36760399026) 已通过：合并提交 `9c02516292623d55040dd46bef582fd16500fec2`、0.8.28 / 61、原证书 `849be1fd8066964d6194faa538fc0b17b4b17e5c116304e1d3be8ff725e4c71d`、非 debuggable、R8 / resource shrinking、内置示例字节比对与 API 35 初始主页冷启动通过。实际 APK SHA-256 为 `e8e595ac1a03140f2a9d35a1ff15e08710a05c4c05df4cf4c81421b44c162afc`；receipt 精确保留 run 36758324823 attempt 1 的 targeted 三方法范围。该产物是整合前性能版本，当前组合版以后续证据为准。

## 7. 与媒体任务 PR #54 的整合要求

用户询问的任务 `01a0f244-a463-79e2-a0fd-69f38f68090c` 对应 [媒体 PR #54](https://github.com/gkeyes/ToolBox-Android/pull/54)。它补全 NextFlux 图片 / 音视频媒体通道；性能 PR #56 收敛运行时调度、后台展示与持久化。以下记录整合前识别的冲突及其处理依据，实际整合见第 8 节。

只读合并检查以共同基线 `06cd8ec7899f139f974c4c72b565af59a3f91118`、本 PR 提交 `652dee8779f700525ce619091c2ee67ace95c5fa` 和媒体提交 `d96b9fbec1d9b5908305eb3defeb481649c6da96` 为准：16 个共同修改文件，7 个文本冲突。冲突为 NextFlux 的 manifest、package / lock、notice，以及 `HardenedRuntimeWebView`、`RuntimeRpc`、`RuntimeWebMessageBridge`。检查没有修改工作树、合并 PR 或操作另一任务。

运行时基础先合入；按用户后续授权，媒体与调试状态接入当前主调用链。具体整合点：

1. **网络所有权与清理。** 保留本次 complete 请求的 active controller 登记 / 释放，不能退回仅登记公开 stream 的实现。媒体的 `openStream` / 资源所有权加入同一取消路径；保留媒体任务的 `clear` / `close` 和撤权取消。锁内收集资源，锁外取消；关闭、撤权、刷新都必须释放资源。
2. **RPC 关闭协议。** `network.openMedia` 属于普通准入，挂起工作后仍检验版本 / 权限。`network.closeMedia` 为资源清理加入 closing 允许的方法及控制准入，撤权后仍可清理已有资源；身份、声明、当前 generation 和控制体积检查继续生效。媒体方法接入本次授权前后检查和保存截止点，不能创建第二套关闭逻辑。
3. **页面切换与桥预算。** 将媒体任务的 navigation epoch 与本次 generation / revision、事件 FIFO、累计 ACK 和 flush token 对齐。旧页面回复 / 事件不能送到新页面；旧队列和在途预算必须释放。当前 event buffer 的 close 是最终关闭，页面 reset 需可复用且保持序号单调；`loadEntry` 与 `onPageStarted` 的重置去重，避免取消新页面刚发出的 ready。桥接回复和控制 token 仍由当前页面拥有。
4. **WebView 与版本。** 保留本次专用 Profile 自身的 ServiceWorker 初始化、进程调试初始化和精确本地来源判断，再加入媒体任务的非主文档路由。每个 WebView 只建立一个媒体 handler，创建失败也释放资源。NextFlux 采用合并后的一个版本，并同步 manifest、package、lock 和 notice 校验值；不得覆盖任一方功能或保留平行实现。

整合后仅验证直接受影响的媒体 GET / HEAD / Range / 416、播放与拖动、刷新 / 退出 / 撤权资源清理、旧页面回复与 ACK 隔离、严格同源检查，以及运行时准入 / 生命周期 / 最终写入相关回归。当前各 PR 的通过证据不能替代合并后共享调用链的验证。

## 8. 已完成分支的统一整合

用户授权在性能交付完成后合并所有已完成分支，再仅保留默认分支。性能 PR #56 已合并并完成签名证据；媒体 PR #54 和调试状态 PR #55 在临时整合分支通过 merge commit 保留完整提交祖先关系。代码只保留一个版本。

调试状态采用 PR #55 的 APK 编译标志策略，并保留本次首次 WebView 创建前、每进程一次的初始化；专用 Profile 的 ServiceWorker 加固不受覆盖。其 user-image Debug / 非 debuggable Verification 专项检查是当前需要的测试门禁，不创建第二条正式发布链。

媒体清理 `network.closeMedia` 走既有控制预算，closing / 保存封口 / 撤权后仍可清理已有资源，同时核验来源、身份、声明和当前版本。`openMedia` 保持普通准入，在挂起授权后复核当前性和协程取消；重复媒体 ID 的 BUSY 不关闭已有 owner。挂起旧 open 的失败只清理自己拥有的 entry，不删除新 entry。

移除未知媒体 ID 的 `cancelledBeforeOpen` 集合：未知 close 是无状态的幂等清理。SDK 已在 abort 后的迟到打开结果上再次 close；原生 open 只建立本地 URL、尚未发出 HTTP，取消的调用方不会把 URL 交给播放器。保留该迟到清理即可避免取消 ID 无界滞留。有效会话、头部请求、阻塞读取和媒体 body 仍由同一所有权路径取消。

删除只验证全局取消所有普通 Job 的 `RuntimeMediaNavigationJobsTest`：该行为会丢失已准入写入，已经由选择性取消、真实 WebView 未解析队列与真实 Room 导航写入回归替代。原生保存封口不因导航重置；停止后台会话属于已准入持久化工作，关闭期允许其结束，封口仍拒绝新普通工作。

当前组合版本为宿主 0.8.29 / 62、NextFlux 1.0.35 / 56（最低宿主 0.8.29），package / lock / notice 同步。前台暂停只作用于正文分批渲染和摘要，不卸载用户已经启动的音视频。隐藏订阅入口、图片缓存升级、HLS 与直接媒体功能保留。

整合后的首次云端检查发现一份完全相同的 `resetForNavigation(WebView)` 声明重复保留，已删除第二份；没有改变导航实现。媒体 SDK 测试的两处断言按 `openMedia` / `closeMedia` 方法计数，避免把性能侧自动 ready 计为媒体请求。`pagehide` 测试验证两个清理请求、两个 abort listener 解除、pending open 拒绝及销毁后迟到回复不能复活调用；原生导航清空媒体 registry、取消挂起 open 与阻塞 body 由原生回归验证。存活文档内的 abort 仍要求迟到成功结果再次 close。

### 合并后的专项证据

| 范围 | 实际结果 |
| --- | --- |
| NextFlux | [run 36766560998](https://github.com/gkeyes/ToolBox-Android/actions/runs/36766560998)，提交 `064a6da358ff515cae4d545e9b1978ca3b089775`：9 个定向 Node 文件共 85 项通过；4 个浏览器文件共 81 项通过（导航 21、控件 15、媒体 27、阅读 18），0 失败 / 跳过 / flaky；Vite 构建和 TBX 打包通过。后续提交未改变 NextFlux 源码或其构建 / 测试输入，复用本次证据 |
| WebView 调试编译策略 | [run 36768325226](https://github.com/gkeyes/ToolBox-Android/actions/runs/36768325226)，提交 `f0bd291f0b2373fd2cd1551476c2fde6389ecc8e`：API 35 Google Play `user` 镜像、实际 WebView `124.0.6367.219`；Debug 与非 debuggable Verification 各 12 个不同场景全部通过，0 失败 / 跳过。下载的两份 XML 各恰好 12 项；不是用开发系统的强制调试行为代替正式系统策略 |

实际 NextFlux TBX SHA-256 为 `bc91f9b17390382ed699819b4140b56794a0886f844a8f952bd9c1721221b675`。包内 252 个文件逐项符合 integrity，manifest 为 1.0.35 / 56、minHostVersion 0.8.29；notice 的 lockfile 哈希 `349892af091cfda30ad1d677581456ebdf3873e6bb3af11f33a9619ae1943b28` 与当前 lock 文件一致，包含 hls.js 1.7.3。

合并后的宿主共享链路检查正在 GitHub 执行，不复跑全局测试；结果及最终签名产物完成后补入。
