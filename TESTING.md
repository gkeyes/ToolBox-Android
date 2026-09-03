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
| 开发帮助 | `scripts/check-developer-help.mjs`；`DeveloperHelpDocumentTest`；`DeveloperHelpScreenTest` | 防止帮助页复制出的源码、声明与实际接口不同步，或折叠/搜索让正文与复制功能不可达。 | Node 静态检查手册层级、嵌入源码、JS/JSON 与 SDK；模拟桥执行最小工程保存/重开/复制，以及后台显式启动、timer、restore、停止清理；JVM 读取与 APK 同源的完整手册并测试生产解析和跨层搜索；Compose 测试同级互斥折叠、搜索、复制与范例入口。 | 源码与合同一致；复制内容保持原文；基础与后台模拟路径真实调用对应 API；未展开正文不出现，搜索可定位主题，折叠不改变正文；不把模拟桥结果当成 Android 后台或通知验证。 |
| 通用工具打包 | `scripts/tests/test_package_tool.py` | 帮助手册必须能打包用户自己的目录，且不能意外覆盖文件或漏打额外静态资源。 | Python 标准库测试从最小模板复制临时工程，加入嵌套资源和 Worker，两次打包比较原始 ZIP/哈希并复算完整性；验证显式覆盖、错误入口、嵌套归档、文件/目录符号链接与源目录内输出拒绝。 | ZIP 根目录与完整性正确，两次结果相同；默认不覆盖既有文件；无效输入不留下输出或临时文件；测试不修改四个内置范例。 |
| 导入 | `DirectPackageLifecycleTest`；`ToolBoxOpenDocumentTest` | 核心导入必须只有成功或失败，且失败不能残留；运行中的旧页面不能跨越包替换或删除继续持有文件；文件选择输入必须是一次性、无歧义的 `.tbx` 来源。 | 用有效包完成安装、更新与卸载，在 catalog 切换及版本目录删除前观察运行时释放钩子；以 Zip Slip、嵌套压缩包、完整性损坏、错误包签名和较高 `minHostVersion` 构成拒绝矩阵；用中文字符恰好跨越 4096 字节嗅探边界的合法 HTML 验证增量 UTF-8；并验证取消、非法/双向文件名和一次性输入。 | 有效包及跨嗅探边界的 UTF-8 HTML 均原子可见；更新和卸载先释放运行环境，再切换 catalog 或移除版本文件；上述无效包没有 DB、目录或临时文件残留；非法来源被拒绝且输入流不能重复打开。 |
| 更新/删除 | `CatalogAndStorageRepositoryTest` | 更新和卸载必须清理完整状态，不留下后台或敏感数据。 | 安装后写普通 KV、grant、后台 task/result，再提交更高版本并删除目录。 | 更新仅保留普通 KV 且以新 manifest grants 替换旧 grant；任务/结果消失；删除清理全部数据库工具状态。 |
| 权限与运行时 API | `PermissionCenterViewModelTest`；`RuntimeRpcDispatcherTest` | 防止“有开关没能力”、0.3 新接口覆盖旧 API，或跳过声明、授权、系统权限、手势和配额层。 | 对已支持 capability 用 production dispatcher 分别关闭 declaration、grant、system、gesture、quota 条件；验证公网 POST/Header/JSON 请求与协议级 Header 拒绝、`background.list` 的旧任务语义、`background.listSessions` 的持续环境语义、`notifications.live.start/update/end` 字段边界和错误 session、仅含调度元数据的 alarm、位置 watch 参数及文件一次性令牌。 | 全部条件满足时得到真实 handler 结果；实时通知合法调用返回完整增强状态，错误 session/颜色/字段在写入前稳定失败；旧任务与持续环境列表互不混淆；危险 Header 和未公开参数在调用 handler 前稳定失败；任一授权层缺失稳定拒绝；alarm 无业务 payload；文件令牌只能消费一次。 |
| Bridge | `ToolRuntimeSecurityBoundaryTest`；`HardenedRuntimeWebViewInstrumentationTest` | 保护导入内容的来源与会话边界，同时允许高负载计算离开页面主线程而不放开远程代码或 ServiceWorker。 | JVM 检查 CSP 仅含 `worker-src 'self'` 且不含 wildcard/blob；API 35 WebView 测试 exact origin、main frame、nonce、iframe、导航后的旧 nonce、取消和 ServiceWorker 阻断，并验证被拒 iframe 不能抢占主 frame 完成 `ready` 后的原生事件通道。 | 仅包内 exact-origin 静态 Worker 可加载；远程/blob/data Worker 与 ServiceWorker 被阻断；仅当前主 frame/来源/会话可调用 ToolBox 并接收原生事件，其他请求被拒绝且无副作用。 |
| M3 API | `RuntimeRpcDispatcherTest.m3FileTokensAndLocationFailClosedThroughTypedHandlers` | 文件和定位会触及用户数据及系统权限，必须保证会话令牌、消息配额、能力授权和稳定错误不会被绕过。 | 通过 production dispatcher 验证文件令牌继承创建它的 `files.open`、`files.save` 或 `camera` 能力、只能消费一次；以含引号的非法 ID 和 128 字节合法 ID 验证令牌读取前的 ID 限制及按实际 ID/Base64/JSON 计算的响应预算；直接验证 bridge 对 UTF-8 编码后超额的任意响应替换为 quota failure；验证位置只在通用层要求粗略权限，并将原生位置不可用映射为 typed `NOT_FOUND`。 | 非 shim ID 在消费令牌前失败；边界合法 ID 的完整响应不超过会话配额；首次读取只返回会话配额内的内容，重复读取失败，其他超额响应由 bridge 返回 `QUOTA_EXCEEDED`；粗略授权可进入 handler，精确请求由 handler 再检查 fine；原生空结果不会被取消通道吞掉。 |
| M3 生命周期 | `M3BrokerLifecycleInstrumentedTest` | Dispatcher 假对象不能证明宿主实际使用的临时句柄表和相机缓存会在运行会话结束时清理，也不能证明 Android `Location` 空回调采用 typed failure。 | 在 Android instrumentation 中使用生产 `RuntimeFileSessionResources` 注册真实缓存文件、content URI 与 camera capability，调用会话 `close`；同时调用生产位置回调适配器的 null 与非 null 分支。 | 关闭后令牌、句柄和临时文件计数均为零且真实缓存文件消失；null 结果为非取消型 `RuntimeHandlerException(NOT_FOUND)`，有效 `Location` 成功返回。 |
| 后台 | `BackgroundTaskRepositoryTest`；`BackgroundTaskPolicyTest`；`RuntimeReminderPolicyTest`；`LiveNotificationCoordinatorTest`；`RuntimeNotificationRegressionTest`；`BackgroundTasksPresentationTest`；`manual-xiaomi-toolbox-v1` 的后台步骤 | 同时保护冻结的 WorkManager 任务语义、持续 runtime 的 12 小时提醒和独立实时卡生命周期；防止工具争用一张卡、返回宿主页后通知消失和持续会话被后台任务页漏掉。 | JVM 合同测试验证旧任务状态与提醒；production live coordinator/controller 配合 fake clock 和发布器验证多卡合并、移交与清理；回归测试验证 runtime detach 保留宿主、Focus 明暗两套文字字段均为白色、后台页按工具显示持续会话；真机启动两个工具，返回宿主页、锁屏、分别停止。 | 旧任务语义不变；返回宿主页后持续通知保留；各卡独立更新与停止。文字请求仍为白色，SystemUI 实际颜色单独验收；后台任务页可停止当前工具的持续会话；停止后对应 WebView、timer、位置和通知释放，其他工具不受影响。 |
| 实时通知对象与操作 | `RuntimeLiveNotificationRendererTest` | 防止独立卡遗漏占位文本、携带摘要标记失去增强资格，或 PendingIntent 串到其他会话。 | production renderer 构建每工具普通占位与两张独立实时卡，Android Parcel 往返后检查文本白色 span、无分组摘要、两项操作、独立 Focus 编号/序号和默认隐藏参数；构造可发生字符串哈希碰撞的不同会话，检查打开/停止 PendingIntent 身份。 | 每张卡只含自身数据，只有打开与停止当前，Android 36+ 实时对象具有 promoted 特征；无自定义 deleteIntent；PendingIntent 跨工具/会话/动作不同且不可变，重复生成同一动作不新增身份。仅证明对象数据，不替代 HyperOS SystemUI 真机显示。 |
| 网络 | `NetworkBoundaryTest`；`ToolNetworkProxyTest` | 在扩展网络方法后仍保护 manifest 域名边界，防止代理退化为内网扫描器或 OOM 入口，并保持旧任务重试合同。 | `NetworkBoundaryTest` 直接验证 HTTPS、精确/通配域名 allowlist、IP literal、重定向开关及私网/保留 IPv4、IPv6、IPv4-mapped IPv6 和 NAT64；`ToolNetworkProxyTest` 用生产代理验证私网 DNS、第二跳复验、响应上限，并向已声明域名的非 443 HTTPS 端口发送 POST、Authorization/JSON body、自定义超时，观察 401 正文返回；另以 503 验证 legacy `httpGet` 重试分类。 | manifest 声明的公网 HTTPS 主机和合法端口可访问；4xx/5xx 作为直接请求响应返回；未声明域名、私网、回环、保留/IP literal 与危险跳转被阻断；超限响应失败；旧任务 5xx 仍为可重试失败。 |
| Miuix 宿主 | `CatalogViewModelTest`；`HostNavigationTest`；`HostAdaptiveScrollTest`；`HostScreenLayoutContractTest` | 保护通知/超级岛冷启动时直达所属工具，以及最近使用、搜索、紧凑/中等布局、有效按钮、后台保障入口、稳定滚动和单次 inset。 | JVM 测试先发出工具打开请求、再提供目录数据，并在导航订阅晚于事件时读取结果；验证 `lastOpenedAt` 倒序、紧凑屏最多两个/中等屏最多三个、搜索时隐藏最近使用且只过滤一次准备列表；Compose 流程以真实内置 `.tbx` 安装器安装四个范例，进入详情与运行壳、权限、旧后台任务和后台保障页面，再从详情菜单删除工具；普通和 200% 字体；JVM 层验证紧凑/中等宽度间距。 | 冷启动打开请求不会因目录未加载或导航订阅竞态丢失，并直达对应运行页；最近使用与搜索规则稳定；四个范例走生产安装路径后可管理；权限开关真实持久化；运行壳、旧后台页与后台保障可达；删除完整；无双 inset、裁剪或固定无效文本。 |
| Miuix 静态视觉 | `HostScreenPreviewScreenshots` | 保护已确认的紧凑 grouped-list、浅深主题、空/搜索/已安装状态、后台页面、2 倍字体、中等屏和内容优先运行壳，防止旧审核/签名界面重新进入基线。 | 使用 Android Compose Preview Screenshot 的真实生产 Composable 渲染浅色与深色工具列表、搜索、2 倍字体空状态、工具详情、普通与 2 倍字体权限、设置、后台保障、后台任务、浅色/深色/2 倍字体开发帮助、浅深运行壳以及中等屏工具/设置；GitHub 生成并校验截图，不启动本地 Gradle 或模拟器。 | 十七个状态均可稳定渲染；无旧审核/风险/发布者内容；最近使用、分组层级和中等屏适配符合当前视觉契约；2 倍字体时主导航转为保留 TalkBack 标签的图标模式且无文字裁剪；运行壳无宿主底栏。 |
| 真机组合 | `manual-xiaomi-toolbox-v1` | 自动门禁不能证明 WebView detach/reattach、后台位置、精确闹钟、前台服务、Android 实时更新、HyperOS 超级岛和系统回收恢复；导航丝滑度也依赖真实设备。 | 安装 0.3.4 后安装四例，使用通知实验室验证普通/实时通知、锁屏、超级岛更新、打开所属工具和停止当前；同时覆盖 timer/location/alarm、重启恢复和关键进入/返回路径帧数据。 | 同一 runtime 状态连续，恢复事件不丢；通知内容、进度和色调原位更新，普通通知始终存在，超级岛仅在系统实际支持时增强；“打开”直达所属工具，“停止当前”释放对应会话；授权关闭/更新/删除无残留；关键页面无可见停顿或残影。 |
| 范例打包 | `scripts/package-examples.sh` 可重复性检查 | APK 必须内置四个可重复生成的 `.tbx`，其中通知实验室用于真机验证通知通道。 | 对同一工作树连续运行两次打包脚本并比较 SHA-256；检查 APK assets 中存在四个名称，不把 `.tbx` 复制进最终交付目录。 | 两次哈希一致，四个范例都在 APK assets；最终产物不出现独立 `.tbx`。 |
| 行情哨兵摘要与打包 | `live-summary.test.js`；`examples/stock-monitor/package.sh` 可重复性检查 | 防止多股票实时通知只显示第一只或重复股票名，并确保独立 `.tbx` 可复验。 | Node 回归测试输入两只启用股票，检查标题数量、两只摘要及正文唯一性；随后 `node --check` 并连续打包两次比较 SHA-256，检查 manifest、integrity、ZIP 内容。 | 通知报告 2 只且每只只出现一次；两次包哈希一致；版本为 1.1.1 (3)、`minHostVersion=0.3.2`；ZIP 只含声明文件并使用 `notifications.live`。 |
| GitHub 构建守望 | `github-model.test.js`；`DirectPackageLifecycleTest.standalonePackageUnderTestPassesProductionImportLifecycle`；`examples/github-actions-watcher/package.sh`；`GitHub Actions Watcher TBX` | 百分比是本工具估算而非 GitHub 原生字段，且独立打包检查不能替代宿主真实导入链路，必须保护历史样本、仓库分支选择、只读 API、后台摘要和最终 `.tbx` 可安装性。 | 固定 fixtures 覆盖仓库/Actions/workflow 链接、分页、仓库分支候选与近期 run 回退、workflow/分支过滤、1–10 次及淘汰最旧样本、缺失与矩阵 step、并行 job、单调 98% 上限、终态 100%、rerun 重置、多 run 优先级、错误/限流状态和通知摘要；执行 JS 语法检查与两次可重复打包，校验入口前 4096 字节可由当前已安装宿主完整解码，再把实际产物交给 production `ToolPackageManager` 以宿主 0.3.4 完成一次原子导入。 | 默认分支、仓库分支和近期 run 分支按顺序去重后进入下拉候选；所有模型边界稳定；只访问 `api.github.com` 的只读接口；活动构建通知内容不重复错位；两个包 SHA-256 一致；生产安装器返回 `Installed(io.toolbox.githubactionswatcher, 2, false)` 且无临时残留；CI 回执明确 APK、真机和超级岛未执行。 |
| CI 交付 | `artifact-gate-receipt` | 防止协议/安全/编译/最小测试未过就发布 APK，也防止临时 Runner 每次生成不同调试签名而破坏覆盖安装。 | Actions 按 verify → delivery 的 `needs` 关系运行；delivery 必须从 GitHub Secrets 恢复固定 keystore，比较 keystore 与 APK 的 SHA-256 证书指纹，再检查 `toolbox-v0.3.6-debug.apk`、`SHA256SUMS.txt`、提交回执及 `DEVICE_TEST_RESULT=NOT_RUN_USER_OWNED`、`HYPEROS_ISLAND_TEST_RESULT=NOT_RUN_USER_OWNED`。 | 任一静态门禁、签名密钥缺失或证书不一致时没有交付产物；连续 GitHub 构建的 APK 证书指纹一致；成功 APK 与回执来自同一提交并可按 SHA256 复验，不另交付 `.tbx`，回执明确真机与超级岛验证由用户执行。 |

## 执行原则

- 每次改动只运行受影响的最低忠实层测试，再运行必要编译；不要重复运行无输入变化的全套测试。
- 涉及 WebView、Android permission、SAF、通知或 WorkManager 的改动，除 JVM 测试外必须有相应
  instrumentation 或真实系统表面证据；前者不能替代后者。
- 失败测试不得通过删除断言、降低安全检查或改成静态 UI 来“修绿”；修复后重跑完整相关场景。
- 每阶段报告必须逐项给出实际命令、理由、方法、预期、实际结果和证据路径；未运行即明确写未运行。

## 0.3.6 多工具独立实时通知回归

- 范围：每个持续会话独立编号、独立卡片和打开/停止操作，一个前台服务稳定承载其中一张卡。按用户最新决定，隐藏完全交给手机默认机制，不实现隐藏标记、恢复按钮、暂停状态或 `SUPPRESSED` 回执。
- `LiveNotificationCoordinatorTest`：理由是实际发生过两个工具争用同一张卡；方法是以 production coordinator/controller、fake clock 和模拟 Android 换前台编号会移除旧编号的发布器，覆盖两种启动顺序、第三个会话、500ms 最新值合并、单卡结束/停止、待发布更新清理、承载移交、发布失败隔离和稳定编号恢复；预期是只更新变化的卡、结束实时后同号退回普通后台状态、停止一张不影响其他卡、最后停止时无残留，哈希碰撞不产生同号。
- `RuntimeLiveNotificationRendererTest`：理由是 JVM 状态不能证明真实 Notification 对象与 PendingIntent；方法是现有 Android 对象测试补上双工具无 `GROUP_SUMMARY`、独立 Focus identity、默认不强制重开/重排，以及不同动作身份与不可变校验；预期是每张卡分别满足增强数据要求且动作不串工具。只在 GitHub 编译测试源码，不启动模拟器；对象运行和 SystemUI 展示仍由真机验证。
- `BackgroundTasksPresentationTest` 与截图夹具仅补充宿主通知编号，不改变后台页面内容、四个范例或工具 API。截图继续使用现有 17 状态与原像素门禁；版本文本变化须检查 GitHub 原始渲染后更新相应基线。
- 最小静态检查：`node scripts/check-developer-help.mjs`、`bash scripts/verify-security-invariants.sh "$PWD"`、`git diff --check`；协议、Kotlin、现有最小 JVM 用例和 APK 构建只在 GitHub 执行。实际结果写入同提交交付回执，本地源码检查不记作编译或真机通过。
- 本地实际结果：手册的 7 章节、27 主题、27 代码块与模拟桥示例检查通过，安全扫描与差异格式检查通过；没有运行本地 Gradle、APK 构建、模拟器或操作手机。LSP daemon 不可达，Kotlin 类型与 Android 测试源码检查由 GitHub 完成。
- 首轮 GitHub `33735186322`（`8a65be660c9981d2b7b8ef7ae9c3d9896f0d5f2e`）：协议、安全、宿主与 Android 测试源码编译、最小 JVM 测试全部退出 0。17 张预览仅设置浅色与中等屏深色两张因版本号 `0.3.5 → 0.3.6` 失败；读取 GitHub 原始图像并复核像素差异仅为末位数字后，直接采用该次渲染更新这两张基线，不改变页面、像素阈值或其余 15 张基线。最终交付须等待后续同提交完整门禁成功。
- 真机验收：同时启动 GitHub 守望与通知实验室，分别更新；划掉一张后观察手机默认行为；停止承载会话后另一张仍保留，点击各自打开所属工具；最后停止所有会话。真实多卡、超级岛数量/排序、后台存活及浅色主题文字色均标记 `NOT_RUN_USER_OWNED`，本次不宣称独立黑字问题已解决。

## 0.3.5 三项问题回归

| 测试 | 理由 | 方法 | 预期与实际边界 |
|---|---|---|---|
| `HostNavigationTest` 的详情操作 | 更多与底部删除曾把弹层注册在 Miuix Scaffold 之外，点击后不可见。 | 生产详情页点击底部删除、取消，再点击更多中的删除并确认。 | 两条入口都显示实际弹层，取消保留工具，确认才删除。源码由 GitHub 编译，交互由用户真机验证，不启动模拟器。 |
| `RuntimeLiveNotificationRendererTest` | 已有实时内容的白字配置没有覆盖恢复占位。 | 在无 Focus 与 Focus V3 已支持但报告权限未允许两种状态下构建准备、恢复、单/多会话通知，Parcel 往返检查普通文本，以及实际 `miui.focus.param` 内两组全部明暗文字字段；同时检查未启用 colorized。 | 全状态均携带白色配置，仍保留 Android promoted ongoing 资格。只证明生产通知数据与编译，不将其视为小米 SystemUI 颜色验证。 |
| `RuntimeNetworkGatewayTest` | 文本被错误扣除 Base64 膨胀预算，且配额、连接错误被伪装为网络阻止。 | 通过宿主实际使用的 gateway 和生产 proxy 注入 800000/1500000 字节 JSON、超限响应、未声明域名、连接及正文读取阶段的 IO/超时异常。 | 预算内 JSON 完整返回；超限为 `QUOTA_EXCEEDED`，只有地址策略拒绝为 `NETWORK_BLOCKED`，连接/超时分别返回 `NETWORK_UNAVAILABLE`/`NETWORK_TIMEOUT`；错误不含底层敏感细节。GitHub 运行 JVM 测试。 |
| `examples/github-actions-watcher/app.test.js` | 原生错误不能再次被网页包装为普通断网。 | 启动生产 app.js，点击已注册的仓库读取回调，参数化注入配额、超时、连接、域名及权限错误。 | 请求使用 4 MiB 响应预算，五类原生错误完整到达提示，结束加载并可重试；测试使用模拟桥，不代表 Android 宿主执行。 |
| 守望 1.0.2 生产导入与包验证 | 扩大的消息上限必须能通过真正的安装器，不能只让 ZIP 校验通过。 | GitHub 运行 18 项 Node 测试、两次打包和完整性校验，production `ToolPackageManager` 按宿主 0.3.5 导入实际产物。 | 版本为 1.0.2 (3)、最低宿主 0.3.5、网络 4 MiB、消息 8 MiB，安装器返回 `Installed(io.toolbox.githubactionswatcher, 3, false)` 且无残留。 |

- 本次只读访问真实仓库 `gkeyes/ToolBox-Android`：仓库/工作流/运行列表均 HTTP 200；64 条运行列表解压后为 788287 字节，超过旧 785664 字节预扣上限，但其 JSON 包装仍小于原 1 MiB 消息上限。只记录状态与长度，不记录响应正文或 Token。
- 内置浏览器使用生产 HTML/JS 加独立测试适配器，同一份真实 GitHub 响应重放旧预算时复现 `NETWORK_BLOCKED`；改用修正预算后显示两个 workflow、两个仓库分支，选中 main 和 Android CI 后开始按钮可用，无页面异常。此验证不替代 Android 网络代理 JVM 测试或真机后台验证。
- 本地手册检查、JS 测试与安全扫描执行；Android Kotlin、原生交互测试源码和截图只由 GitHub 编译/校验，实际构建与测试结果绑定同提交的 Actions 和交付回执。未操作手机、未本机编译、未运行模拟器。
- GitHub 首轮 `33722368430`（提交 `4b09491bb5b27c6ed16278632df10350ab37fa9d`）：合同、安全、Kotlin/Android 测试源码编译与最小 JVM 测试通过；17 张预览仅设置浅色/中等屏深色两张存在版本数字 `0.3.4 → 0.3.5` 差异。检查实际渲染与差异图后，以该次 GitHub 原始渲染更新两张基线，不改变像素阈值或页面断言。守望流程 `33722368426` 的 18 项 JS 测试、重复打包和实际导入通过。

## 权限页误报回归（2026-09-03，本地修复）

- 理由：权限页曾使用过期的默认宿主版本，将仍安装着的工具误报为不存在，并叠加没有权限的空状态。原权限测试只提供已经读取好的假 manifest，不能覆盖这条生产路径。
- 方法：在现有 `PermissionCenterViewModelTest` 中使用 production `ToolPackageManager`、临时 ZIP/文件目录和 `InMemoryCoreData` 安装最低版本等于 `BuildConfig.VERSION_NAME` 的五权限工具，再通过与 App 相同的 `HostInstalledManifestReader` 和真实 ViewModel 读取；比较读取前后 grants，并实际打开网络 grant。参数化改写安装后的 manifest 为更高最低版本或损坏内容，另覆盖真正未安装和合法空声明。
- 预期：正常路径得到五个真实开关且不擅自开启权限；显式切换可保存；版本及文件校验失败保留 typed 原因，工具记录与 grants 不丢；只有真正未安装才显示不存在，只有读取成功且声明为空才显示无权限。生产页面按互斥读取状态渲染，不把失败当空列表。
- GitHub 执行入口：现有门禁已包含 `:app:testDebugUnitTest --tests io.toolbox.host.permissions.PermissionCenterViewModelTest`；没有新增依赖、模拟器或交付任务。`ToolRuntimeSecurityBoundaryTest` 仅改为显式传入测试宿主版本，原安全断言保持不变。
- 本轮实际结果：回归用例已补齐，尚未编译或执行；遵守不在本机编译的要求，没有运行 Gradle、启动模拟器或操作手机，不把源码检查记为 Kotlin、界面或真机验证通过。
- 本地静态检查：`bash scripts/verify-security-invariants.sh /Users/jianchen/Downloads/ToolBox_Codex_Package` 返回 `Security invariants verified.`，`git diff --check` 无错误；读取器所有生产构造点均显式使用当前 App 版本。Kotlin LSP daemon 不可达，因此类型检查、上述 JVM 回归及权限页真机显示仍待 GitHub/用户验证。本轮未提交或推送 GitHub。
- 后续 GitHub 交付：用户确认后按 `0.3.5 (9)` 构建权限页修复版，继续使用固定签名，不改变 `.tbx`、API 或数据库。上述用例由既有 GitHub 门禁执行，成功产物的同提交回执增加 `PERMISSION_MANIFEST_REGRESSION=PASS`；实际 Actions 结果与手机上的最终显示分开报告，不进行本地 Android 编译或代用户安装。

## 导入边界补充

- `DirectPackageLifecycleTest.rejectedSecurityMatrixLeavesNoCatalogOrFiles` 同时覆盖短 HTML 和恰好 4096 字节 HTML 在真实文件结尾缺失 UTF-8 后续字节的情况。理由：不能把嗅探截断与损坏文件混为一谈；方法：读取一个前瞻字节以确认是否真正到达 EOF，再由生产导入器验证两种损坏结尾；预期：均返回 `ENTRY_MIME_INVALID` 且无残留，而合法跨 4096 字节字符仍可安装。只在 GitHub 运行 Kotlin 测试。

## 开发帮助本地改造（2026-09-03，未提交/未编译）

- 手册：7 个章节、27 个主题，App 从 assets 离线读取仓库同一份 Markdown；能力、运行时、路由和 App 版本未更改。
- `node scripts/check-developer-help.mjs`：最终检查通过；27 个代码块的语法/嵌入源码与 SDK 检查，以及最小工程和后台示例的模拟桥执行均通过。
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -p 'test_package_tool.py' -v`：4 项测试通过，产物只存在于测试临时目录并由测试回收。
- 打包器命令行实用检查通过：帮助可读取、最小模板可打包、重复输出被拒绝；测试临时包已回收，没有生成正式交付物。
- 安全不变量扫描与 `git diff --check` 通过。界面静态探测器以正则降级模式运行，未报告问题；由于缺少 HTML/CSS 解析依赖，未验证计算后的对比度或选择器匹配，不作为视觉验收证据。
- 用户本轮要求不提交 GitHub、不编译：没有运行 Gradle、APK 打包、模拟器或真机。新增 JVM/Compose 测试尚未执行，开发帮助截图基线尚未重新生成；现有截图不能作为本次改造的验证结果。
- Kotlin LSP 状态检查返回 daemon unreachable；未安装、重启或借用 Android 编译代替静态检查，Kotlin 编译结果保持未验证。

## 实时通知白字修正（2026-09-03，未提交/未编译）

- 根据用户的深色实时通知截图，修正恢复占位使用未着色文本、Focus 浅色主题字段使用黑色的两条生产路径；准备、恢复、正常实时内容、多会话摘要及操作文字统一携带白色。普通非实时通知不变。
- `RuntimeNotificationRegressionTest` 改为检查 Focus 全部明暗文字字段均为白色；新增 `RuntimeLiveNotificationRendererTest` 在真实 Android Notification/Parcel 层覆盖四种状态，验证文字 span 不丢失。两项测试本轮均未编译、未执行，不能记为通过。
- 本地安全不变量扫描及 `git diff --check` 通过；检查旧调色函数已无引用。LSP daemon 仍不可达，不将源码检查替代 Kotlin 编译结果。
- 遵守本轮交付边界：没有提交/推送 GitHub、触发 Actions、编译或操作手机。尚未验证 SystemUI 最终呈现；后续在浅色、深色主题下分别检查恢复通知与实时更新，确认白字及打开/停止操作正常。

## 0.3.4 候选版收尾

- 范围：开发帮助、深色实时通知白字、相应验证及版本交付配置。维持四个内置范例、现有 API、数据库与签名密钥；未增加业务功能。
- GitHub 必须运行手册/打包器检查、生产手册 JVM 解析、现有最小单元测试、App 与运行时 instrumentation 源码编译，以及宿主截图矩阵。帮助页截图使用实际离线手册与生产页面，覆盖浅色、深色和 2 倍字体；不以加载占位代替正文。
- 基线只根据 GitHub 实际渲染图、人工查看后的结果更新；CI 不在校验前自动生成期望图，不删除其他页面测试。设备交互测试仍由用户执行，不启动模拟器。
- 本地手册校验、4 项打包测试、安全静态扫描、shell 语法和差异检查通过。Kotlin、截图及 APK 编译全部交给 GitHub；最终实际结果以同提交的 BUILD_AND_TEST_RECEIPT.txt 与 Actions 日志为准。
- GitHub run `33710029713`（源码 `474fde1e81bf7f43d35415e67b3786169206a3bd`）的 App、instrumentation 源码编译及最小单元测试通过。17 个截图中 12 个既有状态匹配；人工查看实际渲染后，更新三种开发帮助基线及仅版本号变化的两种设置基线。其他基线不变；后续候选必须重新通过全部截图比较，不能以本次基线更新替代验证。

## 独立工具执行回执

- `github-actions-watcher-v1.0.1`：本地只运行 JS 语法、Node 模型测试与浏览器界面检查，不运行 Gradle、模拟器或 APK 构建；正式包由 GitHub CI 两次打包比对，并使用 production `ToolPackageManager` 验证实际 `.tbx` 可导入，把结果、提交号和产物哈希写入独立 artifact 内的 `BUILD_AND_TEST_RECEIPT.txt` 与 `SHA256SUMS.txt`。浏览器通过生产页面事件与固定 GitHub 响应验证页内展开三个分支、点击选择、清除旧手填值、切换全部分支和恢复选择，以及键盘打开与 Escape 收起；17 项 Node 模型测试通过。不把浏览器结果当作 Android WebView 验收。真机后台、锁屏和 HyperOS 超级岛状态保持 `NOT_RUN_USER_OWNED`，由用户验收后补充证据。

## 首页正在运行（2026-09-03，本地实现）

| 测试/检查 | 理由 | 方法 | 预期 |
|---|---|---|---|
| `RunningToolsViewModelTest` | 防止停止误伤其他会话、重复执行或过期弹层停止新环境。 | 生产 ViewModel 接入可控会话流和停止函数，覆盖取消、重复确认、源状态消失、同工具新 session 替换、失败后重试。加入既有 host gate 的最小 JVM 测试集合。 | 只有确认的当前 sessionId 被停止；列表只随真实来源移除；旧弹层失效；错误不泄漏内部信息。 |
| `CatalogRunningToolsTest` | 确认入口位置与两个独立操作，而不是只有静态状态标签。 | 生产首页、分组和 ViewModel，回放零会话到两会话、点击名称、取消停止、逐个确认停止；另外以 2× 字体检查名称和状态按钮的独立语义及至少 48dp 目标。 | 运行区位于最近使用上方；名称只打开；确认只停止所选；最后一个停止后分组隐藏。 |
| `HomeRunningToolsPreviews` | 为原生浅色、深色、2× 字体、中等屏幕和停止确认提供一致的审图入口。 | 使用生产 `ToolManagerScreen` 与 `CatalogRunningToolsContent` 的 Compose Preview；原 17 项截图测试不改动、不替换。 | 真实背景会话与目录夹具一致；长名称可换行，状态按钮清晰；不得把 HTML 示意图当作原生截图。 |
| 静态边界 | 新入口不得改变后台生命周期、通知、权限或 API。 | 安全不变量扫描、门禁脚本语法及差异检查；核对页面只调用现有 `RuntimeSessionManager.stopSession(sessionId)` 和目录打开操作。 | 未新增网络/数据库/通知机制，安全扫描通过，新增状态不在导航根收集。 |

- 本轮没有运行 Gradle、Kotlin 编译、模拟器、真机或 GitHub Actions；新增 JVM/Compose 测试尚未执行，原生 Preview 尚未渲染。它们属于待 GitHub 编译和真机验收项目，不能记为通过。
- 本地 Kotlin LSP daemon 不可达；未通过安装开发环境或本机编译绕过用户约束。静态扫描不等同于 Kotlin 类型检查或原生交互验证。
- 首页继续复用已安装列表的图标策略：四个内置范例使用既有图片，外部工具使用既有分类图标；本轮不扩大到包内图标加载。
- 已执行：生产源码安全不变量扫描、门禁 shell 语法检查和差异空白检查均退出成功。运行按钮浅/深色文字与背景 Token 的计算对比度分别为 4.83:1、7.60:1；这不是最终屏幕渲染或 TalkBack 实测结果。

## 工具图标贯通（2026-09-03，本地实现）

图标来自当前安装包 manifest.icon，列表、通知内容图和 HyperOS 岛图共用异步加载结果。

| 测试/检查 | 理由 | 方法 | 预期 | 实际结果 |
|---|---|---|---|---|
| `InstalledToolIconReaderTest` | 图片读取不能跨版本、越界或经链接读取工具外文件。 | 真实临时目录与生产 manifest 校验器，覆盖两个版本、嵌套图标、缺失/超大文件、无声明、错误身份、错误 bundle、父目录及文件符号链接。 | 只读取当前身份和版本的包内资源；失败返回无图，保留回退。纳入现有最小 JVM 门禁。 | 测试源码已添加；未执行 Kotlin 编译/JVM 测试。 |
| `StaticSvgPolicyTest` | SVG 图标不得引入页面执行、外部资源或递归图形引用。 | 生产静态 SVG 校验器，覆盖形状/文字/本地渐变，以及脚本、实体、外链、use 循环、图片、样式导入、超深和超量元素。 | 静态内容通过，危险或复杂输入在绘制前拒绝；不增加网络或 WebView 通道。纳入最小 JVM 门禁。 | 测试源码已添加；未执行。 |
| `ToolIconLoadingTest` | 真正的 Android 解码、并发缓存和版本失效不能用图片文件名断言替代。 | 系统 PNG 解码、AndroidSVG、白色透明标记，fake catalog 接真实包文件；并发读取同版本、换版本、失效和删除，检查读取不在主线程。 | 256px 等比、无染色、白标可读；同源请求共用缓存，不返回旧版本或已删工具图片。 | Android 测试源码已添加；未运行设备或模拟器。 |
| `LiveNotificationCoordinatorTest` 图标补入 | 异步图片抵达不能重发其他卡或唤回已停止的会话。 | 生产通知控制器与可控 sink，补入一张卡，再停止该会话、清空服务并重放迟到补图。 | 只刷新所属仍发布的会话；不切换承载、不串内容、不复活已停止卡。 | 用例已扩展；未执行 JVM 测试。 |
| `RuntimeLiveNotificationRendererTest` | 标准通知与 HyperOS 必须真正携带所属工具图片，不能仅更换标题。 | 红/蓝位图分别进入生产渲染器与 Notification Parcel，检查 largeIcon 像素、独立图片键及补图序号，保留白字、动作身份和原生增强资格断言。 | 卡片图片独立，HyperOS 引用同图；缺图仍可发通知，原有增强与操作保持。 | Android 测试已扩展；未执行；超级岛显示数量/布局仍需真机。 |
| 静态检查 | 接图和改控件不能改变 API 或宿主安全边界。 | 安全扫描、帮助文档与嵌入源码一致性、模拟桥示例、API 哈希核对、门禁脚本语法和 diff 检查。 | 不改变公开合同、网络、权限、数据库、四个内置包或签名配置。 | 安全扫描、帮助检查（7 章/28 主题/27 代码块）及模拟桥、shell 语法、diff 检查通过；协议哈希核对记录见本轮回执。 |

- 本地安全扫描、帮助检查、门禁脚本语法与差异检查通过；未运行 Gradle、Kotlin 编译、模拟器或真机。新增 JVM 和 Android 测试由 GitHub 编译/执行对应门禁，不把源码检查视为实际 SystemUI 验证。
- 新增固定版本 AndroidSVG 1.4，只作静态图标解码器，第三方声明已登记。图标一致性、双工具隔离、升级换图和停止后无迟到通知仍需真机验证。
