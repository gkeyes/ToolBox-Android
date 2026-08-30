# Codex 开发任务书：ToolBox Android

开始前完整读取并遵守 `AGENTS.md`、`docs/ToolBox_Android_技术方案.md`、
`schema/manifest.schema.json`、`sdk/toolbox-api.d.ts`、
`references/component_mapping.md` 和 `TESTING.md`。

## 产品目标

ToolBox 是 Android 13+ 的轻量本地 HTML/CSS/JavaScript 小工具宿主。用户可导入
`.tbx`、立即使用、在工具详情中开关权限、让有限的原生任务在后台执行，并能完整删除
工具。宿主 UI 使用 Compose + Miuix；小工具内部 UI 继续是 HTML。

导入的可见流程只有：选择 `.tbx` → 内部检查 → 安装成功或可操作的失败提示。不得展示
审核会话、风险分级、签名/发布者徽标、审计日志、恢复审核或“继续审核”操作。安全检查
仍必须在内部完成，且任何无效包不得留下文件或数据库残留。

## 固定产品决策

- 当前仍是开发期，清空现有工具、授权和设置，按全新数据库建立最终结构；不写迁移、
  兼容读取、回退兼容或旧字段保留代码。Room 保持最终 `version = 1`，不使用
  `Migration`、`AutoMigration`、`DataMigration` 或 `fallbackToDestructiveMigration`。
- 删除审计日志、发布者信任库、安装审核会话和虚假设置。DataStore 只保存主题和后台
  总开关；配额是内部常量。
- 未签名包可正常安装。`integrity.json` 存在时必须验证；`signature.json` 存在时验证
  包内 Ed25519 公钥和签名，验证失败阻止安装。不要增加发布者持久化、信任策略或签名 UI。
- 版本更新只接受更高版本且原子替换；普通 KV 保留，旧版本的安全存储、临时句柄、后台
  任务和结果清理。首版没有回滚、导出、迁移或兼容 UI。
- 工具权限是按工具的虚拟授权，不等同于 Android 按应用授权。生效需要同时满足：manifest
  声明、工具开关、宿主系统权限、当前手势/前台场景、速率和配额。关闭后立即生效。
- 默认开启 `storage`、`storage.secure`、`device.basic`、`clipboard.write`、`haptics`；
  其他能力默认关闭。需要 Android 权限的开关在开启时请求系统授权，拒绝时不保存工具授权。
- 后台只运行宿主委派的 WorkManager 操作，不启动后台 WebView、不执行任意后台 JS、不使用
  常驻前台服务。首版仅有受 allowlist 约束的 HTTPS `httpGet` 和命名空间通知。
- 固定 Miuix `0.9.4-rc01`，经 ToolBox 适配层使用。运行页不得有宿主底栏或技术状态条；
  WebView 占满其余空间，顶部仅约 48dp 的返回、标题和刷新/更多。

## 不可协商的安全要求

`AGENTS.md` 的所有安全不变量原样适用，尤其是：禁止 `addJavascriptInterface`、`file://`
和 localhost；唯一 exact HTTPS origin；`addWebMessageListener` 的 origin/frame/nonce/
manifest/grant/system permission/gesture/rate/quota 校验；禁止 WebView 文件/内容访问、混合
内容、任意导航和弹窗；网络只经原生 HTTPS 代理并执行域名、重定向、DNS/IP 私网 SSRF
检查；安装事务化并抵御 Zip Slip、Zip Bomb、路径碰撞、链接、嵌套压缩包和动态代码。

不要申请 `MANAGE_EXTERNAL_STORAGE`、`QUERY_ALL_PACKAGES`、无障碍、短信、联系人或 root
权限。不得记录剪贴板、文件、secure storage 或 HTTP body 内容。

## 分阶段执行

不要一次性生成全部模块；上一阶段编译、最小相关测试和手动界面路径通过后，才进入下一阶段。

1. **清洁基线与协议**：同步文档；删除审计/发布者/审核/兼容代码；建立 `tool-api` 的
   canonical API v1 机器可读来源，并生成或校验 Kotlin capability 描述、JS shim 方法表、
   `sdk/toolbox-api.d.ts` 与 manifest 权限枚举；CI 检查生成物漂移。
2. **导入、目录和权限**：SAF 导入、内部检查、一次安装、真实工具详情/删除/权限开关；
   无效包零残留，卸载清理所有工具状态。
3. **安全运行时和基础 API**：AssetLoader、exact origin、硬化导航、消息桥和
   `ready`、`ui.toast`、`crypto.sha256`、`storage`、`storage.secure`、`device.basic`、
   `haptics`、`clipboard.write`。
4. **后台、网络和通知**：WorkManager + 受控 HTTPS 代理；实现任务 API、任务持久化、
   取消、版本/卸载清理和后台总开关。
5. **剩余 App 式能力与帮助**：实现 `clipboard.read`、分享、SAF 文件、快捷方式、
   系统相机拍照和前台单次定位；设置页加入 Developer Help；交付三个完整示例。
6. **交付门**：最小安全/持久化/导入/桥/后台/界面验证，真机组合旅程，GitHub Actions
   在编译和模拟器门均通过后才上传 APK、三个 `.tbx` 和 `SHA256SUMS.txt`。

## API 与后台合同

- API v1 的已支持能力才显示开关或写入工具 manifest；没有真实 handler 的能力不得伪装为可用。
- `ready` 返回 host/API/tool generation；`ui.toast` 限制长度；`crypto.sha256` 对受限输入
  求摘要；`device.basic` 不返回标识符；storage 按工具和 generation 隔离。
- 敏感调用必须有宿主记录的真实 WebView 触摸证明，不能信任 JS 自报的“用户已点击”。
- 后台 API 为 `enqueue`、`schedulePeriodic`、`list`、`getResult`、`cancel`。任务状态为
  `QUEUED | RUNNING | COMPLETED | CANCELLED`；最近一次结果为 `SUCCEEDED | FAILED |
  CANCELLED`。周期任务完成一次后回到 `QUEUED`。
- 2xx 成功；4xx 不自动重试；网络、超时、5xx 最多指数退避三次；取消、关闭权限、版本替换
  或卸载不重试。每工具最多 8 个活动任务、4 个周期任务，最短周期 15 分钟。

## UI、帮助和范例

- 主导航只有“工具”和“设置”；导入在工具页的顶部操作区。底栏视觉内容约 56dp，系统
  手势 inset 仅消费一次；字体放大不整体放大容器。
- 权限页使用完整行可点击的 Miuix switch setting；设置只显示主题、后台总开关和
  Developer Help 等真实功能。
- Developer Help 是离线原生页面，从 canonical API 和实际示例生成：目录、manifest、权限、
  JS API、打包、导入、后台限制和错误码。
- 三个必须真实可用的示例：仓位计算器（存储/复制/触觉）、快速笔记（保存/编辑/复制）、
  后台任务演示（固定 `api.github.com/repos/gkeyes/ToolBox-Android` 的受控请求和通知）。
  每个例子交付源码、manifest、完整性清单、可重复打包脚本和 `.tbx`。

## 验证与汇报

每个保留或新增自动化测试必须在 `TESTING.md` 中记录：测试理由、测试方法、预期结果。
不保留删除功能的字符串测试，也不为同一边界叠加重复测试。至少覆盖：全新数据基线、有效/
损坏包导入与删除、权限/RPC 一致性、后台与 SSRF、Miuix 真机组合流程。

每阶段汇报：改动文件、运行方式、每项测试的理由/方法/预期/实际结果、证据路径和剩余风险。
最终候选 APK 必须在卸载 `io.toolbox.host` 后干净安装；不执行覆盖升级，也不保留现有工具数据。
