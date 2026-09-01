# ToolBox 测试准入与最小矩阵

测试的目的不是堆数量，而是保护真实的产品与安全边界。每个保留或新增自动化测试都必须在
本文件登记三项内容：**测试理由、测试方法、预期结果**。实现尚未到达某阶段时，不创建占位
测试；功能删除后，同步删除其测试与本文件条目。

## 准入规则

仅保留保护以下任一边界的测试：

1. `AGENTS.md` 安全不变量或安全敏感分支；
2. 原子事务、持久化、并发、配额、任务状态或清理边界；
3. 已报告的无效控件、卡顿、inset、字体缩放或辅助功能缺陷；
4. 与候选 APK/提交绑定的构建、产物和真机证据链。

不测试 data class getter、框架默认行为、静态文案、删除的审核/审计/发布者/迁移功能，或已经
由更低层真实测试覆盖的同一行为。相同边界的输入优先用参数化矩阵。截图只保护指定渲染状态，
不替代交互、系统 UI、WebView 或安全验证。

## 阶段性最小测试矩阵

| 阶段 | 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|---|
| 清洁基线 | `FreshPersistenceContractTest` | 防止开发期重构残留旧 DB、设置或兼容代码。 | 以 production Room/DataStore 创建并重开空基线，写入工具、grant、KV、任务、结果；检查 schema 与 preferences keys。 | 所有真实状态跨重开保持；没有 audit/publisher/旧设置/迁移类或旧表。 |
| 协议 | `:tool-api:verifyToolBoxApiContract` | 防止 Kotlin、JS shim、d.ts 和 manifest capability 枚举漂移。 | 从 canonical API v1 输入运行生成/比对任务。 | 生成物完全一致；任一手工漂移令检查失败。 |
| 导入 | `DirectPackageLifecycleTest`；`ToolBoxOpenDocumentTest` | 核心导入必须只有成功或失败，且失败不能残留；运行中的旧页面不能跨越包替换或删除继续持有文件；文件选择输入必须是一次性、无歧义的 `.tbx` 来源。 | 用有效包完成安装、更新与卸载，在 catalog 切换及版本目录删除前观察运行时释放钩子；以 Zip Slip、嵌套压缩包、完整性损坏、错误包签名和较高 `minHostVersion` 构成拒绝矩阵，并验证取消、非法/双向文件名和一次性输入。 | 有效包原子可见；更新和卸载先释放运行环境，再切换 catalog 或移除版本文件；上述无效包没有 DB、目录或临时文件残留；非法来源被拒绝且输入流不能重复打开。 |
| 更新/删除 | `CatalogAndStorageRepositoryTest` | 更新和卸载必须清理完整状态，不留下后台或敏感数据。 | 安装后写普通 KV、grant、后台 task/result，再提交更高版本并删除目录。 | 更新仅保留普通 KV 且以新 manifest grants 替换旧 grant；任务/结果消失；删除清理全部数据库工具状态。 |
| 权限与运行时 API | `PermissionCenterViewModelTest`；`RuntimeRpcDispatcherTest` | 防止“有开关没能力”、0.3 新接口覆盖旧 API，或跳过声明、授权、系统权限、手势和配额层。 | 对已支持 capability 用 production dispatcher 分别关闭 declaration、grant、system、gesture、quota 条件；验证公网 POST/Header/JSON 请求与协议级 Header 拒绝、`background.list` 的旧任务语义、`background.listSessions` 的持续环境语义、`notifications.live.start/update/end` 字段边界和错误 session、仅含调度元数据的 alarm、位置 watch 参数及文件一次性令牌。 | 全部条件满足时得到真实 handler 结果；实时通知合法调用返回完整增强状态，错误 session/颜色/字段在写入前稳定失败；旧任务与持续环境列表互不混淆；危险 Header 和未公开参数在调用 handler 前稳定失败；任一授权层缺失稳定拒绝；alarm 无业务 payload；文件令牌只能消费一次。 |
| Bridge | `ToolRuntimeSecurityBoundaryTest`；`HardenedRuntimeWebViewInstrumentationTest` | 保护导入内容的来源与会话边界，同时允许高负载计算离开页面主线程而不放开远程代码或 ServiceWorker。 | JVM 检查 CSP 仅含 `worker-src 'self'` 且不含 wildcard/blob；API 35 WebView 测试 exact origin、main frame、nonce、iframe、导航后的旧 nonce、取消和 ServiceWorker 阻断，并验证被拒 iframe 不能抢占主 frame 完成 `ready` 后的原生事件通道。 | 仅包内 exact-origin 静态 Worker 可加载；远程/blob/data Worker 与 ServiceWorker 被阻断；仅当前主 frame/来源/会话可调用 ToolBox 并接收原生事件，其他请求被拒绝且无副作用。 |
| M3 API | `RuntimeRpcDispatcherTest.m3FileTokensAndLocationFailClosedThroughTypedHandlers` | 文件和定位会触及用户数据及系统权限，必须保证会话令牌、消息配额、能力授权和稳定错误不会被绕过。 | 通过 production dispatcher 验证文件令牌继承创建它的 `files.open`、`files.save` 或 `camera` 能力、只能消费一次；以含引号的非法 ID 和 128 字节合法 ID 验证令牌读取前的 ID 限制及按实际 ID/Base64/JSON 计算的响应预算；直接验证 bridge 对 UTF-8 编码后超额的任意响应替换为 quota failure；验证位置只在通用层要求粗略权限，并将原生位置不可用映射为 typed `NOT_FOUND`。 | 非 shim ID 在消费令牌前失败；边界合法 ID 的完整响应不超过会话配额；首次读取只返回会话配额内的内容，重复读取失败，其他超额响应由 bridge 返回 `QUOTA_EXCEEDED`；粗略授权可进入 handler，精确请求由 handler 再检查 fine；原生空结果不会被取消通道吞掉。 |
| M3 生命周期 | `M3BrokerLifecycleInstrumentedTest` | Dispatcher 假对象不能证明宿主实际使用的临时句柄表和相机缓存会在运行会话结束时清理，也不能证明 Android `Location` 空回调采用 typed failure。 | 在 Android instrumentation 中使用生产 `RuntimeFileSessionResources` 注册真实缓存文件、content URI 与 camera capability，调用会话 `close`；同时调用生产位置回调适配器的 null 与非 null 分支。 | 关闭后令牌、句柄和临时文件计数均为零且真实缓存文件消失；null 结果为非取消型 `RuntimeHandlerException(NOT_FOUND)`，有效 `Location` 成功返回。 |
| 后台 | `BackgroundTaskRepositoryTest`；`BackgroundTaskPolicyTest`；`RuntimeReminderPolicyTest`；`LiveNotificationCoordinatorTest`；`RuntimeNotificationRegressionTest`；`BackgroundTasksPresentationTest`；`manual-xiaomi-toolbox-v1` 的 0.3.2 后台步骤 | 同时保护冻结的 WorkManager 任务语义、持续 runtime 的 12 小时提醒和实时展示生命周期；专门防止返回宿主页后通知消失、深色锁屏文字不可读和持续会话被后台任务页漏掉。 | JVM 合同测试验证旧任务状态与提醒；fake clock 验证 live 合并与清理；回归测试验证 runtime detach 保留宿主并刷新通知、Focus 明暗文字色完整、后台页按工具合并持续会话；真机启动持续会话，返回宿主页、锁屏、再进入后台任务页并停止。 | 旧任务语义不变；返回宿主页后持续通知仍存在；浅色与深色通知文字均有明确颜色；后台任务页显示并可停止当前工具的持续运行会话；停止后 WebView、timer、位置和通知均释放。 |
| 网络 | `NetworkBoundaryTest`；`ToolNetworkProxyTest` | 在扩展网络方法后仍保护 manifest 域名边界，防止代理退化为内网扫描器或 OOM 入口，并保持旧任务重试合同。 | `NetworkBoundaryTest` 直接验证 HTTPS、精确/通配域名 allowlist、IP literal、重定向开关及私网/保留 IPv4、IPv6、IPv4-mapped IPv6 和 NAT64；`ToolNetworkProxyTest` 用生产代理验证私网 DNS、第二跳复验、响应上限，并向已声明域名的非 443 HTTPS 端口发送 POST、Authorization/JSON body、自定义超时，观察 401 正文返回；另以 503 验证 legacy `httpGet` 重试分类。 | manifest 声明的公网 HTTPS 主机和合法端口可访问；4xx/5xx 作为直接请求响应返回；未声明域名、私网、回环、保留/IP literal 与危险跳转被阻断；超限响应失败；旧任务 5xx 仍为可重试失败。 |
| Miuix 宿主 | `HostNavigationTest`；`HostAdaptiveScrollTest`；`HostScreenLayoutContractTest` | 保护紧凑布局、有效按钮、后台保障入口、稳定滚动和单次 inset。 | Compose 流程以真实内置 `.tbx` 安装器安装三个范例，进入详情与运行壳、权限、旧后台任务和后台保障页面，再从详情菜单删除工具；普通和 200% 字体；JVM 层验证紧凑/中等宽度间距。 | 范例走生产安装路径后可管理；权限开关真实持久化；运行壳、旧后台页与后台保障可达；删除完整；无双 inset、裁剪或固定无效文本。 |
| Miuix 静态视觉 | `HostScreenPreviewScreenshots` | 保护已确认的紧凑 grouped-list、浅深主题、2 倍字体和内容优先运行壳，防止旧审核/签名界面重新进入基线。 | 使用 Android Compose Preview Screenshot 的真实生产 Composable 渲染浅色工具列表、深色工具列表、2 倍字体空状态、工具详情、权限、设置和运行壳，并与仓库基线逐像素比较；不启动模拟器。 | 七个状态均可稳定渲染；无旧审核/风险/发布者内容；2 倍字体时主导航转为保留 TalkBack 标签的图标模式，页面无文字裁剪；运行壳无宿主底栏。 |
| 真机组合 | `manual-xiaomi-toolbox-v1` | 自动门禁不能证明 WebView detach/reattach、后台位置、精确闹钟、前台服务、Android 实时更新、HyperOS 超级岛和系统回收恢复；导航丝滑度也依赖真实设备。 | 安装 0.3.2 后验证三例与原能力；导入行情哨兵，开启通知和外部白名单模块，启动监控、返回宿主页、锁屏、等待更新并停止；同时覆盖 timer/location/alarm、重启恢复和关键进入/返回路径帧数据。 | 同一 runtime 状态连续，恢复事件不丢；通知汇总全部启用股票且明暗模式可读；普通通知始终存在，超级岛仅在系统实际支持时增强；停止/授权关闭/更新/删除无残留；关键页面无可见停顿或残影。 |
| 范例打包 | `scripts/package-examples.sh` 可重复性检查 | 0.3 不修改三个现有范例，但 APK 必须继续内置可重复的相同 `.tbx`。 | 对同一工作树连续运行两次打包脚本并比较 SHA-256；检查 APK assets 中存在三个名称，不把 `.tbx` 复制进最终交付目录。 | 两次哈希一致，三个范例都在 APK assets；最终产物不出现独立 `.tbx`。 |
| 行情哨兵摘要与打包 | `live-summary.test.js`；`examples/stock-monitor/package.sh` 可重复性检查 | 防止多股票实时通知只显示第一只或重复股票名，并确保独立 `.tbx` 可复验。 | Node 回归测试输入两只启用股票，检查标题数量、两只摘要及正文唯一性；随后 `node --check` 并连续打包两次比较 SHA-256，检查 manifest、integrity、ZIP 内容。 | 通知报告 2 只且每只只出现一次；两次包哈希一致；版本为 1.1.1 (3)、`minHostVersion=0.3.2`；ZIP 只含声明文件并使用 `notifications.live`。 |
| CI 交付 | `artifact-gate-receipt` | 防止协议/安全/编译/最小测试未过就发布 APK/TBX，也防止把未执行的设备测试写成通过。 | Actions 按 verify → delivery 的 `needs` 关系运行，检查 `toolbox-v0.3.2-debug.apk`、`stock-monitor-v1.1.1.tbx`、`SHA256SUMS.txt`、提交回执及 `DEVICE_TEST_RESULT=NOT_RUN_USER_OWNED`、`HYPEROS_ISLAND_TEST_RESULT=NOT_RUN_USER_OWNED`。 | 任一静态门禁失败时没有交付产物；成功 APK/TBX 来自同一提交并可按 SHA256 复验，回执明确真机与超级岛验证由用户执行。 |

## 执行原则

- 每次改动只运行受影响的最低忠实层测试，再运行必要编译；不要重复运行无输入变化的全套测试。
- 涉及 WebView、Android permission、SAF、通知或 WorkManager 的改动，除 JVM 测试外必须有相应
  instrumentation 或真实系统表面证据；前者不能替代后者。
- 失败测试不得通过删除断言、降低安全检查或改成静态 UI 来“修绿”；修复后重跑完整相关场景。
- 每阶段报告必须逐项给出实际命令、理由、方法、预期、实际结果和证据路径；未运行即明确写未运行。
