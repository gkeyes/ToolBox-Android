# ToolBox 测试准入与目录

本文件是自动化测试的单一说明目录。任何新增或保留的测试都必须先能回答“为什么必须测、怎样测、什么结果才算通过”。功能尚未实现时，不用占位测试制造虚假信心。

## 测试准入规则

测试至少保护以下一种真实边界：

1. `AGENTS.md` 的安全不变量或安全敏感分支；
2. 原子事务、并发、配额、持久化、恢复或真实 schema 迁移；
3. 用户报告且已定位到代码路径的缺陷；
4. 关键导航、系统 inset、滚动、触控尺寸、字体缩放或辅助功能行为；
5. 发布产物与候选提交之间可复验的证据链。

不为 data class/getter、框架默认行为、静态占位文案、实现细节或已经被更真实层覆盖的同一行为新增测试。同一边界的多组输入优先使用参数化矩阵。截图只证明指定渲染状态，不替代交互、真机 inset、运行时或安全验证。

每次修改测试时同步更新本文件。若测试失去对应风险，应删除或合并，而不是永久保留。

## 当前 JVM 测试

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `MainDestinationContractTest.topLevelDestinationsExposeTheThreeHostTabsInDesignOrder` | 顶级导航顺序是宿主信息架构合同。 | 读取 `MainDestination.entries` 的类型和标签。 | 顺序固定为首页、工具、设置，标签与类型一致。 |
| `MainDestinationContractTest.capabilityRoutesRetainTypedStateInsteadOfWholeScreenModels` | 导入、运行等能力路由必须携带最小类型化状态，避免硬编码整个页面模型。 | 构造不同 capability route 并比较其类型和值。 | 不同能力和不同 ID 不会被错误视为同一路由。 |
| `MainDestinationContractTest.importReviewBackDispatchesThroughSessionCleanupOwner` | 系统返回键曾绕过导入状态机直接弹出路由，留下已审核的私有临时会话。 | 分别把导入审核路由和普通设置路由交给宿主返回分发器，并记录清理回调与默认回调的调用次数。 | 导入审核只调用会话清理所有者；普通页面只调用默认返回，不会交叉触发。 |
| `HostScreenLayoutContractTest.compactAndMediumWidthsSelectTheContentSpacingUsedByTheHost` | 用户已报告排版松散，宽度策略需要一个低成本合同。 | 向 `hostRouteLayoutFor` 输入 360dp 和 840dp。 | 紧凑/中宽判定正确，水平边距为 16dp/28dp，垂直节奏均为 16dp。 |
| `SettingsViewModelTest.auditRetentionPrunesAtTheSelectedCutoffAndReportsPruneFailureAfterPersistence` | 审计留存必须真正清理过期元数据；清理失败不能伪装成设置未保存，快速改选较长留存期也不能让旧的短留存清理在新选择后执行。 | 用固定时钟分别注入成功和失败的 `AuditRepository`，选择 7 天和 90 天后读取设置与接收的 cutoff；再用受控 `HostSettingsRepository` 让首个 7 天 `update` 发出已开始信号后协作式挂起，选择 90 天后才放行首个 update。 | 成功分支保存 7 天并调用 `deleteBefore(now-7d)`；受控重叠改选后仅保存 90 天且只调用 `deleteBefore(now-90d)`；失败分支仍保存 90 天、调用对应 cutoff，并显示“已保存，但清理旧记录失败”。 |

## 当前设备/模拟器交互测试

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `HostNavigationTest.freshProductionCatalogNavigatesToImportReviewAndSettingsWithoutPickerLaunch` | 真实宿主不得注入默认工具、重复管理控件或展示无效设置，且导入审核与真实设置必须可达。 | 启动未写入目录记录的 `MainActivity`，核对首页后依次真实点击当前 Miuix 顶层入口中的工具、导入审核、返回和设置；每次推进 Compose 时钟完成转场并等待目标页面显示，只检查选择入口，不启动外部文件选择器。 | 首页只显示一个可操作的真实空状态，且没有默认工具、本机目录或搜索；Miuix 转场完成后工具页显示独有搜索与 `已安装 · 0`，导入审核显示 `.tbx` 选择入口，返回后设置可操作；设置只显示可用主题与审计留存，不显示静态策略/配额占位。 |
| `HostAdaptiveScrollTest.fixtureLongCatalogScrollsToTheLastStableKeyAndKeepsActionsTouchSafe` | 直接覆盖用户报告的滑动卡涩风险、稳定 key 与 48dp 触控目标。 | 仅向当前 `HomeScreen(HomeScreenState)` 注入 80 项测试夹具，滚动到 `tool-80`，读取末项分组行和标题栏导入操作的语义边界。 | 最后一项可稳定定位且可见，分组行和标题栏导入操作宽高均至少 48dp；夹具不会经过生产目录或被当作预装工具。 |
| `HostAdaptiveScrollTest.freshInstallRemainsReachableAtTwoHundredPercentFontScale` | 保护 200% 字体下内容与底部导航不被裁剪，同时避免 Miuix 导航项再次随字体倍率膨胀。 | 使用 `fontScale=2f` 渲染全新状态，检查空状态、包含系统手势 inset 的底部 surface、Miuix 64dp `IconOnly` 导航项和三个目的地的 TalkBack 名称。 | 内容和底部 surface 可达；每个导航项内容区保持 64dp 且触控边界至少 48dp；图标无裁剪，首页/工具/设置名称均可由语义树读取。 |
| `HostDependenciesViewModelTest.readyDefersNonCoreFactoriesUntilTheirFirstRequiredAccess` | 首页 `Ready` 只可依赖数据库/repository；检查器、包生命周期与运行时服务的构造不得抢占首帧，同时生命周期必须取得创建会话的真实检查器，不能丢失内部安装会话交接能力。 | 给真实 `HostDependenciesViewModel` 注入四项计数工厂与可挂起维护；等待 `Ready` 后模拟首屏读取延迟代理，发送首帧信号，再分别执行检查器/生命周期操作；捕获检查器工厂产物及生命周期工厂入参并比较实例身份。 | 首屏代理读取后四项计数均为 0；首帧维护只构造 Profile 管理器；其余服务仅在首次操作时各构造一次；生命周期收到的检查器与工厂创建的实例相同；维护失败被隔离且同一 `Ready` 实例保持不变。 |

## 当前截图测试

这些截图调用当前的无状态生产 Composable。带 `Fixture` 的条目只使用 `screenshotTest` 夹具，绝不证明生产目录存在预装工具；截图仅保护指定渲染状态，不替代交互或安全验证。

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `CatalogFixtureCompactScreenshot` | 首页紧凑分组需要保护常用/最近工具的信息层级与密度，长工具名也不能被强制压成一行。 | 以 411x891dp 将含长名称固定工具的 `PreviewHostFixtures.catalog` 投影为 `HomeScreenState` 后渲染当前 `HomeScreen`。 | 标题、副标题、长工具名最多两行、分组工具行和标题栏导入操作无裁剪或重叠；仅表示截图夹具。 |
| `FreshCatalogLargeTextScreenshot` | 大字体是已发生的顶部/底部可达性回归场景。 | 以 411x891dp、`fontScale=2f` 渲染空的 `HomeScreenState(isLoaded=true)`，顶部使用可自然增高的 Miuix `TopAppBar`，底部使用 `IconOnly`。 | 顶部标题/副标题、空状态、64dp 导航图标和导入操作完整可见，不出现 fixture 工具；导航项不因字体倍率整体增高。 |
| `ToolManagerFixtureCompactScreenshot` | 工具页需在紧凑手机上展示检索、分组行和当前真实目录字段，并保护带“已固定”标记的长工具名排版。 | 以 411x891dp 渲染含长名称固定工具的 `PreviewHostFixtures.catalog` 到当前 `ToolManagerScreen`。 | 搜索/筛选、`已安装 · N`、最多两行的工具名、版本/大小/签名状态、更多操作和标题栏导入均无裁剪或重叠；仅表示截图夹具。 |
| `ImportReviewFixtureCompactScreenshot` | 导入审核必须在紧凑屏展示检查后的 manifest、风险与逐项权限，主操作也不能随长内容滚出屏幕；200% 字体不能截断顶栏或操作。 | 以 411x891dp、`fontScale=2f` 渲染 `PreviewHostFixtures.importReview` 的无状态审核页、自适应 `TopAppBar` 及固定底部动作区；该对象仅存在于 `screenshotTest`。 | 顶栏标题/副标题、已检查的工具身份和可见审核信息均无裁剪；底部动作在大字体下纵向排列，取消与确认/安装均完整可见；不表示生产目录已经审核、授权或安装任何包。 |
| `PermissionCenterFixtureCompactScreenshot` | 权限中心应展示已观察的授权记录和撤销入口，而不能回退为虚构的全局权限列表。 | 以 411x891dp 渲染一项仅截图夹具的 `PermissionCenterUiState`。 | 工具 ID、授权状态、范围与撤销操作可读且无裁剪；夹具不表示真实授权。 |
| `SettingsCompactScreenshot` | 设置曾展示无效操作，需保护真实主题与审计留存表单在宿主 chrome 和 inset 分配下的紧凑排版。 | 以 411x891dp 通过 `PrimaryScreen(Settings, ...)` 渲染已加载的默认 `SettingsUiState`。 | 顶栏、底栏、系统 inset 与主题、审计留存无重叠，且没有静态策略/配额占位；不将预览当作 DataStore 写入验证。 |

## 当前证据回执自测

五个 case 共用一个脚本和夹具，不再为相同规则增加独立测试文件。

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `scripts/qa/self-test.sh --case valid` | GitHub 下载后的门禁证据必须可从任意目录独立复验。 | 在临时目录生成完整相对路径回执并传入明确候选 SHA。 | 校验器返回 `EVIDENCE_VALID`。 |
| `scripts/qa/self-test.sh --case stale-sha` | 门禁结果必须绑定调用方期望的精确提交，不能复用旧候选回执。 | 对完整回执传入不同的 40 位候选 SHA。 | 校验器以 `EVIDENCE_STALE` 非零退出。 |
| `scripts/qa/self-test.sh --case missing-action-log` | 缺少动作记录时不能给出完整证据结论。 | 回执引用不存在的动作日志。 | 校验器以 `EVIDENCE_INCOMPLETE` 非零退出。 |
| `scripts/qa/self-test.sh --case missing-ui-tree` | 宿主门禁必须明确记录其 UI 证据范围，即使范围是未采集设备树。 | 回执引用不存在的 surface 记录。 | 校验器以 `EVIDENCE_INCOMPLETE` 非零退出。 |
| `scripts/qa/self-test.sh --case missing-cleanup-receipt` | 缺少清理证明时不能复用门禁结果。 | 回执引用不存在的清理记录。 | 校验器以 `EVIDENCE_INCOMPLETE` 非零退出。 |

## Task 7/9 数据与目录生命周期测试

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `CatalogRepositoryTest.installRemainsPendingUntilActiveVersionIsExplicitlyMarkedStable` | 新代码在真实首次启动成功前不能被误标为稳定，否则崩溃恢复会失去可靠回滚点。 | 通过共享内存适配器提交一个安装尝试，先观察 active 版本状态，再显式调用 `markActiveVersionStable`。 | 提交后新版本为 active 且保持 `PENDING`；只有显式确认后才变为 `STABLE`。 |
| `CatalogLifecyclePolicyTest.transactionPolicyAndCompensationLeaveOnlyCommittedCatalogState`（6 行参数矩阵） | source session 的权威重放查询、版本单调性、签名连续性、未签名持久授权、Room 提交点和文件侧失败后的补偿共同构成原子安装目录边界。 | 参数化覆盖 source session 精确/缺失/非法查询、同源幂等/异源重复、降级版本、已签名 ID 降级或换 key、未签名工具的持久 `GRANTED` 与安全授权、commit hook 异常、升级后按预记录 snapshot 补偿及标稳后的过期补偿器。 | 精确 source session 返回提交的 tool/version，缺失返回空，空白或超长值被拒绝；仅首次合法提交可见；未签名持久允许被类型化拒绝而 session 允许/持久拒绝可提交；hook 失败和有效补偿精确恢复 snapshot；版本标稳后旧补偿器返回冲突且目录不变。 |
| `CatalogOrganizationRepositoryTest.organizationWritesValidateOwnershipAndUpdateOnlyHostFields`（4 行参数矩阵） | 置顶、分类和最近打开时间是宿主拥有的目录字段，必须校验输入与工具存在性，且不能借此改写包身份。 | 对共享内存目录参数化执行合法三字段写入、负置顶/时间、空白/超长分类、空白/不存在 tool ID，并从只读 catalog 观察前后状态。 | 合法值仅更新对应宿主字段；非法字段返回 `InvalidInput`、不存在工具返回 `NotFound`，失败时目录不变。 |
| `ToolKvRepositoryTest.quotaAndConcurrentWritesRemainAtomic` | 工具所有权、KV 配额与并发写共同构成资源隔离边界。 | 先登记工具 owner，再参数化覆盖额度内写入、整笔超额和 8 个并发写竞争 5 字节额度。 | 分别得到 2/2 成功且 5 字节、1/2 成功且 4 字节、5/8 成功且 5 字节；失败均为 `QuotaExceeded`，无部分写入。 |
| `HostSettingsRepositoryTest.invalidNumericUpdatesAreRejectedAndCorruptPersistenceDefaults` | 非法保留期、配额或真实损坏的 Preferences 文件都不能污染宿主设置。 | 对内存与 DataStore repository 提交越界变换；再写入截断 protobuf 字节，用生产同款 corruption handler 读取并比较恢复后的文件。 | 越界更新返回 `InvalidInput` 且状态不变；损坏文件读取为安全默认值，并被替换为有效存储。 |
| `PersistenceContractTest.freshV1CatalogAndSettingsPersistAcrossReopenedProductionAdapters` | 内存适配器不能证明增补后的未发布 Room v1 schema、版本级身份/source session、宿主组织字段和 DataStore 文件能跨实例恢复。 | 用 `MigrationTestHelper` 创建 v1，排除 `android_%`、`room_%`、`sqlite_%` 框架内部表后核对七张应用表；通过生产 Room lifecycle 提交 `PENDING`、显式标稳，写入置顶/分类/最近打开字段与全部设置，关闭数据库/scope 后从同一文件重开并按精确 source session 查询提交记录。 | schema 恰有七张应用规划表；active `STABLE` 版本、精确 source session 到 tool/version 的映射、版本身份、宿主组织字段和全部设置精确保留；相近但不相等的 session 查询为空。 |
| `PersistenceContractTest.productionAdaptersEnforceRollbackOwnershipQuotaAndRuntimeParity` | 真实 Room 必须证明事务/授权拒绝零残留、过期补偿器不可回退稳定版本、回滚身份恢复、FK 卸载清理及审计保留，同时继续与内存适配器保持所有权/配额/会话结果一致。 | 在同一合并设备测试中注入 commit 失败并比较 snapshot，拒绝未签名持久允许；提交稳定 v1 和 v2、标稳 v2 后尝试旧补偿并回滚，再执行配额/孤儿/会话矩阵，写入审计并两次删除目录。 | commit/授权拒绝无残留；标稳后旧补偿返回冲突且状态不变；回滚选择最大较低 `STABLE` 版本并恢复旧名称、保留用户分类；删除幂等且级联清除版本/授权/KV/会话，审计仍在；既有 Room/内存错误结果一致。 |
| `PersistenceContractTest.catalogProjectionRetainsToolWhenActiveVersionRowIsMissing` | 目录单查询使用 `LEFT JOIN`；若活动版本行缺失，工具仍必须可见并以空版本字段进入可恢复状态。 | 用真实 Room 提交正常工具，再直接写入一个指向缺失版本的工具记录，读取生产目录投影。 | 正常工具带版本名、字节数和启动状态；缺失版本的工具仍在结果中，四个活动版本字段均为空。 |

## Task 8 `.tbx` 检查层测试

37 个场景合并在五个高价值测试入口中，覆盖检查生命周期、恶意 ZIP 和完整性/签名边界；不为 manifest 普通字段或 getter 增加重复测试。

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `PackageInspectorTest.positionCalculatorInspection` | 真实受支持包必须产出足够的审核事实，并且拒绝结果不能靠伪 fixture 证明。 | 从公共 inspector 流式读取仓库中的仓位计算器示例包，检查完整 manifest、文件/字节统计、权限、CSP/风险和签名状态，再执行两次 discard。 | 得到可安装的不可变 unsigned 检查会话；审核事实准确且无误报风险；首次 discard 成功、第二次为 `NotFound`，无会话残留。 |
| `PackageInspectorTest.cancellationAndSessionRootFailureTerminateWithoutInspectionResidue` | 导入取消、receipt 持久化中断或私有会话目录不可用时不能发布可复用结果、留下半包或挂起任务。 | 取消阻塞读取；在 receipt 原子 rename 后的 session-directory fsync 边界分别注入 `InterruptedIOException` 与普通 I/O 失败；再用非法会话根目录触发创建失败。 | 输入与 receipt-fsync 中断均向上传播且目录为空；fsync 失败返回类型化 `RECEIPT_INVALID`，不会发布 `Inspected`；目录失败返回 `SESSION_IO_FAILED`，均无可安装残留。 |
| `PackageInspectorTest.completedInspectionCanBeClaimedExactlyOnceWithoutReopeningInput` | 检查到安装的交接必须保持同一份已审核字节；并发、进程退出、receipt 损坏或审核后树变更都不能绕过 exact-tree 门禁。 | 用计数输入并发 claim；释放文件锁并由新 inspector 从 durable receipt 恢复同一审查；检查 live public discard、幂等 cleanup、缺 bundle、`.disposing` 恢复；另在审查后添加未记录文件，并分别删除、破坏 receipt 后 claim。 | 输入只打开一次且同一时刻仅一个 owner；恢复的 verified receipt 与原审查相同；新增文件返回 `RECEIPT_TREE_MISMATCH`，缺 receipt 返回 `RECEIPT_MISSING`，损坏 receipt 返回 `RECEIPT_INVALID`；所有失败类型化并终态零残留。 |
| `MaliciousPackageMatrixTest.adversarialArchiveFailsClosedWithoutSessionResidue` | Zip Slip、碰撞、链接、炸弹、嵌套/原生载荷、schema 错误及实际 CRC 篡改属于不同的安全分支。 | 参数化生成 24 个真实二进制 ZIP 变体，其中 CRC 用例仅篡改 STORED payload、保持目录元数据一致，逐个通过公共 inspector。 | 每行都返回对应的类型化拒绝；CRC 用例命中流式 `EXTRACTION_FAILED`/`CRC32`；所有失败均零会话残留。 |
| `PackageInspectorIntegrityMatrixTest.integrityAndRawSignatureMatrixBlocksEveryInvalidPackageBeforeInstall` | 完整文件集和原始 `integrity.json` 的 Ed25519 验证是安装前阻断边界。 | 参数化覆盖有效 unsigned、缺失/多余/篡改/畸形完整性、有效 unknown publisher、签名后原始字节变异、密钥不匹配/不可用及无效签名。 | 只有策略允许的有效 unsigned/unknown 状态可进入审核；所有无效状态均携带 blocker，且不会保留安装会话。 |

## Task 9 包目录生命周期协调测试

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `PackageLifecycleCoordinatorTest.installUpdateRollbackAndUninstallKeepCatalogAuthoritative` | 安装、同一 source session 重放、升级、回滚与卸载必须由 Room 目录和包目录共同收敛，且新版本首次运行前保持 `PENDING`。 | 用真实检查会话安装 v1，待 session/journal 成功清理后删除 `active.json` 并以同一 session 重试，再标稳、安装 v2、回滚到较低稳定版，最后执行 catalog-first 卸载。 | 重放由 Room 权威映射返回 `AlreadyCommitted`，仅一个 v1 且 active 缓存重建；v2 安装后为 active `PENDING`；回滚只指向 v1；卸载后目录不可达，版本与 active 缓存删除。 |
| `PackageLifecycleCoordinatorTest.lifecycleFailureAndCrashCutPointsRecoverWithoutMisreportingOrRepeatedCommits`（9 行切点矩阵） | 版本目录原子发布、Room 安装提交、三版本回滚提交、catalog 删除、claim 后 journal 前取消、已提交 session 重放取消、pre-commit ownership yield 失败、active pointer 写失败和 claim cleanup 失败都不能产生无主目录、卡死 claim、重复提交、二次回滚、吞取消或错误终因。 | 在带 durable owner 的完整版本容器原子发布、安装/回滚/卸载 commit 后注入取消；用 catalog 代理在 claim 成功后的首个 snapshot 抛出取消，然后显式重新 claim/yield 并用同 session 重试；删除 active 缓存后，在 Room 权威重放的缓存修复前注入取消；让无效授权分支 yield 失败；并在 install commit 后分别注入 pointer 写失败与 claim cleanup 失败，再用同一 session 重试或新协调恢复。 | claim 后取消向上传播，同 session 可显式重新 claim/yield 并安装，catalog/最终目录/journal 在重试前零残留；PREPARED journal 能删除本操作版本并重试；post-install 幂等且仅一版；三版本只回滚一次；卸载残留被删除；Room 重放取消向上传播，已提交状态不变且下次重试重建 active；yield 失败类型化为 `RECOVERY_REQUIRED`；pointer/cleanup 故障返回 `CommittedRecoveryPending` 且恢复后 active 与会话收敛。 |
| `PackageLifecycleCoordinatorTest.preexistingVersionCollisionPreservesExistingBytesAndCatalog` | 原子安装遇到既有目标时，清理逻辑绝不能把碰撞目标误当成本次 staging 删除。 | 预置 `versions/1/bundle/sentinel.bin` 后安装同版本真实检查会话，比较原字节并读取目录 repository。 | 返回类型化 `FILE_COLLISION`；sentinel 字节不变；目录仍为空且没有版本记录。 |
| `PackageLifecycleCoordinatorTest.corruptLifecycleJournalsFailClosedAndRemainAvailableForRecovery`（3 行损坏矩阵） | 损坏、重复字段或文件名与内容不匹配的 journal 不能被忽略后继续变更包状态，也不能被自动删除而丢失恢复证据。 | 在私有 journal 目录分别写入畸形内容、重复 key、operationId/文件名不匹配记录，再调用公开 `recover()` 并检查文件与 catalog。 | 每行都返回 `RecoveryLifecycleResult.Pending(RECOVERY_REQUIRED)`；原 journal 保留且 catalog 不变。 |
| `PackageLifecycleCoordinatorTest.claimedSourceTreeMutationIsBlockedBeforeCatalogCommit`（3 行源树矩阵） | receipt claim 后到安装复制前仍可能出现链接、特殊文件或额外载荷，协调器必须再次验证 exact tree，不能只依赖先前审核。 | 取得真实 verified claim 后，分别把预期 JS 换成符号链接/FIFO，或增加未记录文件，再通过协调器安装。 | 每行都返回 `FILE_INTEGRITY_MISMATCH`；无目录提交、无最终版本目录，未审核字节不会进入安装包。 |

## Task 10 SAF 与可恢复审核基础测试

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `PackageInspectorTest.durableInspectionRecoveryIsBoundedExclusiveAndCleansUnlockedResidue` | SAF 选择后的审核状态必须以私有 durable receipt/session 为唯一权威；进程重启、并发检查、中断和损坏残留不能重开外部 URI、暴露半成品、遗失 claim 或造成无界启动扫描。 | 只打开一次输入生成审核会话，模拟 claim 已原子移动到 `.claimed` 但 lifecycle journal 尚未发布即进程死亡；先让组合恢复入口返回 lifecycle `Pending`，再让它在 lifecycle `Recovered` 后冷恢复；随后执行显式 resume 和 bounded discovery，注入 recovery scan 中断、receipt fsync busy、损坏/缺失 receipt 和 33 个未完成 UUID 目录。 | lifecycle `Pending` 时组合入口不扫描且 `.claimed` 原样保留；`Recovered` 后无 journal 的 unlocked claim 被原子退回 pending 并完整复验为同一审核，输入仍只打开一次；scan 中断被标准化为协程取消并向上传播，且锁释放后会话仍可恢复；检查中的锁返回 busy；未锁定坏残留被清理并给出类型化 issue；每次最多处理 32 个候选并报告 truncated，最终无残留。 |
| `ToolBoxOpenDocumentTest.selectedSourceUsesSafeEphemeralNameAndCanOnlyBeOpenedOnce` | 真实 SAF 适配器不能在取消时创建会话、接受错误扩展名或 Unicode 双向伪装、泄露 provider 路径字符，亦不能把同一 `content://` 源重复打开造成审核字节漂移。 | 在不启动系统 picker 的最低可信适配层输入取消、`.txt`、BMP 与补充平面的 Unicode FORMAT/bidi 控制码点、以及含路径/控制字符的 `.tbx` 名称，并对选中源调用两次 `openStream`，同时检查 OpenDocument MIME 常量。 | 取消不创建 input，错误扩展名和任意平面 bidi/FORMAT 文件名显式拒绝；显示名按 code point 安全化且不截断代理对；有效源只打开一次且第二次失败；只声明 `.tbx`/ZIP/二进制 MIME，不持久化 URI。 |
| `ImportReviewViewModelTest.reviewStateMachineKeepsInspectionRecoveryGrantInstallAndCancelBoundaries` | 导入审核页必须只安装已审核的私有会话，不能伪造未声明权限、绕过阻断项、在清理或协作者异常时永久卡住、复用已消费的安装成功页面，或在进程重建后自行拼接生命周期与 receipt 恢复顺序。 | 用同一状态机测试依次驱动选择器拒绝/取消与互斥、可安装审核、安装结果退出并重开、阻断审核、两次取消清理、已知会话恢复、冷恢复及 inspector 抛出非取消异常；检查默认拒绝、必需权限确认、未知权限、传给 lifecycle 的 session/grants、退出事件消费前后的反馈状态，以及异常后的可重试阶段。 | 选择器和检查阶段拒绝并发操作；只有完整且确认的已声明 `SESSION` 计划可安装；安装只收到 sessionId 与派生授权；退出事件未消费时保留安装反馈，消费后回到可重新选择文件的 `IDLE`；阻断项保持禁用；清理失败留在审核页且可重试，成功才请求退出；冷恢复只委托 startup recovery；非取消异常显示可操作错误并恢复安全阶段，`CancellationException` 继续传播。 |

## Task 11 权限中心展示与撤销测试

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `PermissionCenterViewModelTest.revokeRemovesObservedGrantImmediatelyAndRefusesUnknownPermissionMutation` | 权限中心只能管理安装已声明的授权记录；撤销必须及时反映，未知权限绝不能被页面伪造或写入。 | 以受控 `PermissionGrantRepository` 流提供一项已安装授权，调用撤销后检查状态流与仓库调用；再请求撤销不存在的权限。 | 已存在记录立即从状态流移除并只发生一次 repository 撤销；未知权限不触发 repository 写入，返回 `NotDeclared` 类型反馈。 |
| `CatalogViewModelTest.catalogFlowDrivesRealItemsFiltersOrganizationAndRecoverableUninstall` | 首页与工具管理不得展示默认工具、在重组时重复筛选分组，或在卸载结果返回时本地伪删；搜索去抖、首页分组、组织字段、具名卸载确认、异步运行数据清理与包目录恢复必须以真实目录流和生命周期类型结果为准。 | 先订阅空的内存目录，再提交两个带真实版本/字节数的工具并观察 ViewModel `homeState`；验证搜索文本立即发布但可见列表在 120ms 后才更新、清空立即恢复，再覆盖分类筛选与置顶；取消具名卸载不清理运行数据，确认后以可控 suspend cleaner 证明清理完成前不会调用包生命周期，随后覆盖恢复、运行中 profile、清理失败与 WebView provider 能力不足。 | 首次空查询不延迟目录首批内容；首页只投影常用/最近工具，不重复未打开的完整目录；非空搜索在 119ms 内不重建列表、120ms 后只保留匹配项，清空立即恢复；分类和置顶准确；取消不触发清理；清理完成后只调用一次包卸载并显示类型化反馈；失败分支保持目录与包不变，恢复后仅通过目录 Flow 移除目标工具。 |
| `CatalogViewModelTest.catalogListUsesSingleProjectionWithoutOpeningPerToolVersionFlows` | 目录按工具逐个订阅版本或在初始空搜索上等待 debounce 会放大首屏与滚动成本，直接对应用户报告的滑动卡涩。 | 以计数代理包装内存目录，创建 ViewModel 后提交两个工具，只执行当前调度队列而不推进虚拟时间，再读取目录状态、单一 projection 订阅次数与逐工具版本订阅次数。 | 两项及唯一存储的可见列表立即进入状态；`observeCatalogProjection()` 恰好订阅 1 次，`observeVersions(toolId)` 调用保持 0。 |
| `RuntimeViewModelTest.pageLoadAndRendererLossRemainPendingUntilUserConfirmsReadyVersion` | WebView 的页面提交或渲染进程退出不能替代用户确认而把首次运行的新版本误标为稳定；活动版本切换必须先请求旧 WebView 注销确认，provider/profile 创建异常也不能穿透 Compose 导致崩溃。 | 用真实私有 v1/v2 bundle、内存目录和记录等待参数的 creation permit provider 创建 `PENDING` 运行时；v1 加载后切换 active v2，验证新 permit 要求等待旧 runtime release；再驱动 renderer 退出、重试、确认和类型化 WebView 创建失败。 | v2 permit 明确携带等待旧实例释放的请求；加载与渲染器退出后 active v2 仍为 `PENDING`，只有确认才变为 `STABLE`；创建失败进入 `RUNTIME_WEBVIEW_CREATION_FAILED` 可见错误而非抛出。 |

## Task 12 硬化无桥运行时测试

| 测试 | 测试理由 | 测试方法 | 预期结果 |
|---|---|---|---|
| `ToolRuntimeSecurityBoundaryTest.exactOriginCanonicalBundleEntryAndOfflinePolicyFailClosed` | 安装后的纯 HTML/CSS/JS 只有在每工具隔离来源、活动目录和入口仍与目录事实一致时才能执行；SDK 声明缺失期间远程请求、危险来源与原生桥必须继续关闭。 | 在一个参数聚合测试中比较两个工具的 SHA-256/Base32 Origin 与 profile，检查 HTTP、显式端口、混淆子域、`file://`、`content://`、`intent://`、`javascript:`、localhost/loopback；创建真实私有 `miniapps/<id>/versions/<code>/bundle`，用严格安装期 manifest 解析边界准备入口，再依次替换版本身份、错误 locator、遍历入口和符号链接入口，并核对 strict/compat CSP。 | 同一工具 Origin/profile 稳定且不同工具隔离；只有无显式端口的 exact HTTPS Origin 可用，危险 scheme、本地服务和混淆来源均被拒绝；规范私有目录成功准备，身份、locator、遍历和链接不一致分别类型化拒绝；两种 CSP 均关闭连接、frame、worker 和 `unsafe-eval`，仅 compat 放开内联脚本。 |
| `HardenedRuntimeWebViewInstrumentationTest.realWebViewEnforcesOfflineBoundaryAndContainsRendererLoss` | JVM 策略测试不能证明真实 Android WebView 已应用安全设置，也不能证明 provider 能力变化、模式迁移、后台孤立资料清理或默认 Profile 污染时不会泄漏浏览状态；permit 交接、清理证明、取消和 renderer 丢失仍是同一真实生命周期边界。 | 在同一测试方法中输出 provider capability flags，覆盖 dedicated/stateless 迁移、真实 WebView exact HTTPS 页面、持久存储封锁、活动租约、下一版本交接、损坏证明及孤立冷回收；孤立 profile 的物理删除回调内尝试同工具预约，清理后再申请许可。 | 模式与 capability 一致，危险入口和持久状态失败关闭；孤立 profile 删除期间同工具预约被清理租约拒绝，证明删除后才释放且新许可可获得；取消不执行包动作，renderer-gone 不迟报成功。 |

## 非测试构建门禁

`verifySecurityInvariants`、`assembleDebug`/AndroidTest APK、`lintDebug` 和截图校验是候选构建门禁，不计作功能测试。它们分别预期：禁用 API/权限扫描为零违规且 GitHub Actions 全部锁定到 40 位不可变提交、APK 可构建、Lint 无阻断问题、已登记截图与人工批准基线一致。它们不能替代上面的交互测试或未来包/运行时安全测试。
