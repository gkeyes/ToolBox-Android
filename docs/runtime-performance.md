# 运行时性能实现记录

本次实现以宿主 0.8.27 / 60 为源码基线，宿主目标版本为 0.8.28 / 61。此文件记录当前机制、参数与可验证边界；云端验证结果在合并前补入。源码变化可以证明删除了哪些工作，不能直接证明耗电、CPU、内存驻留或设备手感的变化。

## 1. 状态、事件与关闭协议

主要调用关系：

```text
RuntimeShellScreen → RuntimeViewModel → RuntimeSessionManager
  → HardenedRuntimeWebView → RuntimeBridgeSession
  → RuntimeRpcDispatcher → 原生能力 handler

Activity / 原生页面 → RuntimePresentationCoordinator
  → 文档启动 SDK → ToolBox.runtime.onStateChanged → 各工具前台展示
```

每个 WebView generation 只有一个 coordinator。原生维护 `{generation, revision, foreground, closing}`；状态实际改变才递增 revision。`ready()` 自动初始化且缓存同一个 Promise。原生保存最新状态，SDK 订阅时立即回放最新状态，丢弃旧 generation 和重复、倒退 revision。`foreground` 由 Activity 已恢复且原生工具页面处于 attached 状态共同决定；同一 WebView 进入后台仍保留原来的业务运行方式。

### 准入和事件参数

| 项目 | 当前值 | 计费 / 调度过程 | 选取依据和范围 |
| --- | --- | --- | --- |
| 原生会话事件 | 1 MiB，最多 4096 个未 ACK 事件 | 每条 `UTF-16 字符数 × 2 + 128` 逻辑字节；排队、投递后至收件 ACK 都保留预算 | 两个条件共同限制；不预分配队列，不是 renderer 实际内存上限 |
| 原生进程事件 | 8 MiB，并检查当前可分配 heap | 所有桥共享一次预留；收件 ACK 或 generation 释放后归还 | 8 MiB 是逻辑计费上限，取消 heap/64 二次缩放；不限制运行工具个数 |
| JS 订阅前积压 | 每个文档独立 1 MiB / 4096 条 | 保存编码后的消息，按序号放入一个 Map；注册监听后回放 | 与原生预算独立，不增加跨层 credit 协议 |
| 投递、回放批次 | 最多 32 个，或检查工作达到 2 ms | 原生 Main Handler / JS `setTimeout(0)` 开下一任务；JS 预算按检查次数计算 | 2 ms 是软目标，单个用户回调和平台调度仍可能更长 |
| 收件 ACK | 32 条即发，否则 50 ms 单次定时 | 累计确认序号，独立控制预算；BUSY 后 50 ms 再试 | 收件而非业务处理确认；空闲时没有 ACK 周期定时器 |
| 控制请求 | 会话 4 KiB，进程共享 64 KiB | ready、getState、ackEvents、flushComplete 走小型预算；仍解析并验证身份 | 控制通道不会被大请求队列挤满；不允许借此发大数据 |

原生 FIFO 始终使用同一 event pump，ready 前后不会形成两条投递链。JS 未订阅事件保持序号；超过本地上限会发出 `toolbox:runtime.error`，该事件后续订阅明确抛出 `EVENT_BACKLOG_OVERFLOW`。原生 timer 积压触发原有有限恢复；位置积压停止受影响 watch 并呈现原因。ACK 失败不会当作已确认而释放原生预算。

### 保存截止点

1. `beginClose` 创建唯一 token，状态进入 closing。原生 timer 等待恢复开放事件；位置回调不再产生桥事件。原生主动取消当前 generation 的网络控制器，包括尚未建立公开 stream 的 complete 请求。
2. SDK 通知工具停止新生产工作，等待工具登记的 flush handler。非空 storage 写入仍走原来的普通请求预算；关闭期间遇到 BUSY 按 50 ms 软目标等待容量释放，保持同一请求 ID / 已编码内容，不能占用控制预算。
3. 普通请求在解析前登记。解析后非写入请求退出 drain 计数；写入直到原生 handler 和回复投递完成才退出。这样 token 到达前已准入但尚未解析的写入仍在截止点内。
4. SDK 最终保存成功发 `runtime.flushComplete`。coordinator 在同一同步区验证 token 并封住新普通请求；原生还要等截止点内写入全部结束。保存失败不会封口。
5. 正常返回将结束运行环境、或用户重载时，保留页面等待保存；2 秒后出现继续等待、取消退出、明确放弃保存后退出三个选择。取消使旧 token 失效、恢复原 generation；明确放弃才强制结束。若返回仍有后台会话，沿用 detach 路径继续运行。重载建立新 WebView、nonce 和 generation，再按现行业务恢复路径恢复后台任务。
6. 后台最后一个会话被外部停止：停止生产，最多等待总计 2 秒，然后结束；没有分阶段累加的多个 2 秒。未完成保存有原生日志。更新、撤权、数据清理、崩溃使用立即失效路径，旧 ACK 无法延长或重开旧 generation。

每个 flush handler 必须等真实写入完成；只触发一次保存然后立即返回不满足截止点。取消后旧 handler 可能结束其已准入写入，但不能确认新 token。此协议不是崩溃时保存承诺。

## 2. RPC、网络与 Worker

RPC 在预算准入后只创建一次 UTF-8 请求内容，复用同一个解析结果和字节数。版本 / generation 新鲜度在入口、授权后、最终副作用前验证；授权和系统权限在准入与副作用前验证。跨挂起等待后再次检查，保持拒绝撤权请求的边界。响应已编码文本和长度一起传递，避免重复序列化和 UTF-8 转换。

网络继续按当前可分配 heap 和既有保守成本计费：请求操作成本 64 KiB，body 工作副本为两倍请求体，响应沿用八倍成本。此轮不增加固定并发槽位、不降低业务轮询、不改 tombstone 身份边界。

等待结构为 `owner → FIFO 请求队列`。有释放或新请求时在同一锁中 drain：按 owner 次序扫描队首，选择当前容量容得下的请求；准入后该 owner 移到队尾。大请求不阻塞其他 owner 的可容纳队首，同一 owner 不插队。只有队列非空才有一个 50 ms heap 探测器，队列清空立即取消；旧探测器退出时用 Job 身份判断，避免取消新探测器。此策略提供轮转和容量选择，不宣称大请求一定在指定时间内得到容量。

active 请求控制器与公开 stream entries / tombstones 分开。关闭、取消工具或清理环境时，锁内复制 active 集合、锁外取消，complete 请求和取消 / 建流交错都受覆盖。tombstone 不做 TTL / LRU 缩短，避免重用 ID 时改变现有正确性。

Worker 获取执行身份 claim 后立即进入覆盖授权、通知准备、请求与提交的 `try/finally`；取消发生在授权阶段也释放 claim。同一进程可再次执行，不会因残留 claim 永久跳过。通知准备等挂起工作之后再验证版本 / 权限，避免撤权后的通知副作用。

## 3. 后台任务数据库与页面

数据库版本 1 → 2，只增加后台状态查询需要的索引，不清表、不改数据格式。活动任务由 SQL 按活动状态查出；历史单独按 `(createdAt DESC, taskId ASC)` 查询，每页 50 条，以末行键做 keyset。创建时间是稳定排序键，周期任务的状态和 updatedAt 更新不会令游标跨页跳动。首屏取 51 条只用于判断是否有下一页。

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
| WebView 初始化 | 调试开关和默认 / stateless ServiceWorker 进程内一次；专用 Profile 使用其自身 ServiceWorkerController，按 Profile 生命周期初始化，删除后撤销初始化记录 |
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
| NextFlux 1.0.33 / 54 | 宿主状态统一暂停列表 / 阅读前台工作；nativeTimerEffective 与设置 intent 分开；自动同步共用到期 claim，同周期迟到触发不重复同步；状态文件打断循环导入 | 同步间隔、已读 / 摘要、语音与音频；原有 48 项 / 4 ms 调度及语音容量参数 |

六个变更工具的 minHostVersion 为 0.8.28，避免在缺少新关闭和状态协议的旧宿主上运行。

## 6. GitHub 验证及交付边界

本地仅作源码、引用、差异和语法检查，不编译或执行测试。GitHub 按变更选择 JVM 类 / 方法、SDK 行为、工具测试文件、Android 场景及本次交付 APK / TBX 构建。自动路径映射缺失会失败；全量测试仅显式手动选择。后续修复只重跑失败项和该修复直接影响的检查。

复用旧 CI 要核对同仓库、完整运行、具体通过的检查、提交 ancestry 与所有相关构建 / 测试执行输入。workflow、行为脚本、SDK 或内置工具改变后不豁免。targeted 证据保留 targeted 范围，不能升级为 full。

证据层次分别为源码、GitHub tests、Android 模拟器、构建产物和签名、GitHub Release、真机运行。此次不操作手机，也不把模拟器和 CI 描述成实际设备 CPU / 电池验证。发布 Release 需单独执行；文档与源码提交后，临时 clone 在远端保存完成后清理。

### 当前验证结果

待 GitHub 定向运行完成后，补充精确提交、run、范围、通过 / 失败 / 跳过情况及产物。
