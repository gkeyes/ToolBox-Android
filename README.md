# ToolBox Android

ToolBox 是 Android 13+ 的轻量 `.tbx` 小工具宿主。它导入包含 HTML/CSS/JavaScript 的
本地 ZIP 包，在唯一 HTTPS origin 的硬化 WebView 中运行，并按工具提供真实的权限开关和
受限后台任务。

当前重构的目标是让用户只经历：**导入、使用、授权、后台任务、删除**。导入是一次操作：
选择 `.tbx` 后，宿主在后台完成结构、完整性和签名检查；成功进入工具列表，失败显示原因且
没有残留。不会出现审核会话、风险评分、签名/发布者标签、审计日志或恢复审核页面。

## 开发基线

这是尚未发布的全新数据基线。现有安装、权限和设置不保留：最终 Room schema 从
`version = 1` 新建，没有 database migration、兼容读取或旧字段回退。候选 APK 以卸载
`io.toolbox.host` 后的干净安装验证。

设置只保留真实主题、后台总开关和 Developer Help。工具详情提供打开、权限、后台任务和
删除；权限是每工具的虚拟 grant，仍必须通过 manifest、宿主 Android 权限、用户手势、配额
与 origin 校验才会生效。

## 功能切片

1. **导入、目录和权限**：SAF 导入、内部包检查、原子安装/更新、真实删除、Miuix 权限开关。
2. **安全运行时与基础 API**：exact HTTPS origin、CSP、消息桥、`ready`、toast、SHA-256、
   storage、secure storage、device basic、haptics、clipboard write。
3. **后台任务**：WorkManager 委派的 allowlist HTTPS `httpGet` 和命名空间通知；工具代码不在
   后台运行，也不使用后台 WebView 或常驻前台服务。
4. **App 式能力与帮助**：剪贴板读取、系统分享、SAF 文件、快捷方式、系统相机、前台单次
   定位，以及从 API/范例派生的离线 Developer Help。

最终随 APK 交付三个可导入示例：仓位计算器、快速笔记、后台任务演示。每一个都包含源码、
manifest、完整性清单、可重复打包脚本和 `.tbx`。

## 本地运行

需要 JDK 21 与 Android SDK 37。按当前阶段只运行最小相关验证；完整命令与每项测试的理由、
方法、预期结果见 [`TESTING.md`](TESTING.md)。典型本地构建为：

```bash
./gradlew --no-daemon verifySecurityInvariants assembleDebug testDebugUnitTest
```

连接 Android 13+ 设备或模拟器后安装：

```bash
./gradlew :app:installDebug
```

最终候选必须先卸载旧包，再干净安装并完成一次小米真机组合旅程。GitHub Actions 只有在编译和
模拟器关键路径均通过后才上传 APK、三个 `.tbx` 和 `SHA256SUMS.txt`。

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
验证 origin/frame/nonce/声明/grant/系统权限/手势/限额；网络只经原生 HTTPS 代理并执行域名、
重定向和 SSRF 检查。简化界面不等于放宽这些边界。
