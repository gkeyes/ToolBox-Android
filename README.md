# ToolBox Android

ToolBox 是 Android 13+ 的轻量 `.tbx` 小工具宿主。它导入包含 HTML/CSS/JavaScript 的
本地 ZIP 包，在唯一 HTTPS origin 的硬化 WebView 中运行，并按工具提供真实的权限开关和
可独立于 Compose 页面生命周期的通用网页运行环境。

当前重构的目标是让用户只经历：**导入、使用、授权、后台任务、删除**。导入是一次操作：
选择 `.tbx` 后，宿主在后台完成结构、完整性和签名检查；成功进入工具列表，失败显示原因且
没有残留。不会出现审核会话、风险评分、签名/发布者标签、审计日志或恢复审核页面。

## 开发基线

这是尚未发布的全新数据基线。现有安装、权限和设置不保留：最终 Room schema 从
`version = 1` 新建，没有 database migration、兼容读取或旧字段回退。候选 APK 以卸载
`io.toolbox.host` 后的干净安装验证。

设置只保留真实主题、后台保障、工具权限和 Developer Help。工具详情提供打开、权限、后台任务和
删除；权限是每工具的虚拟 grant，仍必须通过 manifest、宿主 Android 权限、用户手势、配额
与 origin 校验才会生效。

## 功能切片

1. **导入、目录和权限**：SAF 导入、内部包检查、原子安装/更新、真实删除、Miuix 权限开关。
2. **安全运行时与基础 API**：exact HTTPS origin、CSP、消息桥、`ready`、toast、SHA-256、
   storage、secure storage、device basic、haptics、clipboard write；包内同源 Web Worker 可承载
   高负载前台计算，ServiceWorker 与远程 Worker 仍禁用。
3. **持续运行环境**：应用级管理器拥有 WebView、permit、bridge、timer 和位置监听；运行页只
   挂载显示层。工具主动 `background.start()` 后可在离开页面时继续工作，并在进程/重启恢复后
   接收事件；一个 `specialUse` 前台服务提供持续通知和停止入口。
4. **通用宿主能力**：声明域名内的公网 HTTPS 请求、普通/HyperOS 增强通知、前后台位置 watch、精确
   闹钟，以及剪贴板、分享、SAF、快捷方式和相机。0.2 WorkManager 任务 API 冻结兼容。

仓位计算器、快速笔记、后台任务演示三个现有范例保持原样并继续内置；0.3 不新增范例，最终
GitHub 产物只包含 APK、SHA256 清单和同提交测试回执。

## 本地运行

需要 JDK 21 与 Android SDK 37。按当前阶段只运行最小相关验证；完整命令与每项测试的理由、
方法、预期结果见 [`TESTING.md`](TESTING.md)。典型本地构建为：

```bash
./gradlew --no-daemon verifySecurityInvariants assembleDebug testDebugUnitTest
```

需要真机测试时，可在 Android 13+ 设备上安装：

```bash
./gradlew :app:installCandidate
```

`candidate` 与 release 使用相同的 R8 优化，仅使用本机 debug key 签名以便直接安装测试；不要用
debug 构建评价页面帧性能。

GitHub Actions 在安全不变量、API 合同、静态编译和最小单元门禁通过后上传 APK、
`SHA256SUMS.txt` 和构建回执。系统权限、SAF、相机、通知、持续运行、后台位置、精确闹钟和 HyperOS 增强通知由用户在小米真机
上验证；自动交付流程不启动模拟器，也不把未执行的设备测试写成通过。

## 工程结构

```text
app/                              宿主 Compose 页面、路由、系统结果协调
core-ui/                          ToolBox/Miuix 适配层与主题
core-data/                        Room、DataStore、目录、grant、KV、任务与结果
tool-package/                     `.tbx` 检查、签名/完整性、原子安装与卸载
tool-runtime/                     exact-origin AssetLoader 与硬化 WebView
tool-api/                         API v1 合同、bridge、handler 与后台协调
docs/ToolBox_Android_技术方案.md   当前产品与安全架构基线
examples/                         三个范例源码、打包脚本与 `.tbx`
```

## 安全边界

安全不变量以 [`AGENTS.md`](AGENTS.md) 为准：不使用 `addJavascriptInterface`、`file://` 或
localhost；不申请广泛存储、应用列表、无障碍、短信、联系人或 root 权限；所有 WebView 调用
验证 origin/frame/nonce/声明/grant/系统权限/手势/限额；网络只经原生 HTTPS 代理并执行 manifest
域名 allowlist、重定向和 SSRF 检查。回环、私网、保留地址和 IP 字面量始终禁止；
简化界面不等于放宽这些边界。
