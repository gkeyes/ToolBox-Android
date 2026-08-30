# ADR 0004：Origin-only 无状态运行时兼容回退

- 状态：Accepted
- 日期：2026-08-28
- 取代范围：仅取代 ADR 0003 第 36–37 行“缺少整 Profile 清理能力时拒绝运行”的结论；ADR 0003 的专用 Profile 清理协议继续有效。

## 背景

技术方案 7.2 明确要求：WebKit 支持时使用每工具独立 Profile；Profile API 不支持时，
依靠每工具唯一 exact HTTPS Origin，并禁止 Cookie、ServiceWorker 与远程页面。实际 API 35
模拟器的 Android System WebView 124 支持能力并不等同于 AndroidX WebKit 1.17 暴露的全部
能力：缺少 `DELETE_BROWSING_DATA` 时，原实现会在宿主冷启动阶段阻断所有页面，连从未产生
浏览数据的工具也不能运行或卸载。

不能以默认 Profile 中开启持久存储作为兼容方案。它会让卸载清理依赖不完整的旧 API，并使
一次 provider 降级可能重新暴露先前专用 Profile 的状态。

## 决策

运行 permit 携带不可变隔离模式：

- 只有 `MULTI_PROFILE` 与 `DELETE_BROWSING_DATA` 同时可用时选择
  `DEDICATED_PROFILE`，继续采用 ADR 0003 的整个 Profile 清理与 marker 协议。
- 其他组合选择 `ORIGIN_ONLY_STATELESS`。该模式不调用 `setProfile`；关闭 DOM storage、
  Web SQL/database、全部 Cookie 与第三方 Cookie，使用 `LOAD_NO_CACHE`，并保持所有本地响应
  的 `Cache-Control: no-store`。文档开始脚本在任何工具脚本前禁用 Cache API、IndexedDB、
  local/session storage、`navigator.serviceWorker`、Storage/OPFS、Storage Buckets、WebSQL 与
  legacy FileSystem API；同时锁死实例和原型链 descriptor，不能通过恢复原 getter 绕过。
  CSP 的 `worker-src 'self'` 只允许安装包内、经 exact-origin AssetLoader 读取的静态 Dedicated
  Worker，以便把高负载计算移出页面线程；远程、`blob:` 和 `data:` Worker 仍被拒绝。全局
  ServiceWorker client 对所有注册脚本返回阻断响应，文档开始脚本继续移除
  `navigator.serviceWorker`。ServiceWorker 设置同时禁止网络、文件和 content 访问并关闭缓存读取。
- 无状态模式要求 `DOCUMENT_START_SCRIPT`。若 provider 声称支持 ServiceWorker basic usage，
  还必须支持 `SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST`；缺任一前置能力时在写 mode record 前
  拒绝 permit，并在两种隔离模式创建 WebView 时按实际 provider feature 再检查一次。
- 默认 Profile 禁用 Cookie 后，创建器仍用 `CookieManager.getCookie(exact origin)` 检查该
  Origin 可见的所有 Cookie（包括适用的父域 Cookie）。任何非空结果都在 attach/load 前返回
  typed creation failure，不能让受污染的默认 Profile 进入工具代码。
- 两种模式都保留唯一 exact HTTPS Origin、`WebViewAssetLoader`、CSP、任意外部请求/导航阻断、
  文件选择拒绝和 renderer-gone 收敛。回退不引入 `file://`、localhost 或原生消息桥。

在第一次创建某工具 WebView 之前，宿主在私有目录原子写入并 fsync 一份严格校验的 per-tool
隔离模式记录。模式选择和记录迁移发生在该工具的运行 reservation 内，因此不能与活动 WebView
或卸载清理竞态：

1. `ORIGIN_ONLY_STATELESS` 升级到 `DEDICATED_PROFILE` 可直接原子改写记录，因为回退模式未允许
   浏览状态持久化。
2. `DEDICATED_PROFILE` 降级到 `ORIGIN_ONLY_STATELESS` 前必须仍有 `MULTI_PROFILE`，并证明旧
   Profile 尚未加载且已物理删除（或确认不存在）。若 Profile 已加载、无法删除或其存在性无法
   可靠判断，拒绝 permit；不能通过忽略旧 Profile 来报告安全迁移。
3. 记录损坏、身份/文件名不匹配或写入/fsync 失败均阻断运行。

无状态文档开始脚本只有在全部 descriptor 锁定成功后才写入不可变 sentinel。页面完成回调在
报告成功前异步验证 sentinel；失败时停止加载、清空文档并返回用户可见错误，不能仅依赖脚本中
被捕获的 `defineProperty` 异常。

卸载仍取得同一 per-tool 生命周期租约。无状态模式没有可清理的浏览持久化，因此只原子移除模式
记录后进入包动作；若动作被取消或返回失败，工具仍可在下次运行时重新选择并持久化安全模式，旧
页面也没有可恢复状态。专用 Profile 模式仍先完成 ADR 0003 清理证明再运行包动作。

冷恢复先读取 marker 与隔离模式记录；两者都不存在时直接成功，不查询可选 WebKit feature。
孤儿无状态记录只需删除记录。孤儿专用记录或 marker 的物理清理只要求 `MULTI_PROFILE`，不要求
`DELETE_BROWSING_DATA`，因为冷恢复只接受可物理删除的未加载 Profile；已加载或删除失败仍阻断。
若 provider 连 `MULTI_PROFILE` 都不支持，则保留孤儿专用证据并返回 `RecoveryDeferred`：宿主其他
界面可以启动，但该证据不能被伪报为已清理，相关工具的运行或卸载仍按记录 fail closed。

## 安全后果

- WebView 124 等缺少整 Profile 清理能力的 provider 可以运行离线工具，但不能写入 Cookie、
  DOM storage、IndexedDB、Cache API 或 ServiceWorker 状态。
- Provider 能力变化不会静默复用不同隔离模式的数据。专用模式向回退模式迁移必须先删除旧
  Profile；反向迁移只会从无状态环境进入新的专用 Profile。
- 宿主空状态冷启动不再因为可选 WebKit 能力缺失而失败；暂时无法处理的孤儿专用证据会保留为
  `RecoveryDeferred`，不阻断无关宿主界面，也不会伪报清理成功。
- 文档开始脚本是安全前置条件；不支持该 feature 时 WebView 创建返回类型化失败，不会先运行工具代码。

## 拒绝的备选方案

- 在默认 Profile 中保留 DOM storage，再按 Origin 猜测性清理：无法覆盖所有浏览器状态，拒绝。
- 缺少 `DELETE_BROWSING_DATA` 时继续使用专用 Profile 并在卸载时忽略已加载实例：会留下旧数据，拒绝。
- 全局删除宿主 WebView 数据：会影响其他工具且不能证明 per-tool 事务边界，拒绝。
- 仅依赖 CSP 禁止 ServiceWorker 或 Cache API：不能证明页面脚本看不到持久 API，拒绝。

## 验证

保留一个真实 WebView instrumentation 测试，输出 provider capability flags，并在实际选择的模式下
加载 exact-Origin 页面。专用模式继续验证整个 Profile 清理；无状态模式验证 Cookie、localStorage、
sessionStorage、IndexedDB、Cache API、ServiceWorker、Storage/OPFS、Storage Buckets、WebSQL 与
legacy FileSystem 均不能持久化，并验证直接调用和原型 descriptor 绕过在销毁/重建后仍失败。
同一测试还用注入 seam 覆盖 dedicated→stateless 的 Deleted/Absent 成功、Loaded/不可证明拒绝、
partial ServiceWorker/missing document-start 在写记录前拒绝，以及默认 Profile 预置 exact-host
Cookie 时的 typed creation failure；同时保留危险导航、离线请求、租约交接、恢复 marker、取消
切点与 renderer-gone 边界。
