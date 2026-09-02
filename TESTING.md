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
| 导入 | `DirectPackageLifecycleTest`；`ToolBoxOpenDocumentTest` | 核心导入必须只有成功或失败，且失败不能残留；运行中的旧页面不能跨越包替换或删除继续持有文件；文件选择输入必须是一次性、无歧义的 `.tbx` 来源。 | 用有效包完成安装、更新与卸载，在 catalog 切换及版本目录删除前观察运行时释放钩子；以 Zip Slip、嵌套压缩包、完整性损坏、错误包签名和较高 `minHostVersion` 构成拒绝矩阵；用中文字符恰好跨越 4096 字节嗅探边界的合法 HTML 验证增量 UTF-8；并验证取消、非法/双向文件名和一次性输入。 | 有效包及跨嗅探边界的 UTF-8 HTML 均原子可见；更新和卸载先释放运行环境，再切换 catalog 或移除版本文件；上述无效包没有 DB、目录或临时文件残留；非法来源被拒绝且输入流不能重复打开。 |
| 更新/删除 | `CatalogAndStorageRepositoryTest` | 更新和卸载必须清理完整状态，不留下后台或敏感数据。 | 安装后写普通 KV、grant、后台 task/result，再提交更高版本并删除目录。 | 更新仅保留普通 KV 且以新 manifest grants 替换旧 grant；任务/结果消失；删除清理全部数据库工具状态。 |
| 权限与运行时 API | `PermissionCenterViewModelTest`；`RuntimeRpcDispatcherTest` | 防止“有开关没能力”、0.3 新接口覆盖旧 API，或跳过声明、授权、系统权限、手势和配额层。 | 对已支持 capability 用 production dispatcher 分别关闭 declaration、grant、system、gesture、quota 条件；验证公网 POST/Header/JSON 请求与协议级 Header 拒绝、`background.list` 的旧任务语义、`background.listSessions` 的持续环境语义、`notifications.live.start/update/end` 字段边界和错误 session、仅含调度元数据的 alarm、位置 watch 参数及文件一次性令牌。 | 全部条件满足时得到真实 handler 结果；实时通知合法调用返回完整增强状态，错误 session/颜色/字段在写入前稳定失败；旧任务与持续环境列表互不混淆；危险 Header 和未公开参数在调用 handler 前稳定失败；任一授权层缺失稳定拒绝；alarm 无业务 payload；文件令牌只能消费一次。 |
| Bridge | `ToolRuntimeSecurityBoundaryTest`；`HardenedRuntimeWebViewInstrumentationTest` | 保护导入内容的来源与会话边界，同时允许高负载计算离开页面主线程而不放开远程代码或 ServiceWorker。 | JVM 检查 CSP 仅含 `worker-src 'self'` 且不含 wildcard/blob；API 35 WebView 测试 exact origin、main frame、nonce、iframe、导航后的旧 nonce、取消和 ServiceWorker 阻断，并验证被拒 iframe 不能抢占主 frame 完成 `ready` 后的原生事件通道。 | 仅包内 exact-origin 静态 Worker 可加载；远程/blob/data Worker 与 ServiceWorker 被阻断；仅当前主 frame/来源/会话可调用 ToolBox 并接收原生事件，其他请求被拒绝且无副作用。 |
| M3 API | `RuntimeRpcDispatcherTest.m3FileTokensAndLocationFailClosedThroughTypedHandlers` | 文件和定位会触及用户数据及系统权限，必须保证会话令牌、消息配额、能力授权和稳定错误不会被绕过。 | 通过 production dispatcher 验证文件令牌继承创建它的 `files.open`、`files.save` 或 `camera` 能力、只能消费一次；以含引号的非法 ID 和 128 字节合法 ID 验证令牌读取前的 ID 限制及按实际 ID/Base64/JSON 计算的响应预算；直接验证 bridge 对 UTF-8 编码后超额的任意响应替换为 quota failure；验证位置只在通用层要求粗略权限，并将原生位置不可用映射为 typed `NOT_FOUND`。 | 非 shim ID 在消费令牌前失败；边界合法 ID 的完整响应不超过会话配额；首次读取只返回会话配额内的内容，重复读取失败，其他超额响应由 bridge 返回 `QUOTA_EXCEEDED`；粗略授权可进入 handler，精确请求由 handler 再检查 fine；原生空结果不会被取消通道吞掉。 |
| M3 生命周期 | `M3BrokerLifecycleInstrumentedTest` | Dispatcher 假对象不能证明宿主实际使用的临时句柄表和相机缓存会在运行会话结束时清理，也不能证明 Android `Location` 空回调采用 typed failure。 | 在 Android instrumentation 中使用生产 `RuntimeFileSessionResources` 注册真实缓存文件、content URI 与 camera capability，调用会话 `close`；同时调用生产位置回调适配器的 null 与非 null 分支。 | 关闭后令牌、句柄和临时文件计数均为零且真实缓存文件消失；null 结果为非取消型 `RuntimeHandlerException(NOT_FOUND)`，有效 `Location` 成功返回。 |
| 后台 | `BackgroundTaskRepositoryTest`；`BackgroundTaskPolicyTest`；`RuntimeReminderPolicyTest`；`LiveNotificationCoordinatorTest`；`RuntimeNotificationRegressionTest`；`BackgroundTasksPresentationTest`；`manual-xiaomi-toolbox-v1` 的 0.3.2 后台步骤 | 同时保护冻结的 WorkManager 任务语义、持续 runtime 的 12 小时提醒和实时展示生命周期；专门防止返回宿主页后通知消失、深色锁屏文字不可读和持续会话被后台任务页漏掉。 | JVM 合同测试验证旧任务状态与提醒；fake clock 验证 live 合并与清理；回归测试验证 runtime detach 保留宿主并刷新通知、Focus 明暗文字色完整、后台页按工具合并持续会话；真机启动持续会话，返回宿主页、锁屏、再进入后台任务页并停止。 | 旧任务语义不变；返回宿主页后持续通知仍存在；浅色与深色通知文字均有明确颜色；后台任务页显示并可停止当前工具的持续运行会话；停止后 WebView、timer、位置和通知均释放。 |
| 网络 | `NetworkBoundaryTest`；`ToolNetworkProxyTest` | 在扩展网络方法后仍保护 manifest 域名边界，防止代理退化为内网扫描器或 OOM 入口，并保持旧任务重试合同。 | `NetworkBoundaryTest` 直接验证 HTTPS、精确/通配域名 allowlist、IP literal、重定向开关及私网/保留 IPv4、IPv6、IPv4-mapped IPv6 和 NAT64；`ToolNetworkProxyTest` 用生产代理验证私网 DNS、第二跳复验、响应上限，并向已声明域名的非 443 HTTPS 端口发送 POST、Authorization/JSON body、自定义超时，观察 401 正文返回；另以 503 验证 legacy `httpGet` 重试分类。 | manifest 声明的公网 HTTPS 主机和合法端口可访问；4xx/5xx 作为直接请求响应返回；未声明域名、私网、回环、保留/IP literal 与危险跳转被阻断；超限响应失败；旧任务 5xx 仍为可重试失败。 |
| Miuix 宿主 | `CatalogViewModelTest`；`HostNavigationTest`；`HostAdaptiveScrollTest`；`HostScreenLayoutContractTest` | 保护通知/超级岛冷启动时直达所属工具，以及最近使用、搜索、紧凑/中等布局、有效按钮、后台保障入口、稳定滚动和单次 inset。 | JVM 测试先发出工具打开请求、再提供目录数据，并在导航订阅晚于事件时读取结果；验证 `lastOpenedAt` 倒序、紧凑屏最多两个/中等屏最多三个、搜索时隐藏最近使用且只过滤一次准备列表；Compose 流程以真实内置 `.tbx` 安装器安装四个范例，进入详情与运行壳、权限、旧后台任务和后台保障页面，再从详情菜单删除工具；普通和 200% 字体；JVM 层验证紧凑/中等宽度间距。 | 冷启动打开请求不会因目录未加载或导航订阅竞态丢失，并直达对应运行页；最近使用与搜索规则稳定；四个范例走生产安装路径后可管理；权限开关真实持久化；运行壳、旧后台页与后台保障可达；删除完整；无双 inset、裁剪或固定无效文本。 |
| Miuix 静态视觉 | `HostScreenPreviewScreenshots` | 保护已确认的紧凑 grouped-list、浅深主题、空/搜索/已安装状态、后台页面、2 倍字体、中等屏和内容优先运行壳，防止旧审核/签名界面重新进入基线。 | 使用 Android Compose Preview Screenshot 的真实生产 Composable 渲染浅色与深色工具列表、搜索、2 倍字体空状态、工具详情、普通与 2 倍字体权限、设置、后台保障、后台任务、开发帮助、浅深运行壳以及中等屏工具/设置；GitHub 生成并校验截图，不启动本地 Gradle 或模拟器。 | 十五个状态均可稳定渲染；无旧审核/风险/发布者内容；最近使用、分组层级和中等屏适配符合当前视觉契约；2 倍字体时主导航转为保留 TalkBack 标签的图标模式且无文字裁剪；运行壳无宿主底栏。 |
| 真机组合 | `manual-xiaomi-toolbox-v1` | 自动门禁不能证明 WebView detach/reattach、后台位置、精确闹钟、前台服务、Android 实时更新、HyperOS 超级岛和系统回收恢复；导航丝滑度也依赖真实设备。 | 安装 0.3.3 后安装四例，使用通知实验室验证普通/实时通知、锁屏、超级岛更新、打开所属工具和停止当前；同时覆盖 timer/location/alarm、重启恢复和关键进入/返回路径帧数据。 | 同一 runtime 状态连续，恢复事件不丢；通知内容、进度和色调原位更新，普通通知始终存在，超级岛仅在系统实际支持时增强；“打开”直达所属工具，“停止当前”释放对应会话；授权关闭/更新/删除无残留；关键页面无可见停顿或残影。 |
| 范例打包 | `scripts/package-examples.sh` 可重复性检查 | APK 必须内置四个可重复生成的 `.tbx`，其中通知实验室用于真机验证通知通道。 | 对同一工作树连续运行两次打包脚本并比较 SHA-256；检查 APK assets 中存在四个名称，不把 `.tbx` 复制进最终交付目录。 | 两次哈希一致，四个范例都在 APK assets；最终产物不出现独立 `.tbx`。 |
| 行情哨兵摘要与打包 | `live-summary.test.js`；`examples/stock-monitor/package.sh` 可重复性检查 | 防止多股票实时通知只显示第一只或重复股票名，并确保独立 `.tbx` 可复验。 | Node 回归测试输入两只启用股票，检查标题数量、两只摘要及正文唯一性；随后 `node --check` 并连续打包两次比较 SHA-256，检查 manifest、integrity、ZIP 内容。 | 通知报告 2 只且每只只出现一次；两次包哈希一致；版本为 1.1.1 (3)、`minHostVersion=0.3.2`；ZIP 只含声明文件并使用 `notifications.live`。 |
| GitHub 构建守望 | `github-model.test.js`；`DirectPackageLifecycleTest.standalonePackageUnderTestPassesProductionImportLifecycle`；`examples/github-actions-watcher/package.sh`；`GitHub Actions Watcher TBX` | 百分比是本工具估算而非 GitHub 原生字段，且独立打包检查不能替代宿主真实导入链路，必须保护历史样本、仓库分支选择、只读 API、后台摘要和最终 `.tbx` 可安装性。 | 固定 fixtures 覆盖仓库/Actions/workflow 链接、分页、仓库分支候选与近期 run 回退、workflow/分支过滤、1–10 次及淘汰最旧样本、缺失与矩阵 step、并行 job、单调 98% 上限、终态 100%、rerun 重置、多 run 优先级、错误/限流状态和通知摘要；执行 JS 语法检查与两次可重复打包，校验入口前 4096 字节可由当前已安装宿主完整解码，再把实际产物交给 production `ToolPackageManager` 以宿主 0.3.3 完成一次原子导入。 | 默认分支、仓库分支和近期 run 分支按顺序去重后进入下拉候选；所有模型边界稳定；只访问 `api.github.com` 的只读接口；活动构建通知内容不重复错位；两个包 SHA-256 一致；生产安装器返回 `Installed(io.toolbox.githubactionswatcher, 2, false)` 且无临时残留；CI 回执明确 APK、真机和超级岛未执行。 |
| CI 交付 | `artifact-gate-receipt` | 防止协议/安全/编译/最小测试未过就发布 APK/TBX，也防止临时 Runner 每次生成不同调试签名而破坏覆盖安装。 | Actions 按 verify → delivery 的 `needs` 关系运行；delivery 必须从 GitHub Secrets 恢复固定 keystore，比较 keystore 与 APK 的 SHA-256 证书指纹，再检查 `toolbox-v0.3.3-debug.apk`、`stock-monitor-v1.1.1.tbx`、`SHA256SUMS.txt`、提交回执及 `DEVICE_TEST_RESULT=NOT_RUN_USER_OWNED`、`HYPEROS_ISLAND_TEST_RESULT=NOT_RUN_USER_OWNED`。 | 任一静态门禁、签名密钥缺失或证书不一致时没有交付产物；连续 GitHub 构建的 APK 证书指纹一致；成功 APK/TBX 来自同一提交并可按 SHA256 复验，回执明确真机与超级岛验证由用户执行。 |

## 执行原则

- 每次改动只运行受影响的最低忠实层测试，再运行必要编译；不要重复运行无输入变化的全套测试。
- 涉及 WebView、Android permission、SAF、通知或 WorkManager 的改动，除 JVM 测试外必须有相应
  instrumentation 或真实系统表面证据；前者不能替代后者。
- 失败测试不得通过删除断言、降低安全检查或改成静态 UI 来“修绿”；修复后重跑完整相关场景。
- 每阶段报告必须逐项给出实际命令、理由、方法、预期、实际结果和证据路径；未运行即明确写未运行。

## 导入边界补充

- `DirectPackageLifecycleTest.rejectedSecurityMatrixLeavesNoCatalogOrFiles` 同时覆盖短 HTML 和恰好 4096 字节 HTML 在真实文件结尾缺失 UTF-8 后续字节的情况。理由：不能把嗅探截断与损坏文件混为一谈；方法：读取一个前瞻字节以确认是否真正到达 EOF，再由生产导入器验证两种损坏结尾；预期：均返回 `ENTRY_MIME_INVALID` 且无残留，而合法跨 4096 字节字符仍可安装。只在 GitHub 运行 Kotlin 测试。

## 独立工具执行回执

- `github-actions-watcher-v1.0.1`：本地只运行 JS 语法、Node 模型测试与浏览器界面检查，不运行 Gradle、模拟器或 APK 构建；正式包由 GitHub CI 两次打包比对，并使用 production `ToolPackageManager` 验证实际 `.tbx` 可导入，把结果、提交号和产物哈希写入独立 artifact 内的 `BUILD_AND_TEST_RECEIPT.txt` 与 `SHA256SUMS.txt`。浏览器通过生产页面事件与固定 GitHub 响应验证页内展开三个分支、点击选择、清除旧手填值、切换全部分支和恢复选择，以及键盘打开与 Escape 收起；17 项 Node 模型测试通过。不把浏览器结果当作 Android WebView 验收。真机后台、锁屏和 HyperOS 超级岛状态保持 `NOT_RUN_USER_OWNED`，由用户验收后补充证据。
