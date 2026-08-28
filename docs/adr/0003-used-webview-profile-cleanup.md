# ADR 0003：已使用 WebView Profile 的两阶段清理

- 状态：Accepted
- 日期：2026-08-28

## 背景

技术方案要求在 WebKit 支持时为每个工具使用独立 Profile，并在卸载时删除该
Profile。真实 Android WebView 测试证明，仅检查 `MULTI_PROFILE` feature 不足以
保证同一进程内可完成物理删除：工具 Profile 一旦在当前 WebView 进程实例化，
`ProfileStore.deleteProfile()` 会以 `IllegalStateException` 拒绝删除，即使关联
WebView 已从父容器移除并执行 `destroy()`。

AndroidX 的公开契约要求 Profile 仍有关联 WebView或已加载进内存时拒绝删除；
Chromium 的实现也明确把已实例化 Profile 保持到进程结束。因此，将该异常简单解释
成“界面仍打开”会导致用户在关闭运行页后仍无法卸载；忽略异常则会留下旧浏览数据。

## 决策

继续保留每工具唯一 exact HTTPS Origin 和可用时的独立 Profile，但把清理分成两个
可验证阶段：

1. 运行时维护当前工具 WebView 的活动计数。用户确认卸载时，若仍有活动 WebView，
   返回类型化 `InUse`，包和目录记录保持不变。
2. 没有活动 WebView 时先尝试 `ProfileStore.deleteProfile()`。若 Profile 尚未在当前
   进程实例化，直接完成物理删除。
3. 若 Profile 已实例化而无法物理删除，使用该 Profile 的 `WebStorage` 和
   `WebStorageCompat.deleteBrowsingData()` 清空整个专用 Profile；清理协程不可用超时或
   取消来推断成功，必须等待真实完成回调。回调后先原子写入并 fsync
   `CONTENT_CLEARED_PENDING_PROFILE_DELETE` 标记，再允许包生命周期在同一独占租约内执行。
4. 下次冷启动在任何工具 WebView 创建前，只读取严格校验且 catalog 已无对应 tool ID 的
   清理标记，再物理删除其派生 Profile；不扫描或猜测其他 `tbx_` Profile。catalog 仍有
   工具时保留标记，并在新的异步运行 permit 取得后、`setProfile` 前从 IO 线程原子消费。
5. 运行 permit 在任何 Profile 实例化前预留 tool ID，并在 WebView 注册、创建失败或
   页面释放时精确转移/关闭；清理租约覆盖 Profile 清理、标记持久化和包生命周期结果。
6. 若设备缺少完成安全清理所需的 WebKit 能力，运行入口必须给出可见且可操作的失败，
   不能先写入无法可靠清理的浏览数据。

该策略不改变 Origin 格式、CSP、导航限制、网络默认关闭或消息桥门禁，也不通过
`file://`、localhost 或全局清除其他工具数据来规避 Profile 限制。

## 安全后果

- 工具仍在运行时，代码、目录、授权和浏览数据均不会进入半卸载状态。
- 已使用 Profile 的工具在当前进程内先完成内容清空，因此卸载重装不能读取旧站点
  数据；崩溃安全标记把 Profile 目录壳的冷启动物理删除绑定到已证明的清理操作。
- 冷启动只处理身份、文件名和状态均严格匹配，且不对应任何已安装工具的清理标记；
  不触碰默认 Profile，不执行宽泛 Origin 清理，也不扫描无标记的 Profile。
- Profile 内容清空是异步操作，协程取消或回调失败必须停止包删除，不能报告成功。

## 拒绝的备选方案

- 把 `IllegalStateException` 当作可忽略警告后继续卸载：会留下旧 Profile 数据，拒绝。
- 在 UI 线程轮询、等待或触发 GC 后重试：Chromium 的已实例化 Profile 生命周期由
  进程边界决定，延时与 GC 不是有效契约，拒绝。
- 清空默认 WebStorage 或整个应用 WebView 数据：会破坏其他工具数据，拒绝。
- 为赶进度禁用独立 Origin、放宽 CSP 或使用文件来源：直接违反安全不变量，拒绝。

## 验证

保持一个真实 WebView 仪器测试覆盖：活动页面阻止卸载清理；关闭页面后专用 Profile
内容清理完成；重建同一工具运行环境时旧 Cookie 与 JavaScript 存储不可见；危险导航、
远程请求和 renderer 退出边界仍保持关闭。宿主目录测试同时证明清理失败时包生命周期
未被调用。测试理由、方法和预期结果记录在 `TESTING.md`。
