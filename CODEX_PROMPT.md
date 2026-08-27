# Codex 开发任务书：ToolBox Android

请在一个新的 Android Studio 项目中实现 ToolBox。开始前必须完整读取并遵守：

- `AGENTS.md`
- `docs/ToolBox_Android_技术方案.md`
- `design/host_ui_light.png`
- `schema/manifest.schema.json`
- `sdk/toolbox-api.d.ts`
- `references/component_mapping.md`

## 目标

实现一个 Android 13+ 的原生宿主 App。用户可导入 `.tbx`（ZIP）形式的 HTML/CSS/JS 小工具，经结构、安全和权限审核后安装；工具在独立 HTTPS Origin、可选独立 WebView Profile、严格 CSP 和来源可校验的消息桥中运行，并通过 ToolBox JS API 请求原生能力。

## 技术约束

- Kotlin、JDK 21、Compose、Miuix、Room、DataStore、Coroutines、kotlinx.serialization、AndroidX WebKit 1.17.0。
- `compileSdk 37`、`minSdk 33`；targetSdk 选择当前稳定且验证通过的版本。
- Miuix 作为主 UI；HyperX Compose 仅固定 commit 源码方式按需引入，并包装在 ToolBox 适配层。
- 禁止 `addJavascriptInterface`、`file://`、localhost/Ktor server、`MANAGE_EXTERNAL_STORAGE`、任意外部导航和页面直接联网。
- 必须通过 `WebViewAssetLoader` 为每工具建立唯一 exact HTTPS origin。
- 必须通过 `WebViewCompat.addWebMessageListener` 建立桥，校验 sourceOrigin、main frame、nonce、声明、授权、系统权限、用户手势、限流和配额。

## 执行方式

不要一次性生成全部模块。按以下纵向切片逐步完成；每阶段编译、测试并提交变更说明。

- `docs/ToolBox_Android_技术方案.md` 是核心架构基线，计划书用于执行拆分。只有在计划与技术方案、安全不变量或当前平台事实冲突时才允许调整，并必须先记录证据、影响和替代方案；安全相关调整写 ADR。
- 测试采用最小充分集。新增或保留测试必须保护安全不变量、事务/并发/持久化边界、已报告缺陷、真实迁移或关键可访问性行为之一，且不得与现有测试重复。
- 每个自动化测试都必须在 `TESTING.md` 中记录测试理由、测试方法和预期结果；相同边界的输入使用参数化矩阵，不测试数据类 getter、框架默认行为或静态占位文案。

### 阶段 1：工程与宿主 UI

- 建立 `:app :core-ui :core-data :tool-package :tool-runtime :tool-api`。
- 实现 Miuix Theme、ToolBox UI 适配层和类型安全导航。
- 按效果图实现首页、工具管理、导入审核、权限中心、设置和运行外壳静态页面。
- 添加 Compose Preview 和关键截图测试。

### 阶段 2：包检查与原子安装

- SAF 导入 `.tbx` 到私有临时目录。
- 防御 Zip Slip、Zip Bomb、路径规范化/大小写冲突、禁止类型、超限文件。
- 解析并严格校验 manifest；返回结构化 `ImportInspection`。
- 以原始 `integrity.json` 字节验证完整性与 Ed25519 签名，显示签名、结构、权限、域名和风险；无效签名在安装前阻断。
- 原子安装到版本目录，Room 事务登记；失败彻底回滚。
- 验证 `examples/position-calculator.tbx` 可安装与卸载。

### 阶段 3：安全运行时与最小 API

- AssetLoader + 唯一 origin + feature 可用时 per-tool Profile。
- 自定义 PathHandler 添加 strict/compat CSP 与安全响应头。
- 禁止外部资源、外部导航、file/content/intent/javascript scheme。
- 处理 renderer process gone。
- 实现 RPC、JS shim 和 `ready/ui.toast/storage/haptics`。
- 与 `sdk/toolbox-api.d.ts` 保持一致。

### 阶段 4：权限、安全审计与实用 API

- 实现三层授权、风险分级、一次/会话/持久/每次询问/拒绝/封禁。
- 实现 clipboard.write/read、share、files、network、device、notifications、shortcuts、crypto。
- FileToken 必须短期、不可伪造、不暴露路径。
- 网络代理必须 HTTPS、域名 allowlist、DNS/IP/重定向重复校验、SSRF 阻断、响应大小与并发限制。
- 审计不记录敏感正文。

### 阶段 5：发布者信任、版本与导出

- 在阶段 2 已完成安装前完整性/Ed25519 阻断的基础上，实现可信发布者库与无签名工具的严格策略。
- 升级保留上一版，首次启动失败可回滚。
- 通过 SAF 导出代码包。
- 卸载清理代码、KV、secureStorage key、Profile、权限和审计策略关联。

## 必须覆盖的最小安全测试集

以下边界必须有负载有效的最小测试，但不得为了数量在多个测试层重复覆盖；每项仍须遵守 `TESTING.md` 的理由、方法、预期结果登记规则。

- manifest 边界；Zip Slip/Zip Bomb/路径碰撞；integrity/signature。
- iframe、错误 origin、导航后桥残留、CSP 远程资源、危险 scheme。
- 权限撤销即时生效；未声明能力不可申请。
- 网络私网、IPv6、本地地址、重定向和 DNS rebinding 场景。
- 更新失败回滚；renderer crash 恢复；工具 A/B 存储隔离；卸载清理。
- 导入 → 审核 → 安装 → 运行 → API → 导出完整 UI 流程。

## 完成条件

只有 `docs/ToolBox_Android_技术方案.md` 第 17 节全部验收项满足，才算 P0 完成。任何安全不变量需要变更时，先创建 ADR，说明威胁、替代方案、影响和测试，不得直接放宽。
