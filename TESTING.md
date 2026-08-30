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
| 导入 | `DirectPackageLifecycleTest`；`ToolBoxOpenDocumentTest` | 核心导入必须只有成功或失败，且失败不能残留；文件选择输入必须是一次性、无歧义的 `.tbx` 来源。 | 用有效包完成安装、更新与卸载；以 Zip Slip、嵌套压缩包、完整性损坏和错误包签名构成拒绝矩阵，并验证取消、非法/双向文件名和一次性输入。 | 有效包原子可见；上述无效包没有 DB、目录或临时文件残留；非法来源被拒绝且输入流不能重复打开。 |
| 更新/删除 | `CatalogAndStorageRepositoryTest` | 更新和卸载必须清理完整状态，不留下后台或敏感数据。 | 安装后写普通 KV、grant、后台 task/result，再提交更高版本并删除目录。 | 更新仅保留普通 KV 且以新 manifest grants 替换旧 grant；任务/结果消失；删除清理全部数据库工具状态。 |
| 权限与运行时 API | `PermissionCenterViewModelTest`；`RuntimeRpcDispatcherTest` | 防止“有开关没能力”、公开未实现参数或跳过声明、授权、系统权限、手势和配额层；同时证明 M1/M2/M3 处理器不是伪成功。 | 对已支持 capability 用 production dispatcher 分别关闭 declaration、grant、system、gesture、quota 条件；验证网络仅接收 GET，后台仅接收已公开的 network constraint，并验证权限列表写入、未知权限拒绝、文件一次性令牌和位置错误映射。 | 全部条件满足时得到真实 handler 结果；POST、指定运行时间和未公开任务约束在调用 handler 前返回稳定错误；任一授权层缺失稳定拒绝；文件令牌只能消费一次。 |
| Bridge | `ToolRuntimeSecurityBoundaryTest`；`HardenedRuntimeWebViewInstrumentationTest` | 保护导入内容的来源与会话边界，同时允许高负载计算离开页面主线程而不放开远程代码或 ServiceWorker。 | JVM 检查 CSP 仅含 `worker-src 'self'` 且不含 wildcard/blob；API 35 WebView 测试 exact origin、main frame、nonce、iframe、导航后的旧 nonce、取消和 ServiceWorker 阻断。 | 仅包内 exact-origin 静态 Worker 可加载；远程/blob/data Worker 与 ServiceWorker 被阻断；仅当前主 frame/来源/会话可调用 ToolBox，其他请求被拒绝且无副作用。 |
| M3 API | `RuntimeRpcDispatcherTest.m3FileTokensAndLocationFailClosedThroughTypedHandlers` | 文件和定位会触及用户数据及系统权限，必须保证会话令牌、消息配额、能力授权和稳定错误不会被绕过。 | 通过 production dispatcher 验证文件令牌继承创建它的 `files.open`、`files.save` 或 `camera` 能力、只能消费一次；以含引号的非法 ID 和 128 字节合法 ID 验证令牌读取前的 ID 限制及按实际 ID/Base64/JSON 计算的响应预算；直接验证 bridge 对 UTF-8 编码后超额的任意响应替换为 quota failure；验证位置只在通用层要求粗略权限，并将原生位置不可用映射为 typed `NOT_FOUND`。 | 非 shim ID 在消费令牌前失败；边界合法 ID 的完整响应不超过会话配额；首次读取只返回会话配额内的内容，重复读取失败，其他超额响应由 bridge 返回 `QUOTA_EXCEEDED`；粗略授权可进入 handler，精确请求由 handler 再检查 fine；原生空结果不会被取消通道吞掉。 |
| M3 生命周期 | `M3BrokerLifecycleInstrumentedTest` | Dispatcher 假对象不能证明宿主实际使用的临时句柄表和相机缓存会在运行会话结束时清理，也不能证明 Android `Location` 空回调采用 typed failure。 | 在 Android instrumentation 中使用生产 `RuntimeFileSessionResources` 注册真实缓存文件、content URI 与 camera capability，调用会话 `close`；同时调用生产位置回调适配器的 null 与非 null 分支。 | 关闭后令牌、句柄和临时文件计数均为零且真实缓存文件消失；null 结果为非取消型 `RuntimeHandlerException(NOT_FOUND)`，有效 `Location` 成功返回。 |
| 后台 | `BackgroundTaskRepositoryTest`；`BackgroundTaskPolicyTest`；`manual-xiaomi-toolbox-v1` 的后台步骤 | 证明任务状态、三次指数退避、进程中断恢复和七天结果保留遵守受控 native-work 合同，并在真实系统验证 WorkManager 不依赖后台 WebView。 | JVM 合同测试写入 RUNNING/完成结果后验证 RUNNING→QUEUED 恢复和严格早于截止时间的结果清理；策略测试验证第 1–3 次重试、预算耗尽后的周期任务下次运行时间及仅无活跃 Work 的 RUNNING 恢复；候选包真机中创建、查看、取消 HTTP/通知任务，并离开工具后观察结果。 | 三次重试后周期任务回到 QUEUED 并等待下一周期；孤儿 RUNNING 可重新调度；超过七天的结果被清理、边界结果保留；取消无孤儿任务或后台通知；离开工具后不保留 WebView。 |
| 网络 | `NetworkBoundaryTest`；`ToolNetworkProxyTest` | 阻止端点策略和地址过滤退化为可绕过的网络入口，并覆盖生产代理状态机的 DNS、重定向和响应上限路径。 | `NetworkBoundaryTest` 在 JVM 直接验证 allowlist、HTTPS、443 端口、IP literal、重定向开关及私网/保留 IPv4、IPv6、IPv4-mapped IPv6 和 NAT64 映射地址；`ToolNetworkProxyTest` 用生产 `httpGet` 路径验证私网 DNS 在连接前拒绝，并仅以确定性响应传输驱动重定向第二跳重验和超限响应读取。 | 只有域名 allowlist 中的 HTTPS:443 端点可继续；重定向默认拒绝且已启用时每一跳仍重新校验；私网 DNS 不建立连接；超限响应返回 `RESULT_TOO_LARGE`；所有测试的本地、保留和映射地址均被阻断。 |
| Miuix 宿主 | `HostNavigationTest`；`HostAdaptiveScrollTest`；`HostScreenLayoutContractTest` | 保护紧凑布局、有效按钮、稳定滚动和单次 inset。 | API 35 Compose 流程以真实内置 `.tbx` 安装器安装三个范例，进入“后台任务演示”详情，切换已声明的网络能力，打开运行壳、查看后台任务页并从详情菜单删除该工具；普通和 200% 字体；JVM 层验证紧凑/中等宽度间距。 | 范例走生产安装路径后可管理；权限开关真实持久化为开启状态；运行壳和后台页可达；删除后目录中仅保留另两个范例；无双 inset、裁剪或固定无效文本。 |
| Miuix 静态视觉 | `HostScreenPreviewScreenshots` | 保护已确认的紧凑 grouped-list、浅深主题、2 倍字体和内容优先运行壳，防止旧审核/签名界面重新进入基线。 | 使用 Android Compose Preview Screenshot 的真实生产 Composable 渲染浅色工具列表、深色工具列表、2 倍字体空状态、工具详情、权限、设置和运行壳，并与仓库基线逐像素比较；不启动模拟器。 | 七个状态均可稳定渲染；无旧审核/风险/发布者内容；2 倍字体时主导航转为保留 TalkBack 标签的图标模式，页面无文字裁剪；运行壳无宿主底栏。 |
| 真机组合 | `manual-xiaomi-toolbox-v1` | 模拟器不能证明 SAF、相机、Sharesheet、快捷方式、通知和 HyperOS 系统栏；同一工具二次打开、工具运行页首帧和主页面切换还依赖真实导航生命周期与帧调度。 | 干净安装后导入三例，依次测试权限、复制、文件、相机、定位、后台和删除；在同一进程打开、返回并再次打开同一工具；分别重置并记录工具原生壳进入、工具返回、设置→工具权限和工具权限→设置的 `gfxinfo`，以 120Hz 的 8.33ms 帧预算检查 p95/p99；确认原生壳转场结束后才出现 `webView.create`，并连续往返“工具/设置”八次。 | 实际系统 UI 可完成；同一工具可重复打开且不复用已消费的 WebView 创建许可；二级页只移动顶层并保留底页，返回不触发被揭示页整页重测；工具进入先完成原生壳转场再创建无变形 WebView，网页初始化不会阻塞可见转场；四条路径均无可见停顿或残影。 |
| 范例打包 | `scripts/package-examples.sh` 可重复性检查 | 三个范例是可独立导入的交付物，未变化的源码必须生成相同的 `.tbx` 字节，才能让 SHA-256 回执有意义。 | 对同一工作树连续运行两次打包脚本，逐个比较三个 `.tbx` 的 SHA-256；交付 Actions 在上传前重复这一检查。 | 三个文件的哈希完全相同，ZIP 内容可通过脚本内校验并进入 APK assets 与交付目录。 |
| CI 交付 | `artifact-gate-receipt` | 防止静态门禁未过就发布 APK，也防止把未执行的设备测试写成通过。 | Actions 按 static verify → artifact 的 `needs` 关系运行，检查 APK、三个 `.tbx`、SHA256、提交回执及 `DEVICE_TEST_RESULT=NOT_RUN_USER_OWNED`。 | 静态门禁失败时没有 APK；成功产物可按 SHA256 复验，且回执明确真机验证由用户执行。 |

## 执行原则

- 每次改动只运行受影响的最低忠实层测试，再运行必要编译；不要重复运行无输入变化的全套测试。
- 涉及 WebView、Android permission、SAF、通知或 WorkManager 的改动，除 JVM 测试外必须有相应
  instrumentation 或真实系统表面证据；前者不能替代后者。
- 失败测试不得通过删除断言、降低安全检查或改成静态 UI 来“修绿”；修复后重跑完整相关场景。
- 每阶段报告必须逐项给出实际命令、理由、方法、预期、实际结果和证据路径；未运行即明确写未运行。
