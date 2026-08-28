# ToolBox Android

ToolBox 是 Android 13+ 的原生小工具宿主。当前实现已覆盖阶段 1 宿主、阶段 2 的真实 `.tbx` 检查与目录生命周期，以及阶段 3 的硬化无桥 WebView 运行器；它还不是 `CODEX_PROMPT.md` 定义的完整 P0 产品。

## 当前可用范围

- Gradle 模块：`:app`、`:core-ui`、`:core-data`、`:tool-package`、`:tool-runtime`、`:tool-api`
- `core-ui` 在 ToolBox 组件后封装 Miuix，并统一系统栏、刘海、导航栏与 IME inset 归属
- Navigation 3 类型安全路由与紧凑、自适应的首页、工具管理、详情、导入审核、权限中心、设置和运行页
- 通过 SAF 选择真实 `.tbx`，检查 ZIP、manifest、风险、完整性和签名，再从私有审核会话原子安装
- 真实目录驱动的搜索、分类、置顶、详情、权限撤销、具名卸载与恢复；新安装不会伪造默认工具
- 主题与审计留存设置真实持久化；尚未接入的设置项明确标记为不可用
- 每工具唯一 exact HTTPS Origin、`WebViewAssetLoader`、CSP、危险导航阻断、renderer 恢复；provider 同时支持独立 Profile 与整 Profile 清理时使用专用 Profile，否则使用禁用浏览持久化的 Origin-only 无状态模式
- CI 中的安全不变量扫描、Debug 构建、测试、Lint 与截图验证
- 测试准入与逐项理由/方法/预期结果见 `TESTING.md`

当前运行器只执行离线 HTML/CSS/JavaScript，不注入原生 JS 桥。依赖 `ToolBox.ready/storage/ui/haptics/clipboard` 的能力会保持不可用，不会用占位实现伪造成功。

## 本地运行

要求 JDK 21 与 Android SDK 37。构建及验证：

```bash
./gradlew --no-daemon \
  verifySecurityInvariants \
  assembleDebug \
  :app:assembleDebugAndroidTest \
  testDebugUnitTest \
  lintDebug \
  validateDebugScreenshotTest
```

连接 Android 13+ 设备或模拟器后安装：

```bash
./gradlew :app:installDebug
```

连接设备后执行本次变更相关的宿主、持久化与真实 WebView instrumentation 测试：

```bash
./gradlew \
  :app:connectedDebugAndroidTest \
  :core-data:connectedDebugAndroidTest \
  :tool-runtime:connectedDebugAndroidTest
```

更新截图基准只应在人工检查渲染差异后执行：

```bash
./gradlew updateDebugScreenshotTest
```

## 目录

```text
app/                              宿主入口、路由、真实功能页面与测试
core-ui/                          ToolBox/Miuix 适配层和主题
core-data/                        Room/DataStore 目录、授权、工具 KV、审计与设置
tool-package/                     `.tbx` 检查、审核会话和原子目录生命周期
tool-runtime/                     exact-origin AssetLoader 与硬化无桥 WebView
tool-api/                         等待权威 TypeScript 声明恢复的原生 API 模块
docs/ToolBox_Android_技术方案.md   产品、架构、安全与验收方案
design/                           明亮模式设计板与页面参考图
schema/manifest.schema.json       `.tbx` manifest JSON Schema
examples/position-calculator.tbx  后续阶段验收夹具
```

## 已知输入缺口

资料包的 `SHA256SUMS.txt` 和任务书都引用 `sdk/toolbox-api.d.ts`，但原始 ZIP、当前目录和现有 Git 历史均不包含该文件。阶段 3 的 RPC、JS shim 与最小原生 API 必须先恢复并校验 SHA-256 `7792a14e810d77d2e8c1368fc4cb38e2b4d304d8b4d701bfc082e2ef6dfb4421`，不能根据文档或示例自行臆造协议。决策与重新进入条件见 `docs/adr/0002-authoritative-sdk-gates-rpc-bridge.md`。

## 安全边界

安全不变量见 `AGENTS.md`。当前实现不使用 `addJavascriptInterface`、`file://` 或 localhost 服务，不申请广泛存储、应用列表、无障碍、短信、联系人或 root 权限。后续消息桥仍必须逐次校验 exact source origin、主 frame、session nonce、manifest 声明、ToolBox 授权、Android 权限、用户手势、限流和配额；远程网络只能通过带域名白名单、重定向复验和 SSRF 阻断的原生 HTTPS 代理。辅助进程只用于崩溃和内存隔离，不构成独立 UID 沙箱。
