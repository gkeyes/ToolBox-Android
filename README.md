# ToolBox Android

> 面向 Android 13+ 的本地 `.tbx` HTML 小工具宿主。
>
> An early Android host for locally imported HTML mini-tools.

[从源码构建](#从源码构建) · [查看 API](sdk/USAGE.md) · [查看示例](#三个示例) · [提交问题](https://github.com/gkeyes/ToolBox-Android/issues)

## ToolBox 是什么

ToolBox 让你把包含 HTML、CSS 和 JavaScript 的 `.tbx` ZIP 包导入 Android 设备，并在原生宿主中打开、授权、管理和删除。小工具拥有自己的界面和业务逻辑；宿主只提供有明确边界的 Android 集成能力。

它不是应用商店、云同步服务或通用浏览器，也不会让小工具任意执行原生代码、访问 root 或取得广泛设备权限。

## 当前状态

| 项目 | 当前情况 |
| --- | --- |
| 代码基线 | `main` 上的 `0.2.0`，仍在开发中 |
| 平台 | Android 13+，JDK 21，Android SDK 37 |
| 正式 v0.2 Release | 尚未发布；请从源码构建或使用对应 GitHub Actions 产物进行测试 |
| 旧预览包 | [v0.1.0-alpha.1](https://github.com/gkeyes/ToolBox-Android/releases/tag/v0.1.0-alpha.1) 仅代表旧实现，不包含当前 v0.2 功能 |
| 验证边界 | CI 运行静态安全、协议、编译与单元门禁；系统权限、SAF、相机、通知、后台时序和 HyperOS 系统栏仍需在目标设备上验证 |

v0.2 是全新的数据基线，**不承诺保留旧安装、权限或设置**。请把它当作开发候选，而不是面向普通用户的稳定产品，也不要用它处理敏感数据或关键工作流。

## 能做什么

### 导入与管理

- 通过系统文件选择器导入 `.tbx`；
- 在后台检查包结构、manifest、完整性和可选签名，失败不会留下半安装状态；
- 原子安装、更新和卸载工具；
- 在工具详情中打开、管理权限、查看后台任务或删除工具。

### 小工具 API

ToolBox API 1.0 提供受限的原生能力，所有调用都必须同时通过包声明、每工具授权、Android 系统状态、用户手势、速率和配额检查。

- 本地/安全存储、toast、SHA-256、基础设备信息、触觉反馈和剪贴板写入；
- 明确确认后的剪贴板读取、系统分享、SAF 文件读写、快捷方式、系统相机和前台单次定位；
- 经域名白名单限制的 HTTPS GET、通知与原生委派后台任务。

后台任务由 WorkManager 和原生代码执行；**工具 JavaScript 不会在后台常驻，也不会保活 WebView。**

## 三个示例

仓库随附可重复打包、可直接导入的 API 1.0 示例：

| 示例 | 说明 |
| --- | --- |
| [仓位计算器](examples/position-calculator) | 保存输入、计算结果、复制与触觉反馈 |
| [快速笔记](examples/quick-notes) | 创建、编辑、删除、恢复和复制本地笔记 |
| [后台任务演示](examples/background-task-demo) | 受控 GitHub HTTP 请求、通知、任务列表与取消 |

运行以下命令会生成三个 `.tbx` 文件到 `build/examples/`：

```bash
bash scripts/package-examples.sh
```

## `.tbx` 包结构

`.tbx` 是一个 ZIP 小工具包。通常至少包含入口页面和 manifest：

```text
my-tool.tbx
├── manifest.json       # 身份、入口、版本与权限声明
├── index.html          # HTML 入口
├── app.js / style.css  # 工具自身前端资源
├── assets/             # 可选静态资源
├── integrity.json      # 完整性清单
└── signature.json      # 可选包内签名
```

- [manifest JSON Schema](schema/manifest.schema.json) 定义可声明的字段和 capability；
- [TypeScript API 声明](sdk/toolbox-api.d.ts) 是前端调用的类型合同；
- [API 使用示例](sdk/USAGE.md) 提供最小调用方式。

不要在包中放入 APK、DEX、JAR、SO、class、嵌套压缩包或其他动态/原生代码。

## 安全边界

ToolBox 的安全策略旨在限制导入内容，而不是让安全信息占据使用流程：

- 每个工具由独立的 exact HTTPS AssetLoader origin 提供，不使用 `file://` 或 localhost；
- 不使用 `addJavascriptInterface`；WebMessage 调用校验来源、主 frame、会话 nonce、当前版本、声明、授权、系统权限、手势、速率和配额；
- 远程网络默认关闭；启用后也只能经过原生 HTTPS 代理，并重新校验域名白名单、重定向和私有/保留地址；
- 不申请广泛存储、应用列表、无障碍、短信、联系人或 root 权限；
- 后台执行是原生委派任务，不是独立 UID 沙箱，也不是任意 JavaScript 的后台运行环境。

这些是工程边界，不是独立安全审计或绝对隔离保证。

## 从源码构建

需要 JDK 21 和 Android SDK 37：

```bash
git clone https://github.com/gkeyes/ToolBox-Android.git
cd ToolBox-Android
./gradlew --no-daemon verifySecurityInvariants assembleDebug testDebugUnitTest
bash scripts/package-examples.sh
```

调试 APK 输出在 `app/build/outputs/apk/debug/`，示例包输出在 `build/examples/`。完整测试准入、理由与预期见 [TESTING.md](TESTING.md)。本地构建通过不等同于所有真机系统表面的验证已完成。

## 文档与反馈

- [GitHub Actions](https://github.com/gkeyes/ToolBox-Android/actions)
- [发布版本](https://github.com/gkeyes/ToolBox-Android/releases)
- [测试准入与验证矩阵](TESTING.md)
- [提交 Bug 或功能建议](https://github.com/gkeyes/ToolBox-Android/issues)

提交问题时，请附上 Android 版本、设备型号、ToolBox 版本、重现步骤和不含敏感内容的日志或截图。不要在公开 Issue 中上传私密工具包、凭据或个人数据。

## License

本仓库目前尚未发布许可证。除非仓库所有者另行添加许可证，否则请不要假定可以将其代码或资源用于其他项目。
