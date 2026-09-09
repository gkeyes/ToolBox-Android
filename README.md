# ToolBox OpenDesign

ToolBox 是 Android 13+ 的个人 `.tbx` 小工具宿主，导入包含 HTML/CSS/JavaScript 的 ZIP 包，
在唯一 HTTPS origin 的硬化 WebView 中运行。核心流程是 **导入、使用、授权、后台任务、删除**。

包结构、完整性和签名在后台检查，用户只接收成功或可操作的失败结果。同版本覆盖和降级需要
一次确认；失败或取消不留下待安装状态。工具默认空白安装，示例通过真实导入流程安装。
所有按钮、权限和后台状态都对应实际功能，不增加审核、发布者信任或安全状态展示。

## 本次界面升级

基于 GitHub 默认分支 `codex/refactor-lightweight-v2` 的 `dc78a48baf03692d45c1877309c30412301f28ed`。
独立工作目录 `ToolBox-OpenDesign` 保留上游历史；开发分支为原仓库的 `codex/opendesign-ui`，`upstream` 指向 `gkeyes/ToolBox-Android`。
设计项目为 `04260a2f-f55c-40f3-ab23-0ab14063fa02`；原始文件及校验值存放于
[design/opendesign](design/opendesign)。最新 HTML 为唯一视觉参考，真实业务继续使用 Android Compose。

运行原型（Python 3，无 npm 依赖）：

```sh
python3 -m http.server 8772 --bind 127.0.0.1 --directory design/opendesign
# 浏览器打开 http://127.0.0.1:8772/toolbox-ios-redesign.html
```

运行 Android 应用（JDK 21、Android SDK 37、已启动的 Android 13+ 设备/模拟器）：

```sh
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb shell am start -n io.toolbox.host/.MainActivity
```

针对本次改动的验证命令：

```sh
./gradlew :app:compileDebugAndroidTestKotlin :core-ui:testDebugUnitTest
./gradlew :tool-api:verifyToolBoxApiContract
bash scripts/verify-security-invariants.sh "$PWD"
python3 scripts/verify-opendesign-source.py
```

Android CI 额外运行外观页 2 倍字体交互、后台任务单项停止与批量入口的行为测试；不包含自动截图测试。

debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。未配置稳定签名时它采用本地 debug key，
与 GitHub 已交付 APK 的签名不同；使用独立模拟器测试。本地构建不代表已发布或 GitHub CI 通过。

## 当前能力

- 原生 Compose 宿主使用 OpenDesign Liquid Glass 唯一主题，保留明暗、系统取色和降低透明度；
  颜色切换不重建导航或正在运行的 WebView。
- 工具内容填满系统安全区域，系统返回退出工具；内部返回由小工具根据自身页面状态处理。
- 每工具权限开关仍受 manifest、Android 系统权限、前台/手势、origin 与限额检查约束。
- 普通存储按键保存，提供 `getMany` 和原子 `apply`，不设每工具持久存储总容量配额；安全值
  单独使用安全存储。网络通过按工具授权的原生 HTTPS 代理，支持可取消的流式读取。
- 工具主动启动持续会话后才可脱离界面继续运行；应用级管理器持有 WebView，一个前台服务
  承载各会话的独立通知和停止入口。位置、闹钟、分享、浏览器打开、SAF、快捷方式和相机
  使用对应的原生能力；卸载清理工具数据、权限和后台资源。

当前宿主为 **0.7.2 (26)**，版本来源是 [构建配置](app/build.gradle.kts)。同签名升级保留工具、
授权和设置；Room schema 保持 v1。功能边界与最低宿主要求以技术方案和 SDK 手册为准。

## 小工具与开发入口

仓位计算器、快速笔记、后台任务演示和通知实验室四个范例随 APK 提供；其他小工具独立打包。
各工具版本、源码、安装包和 CI 下载入口统一见 [小工具目录](examples/README.md)。已交付包
收录于 `examples/packages/`，重新打包不得覆盖原产物。

开发新工具从 [最小工程](sdk/templates/minimal) 开始，修改工具身份、版本与权限，再在仓库根目录执行：

```sh
python3 scripts/package-tool.py sdk/templates/minimal ./my-tool-v1.0.0.tbx
```

通用打包器只需要 Python 3.9+ 和标准库，输出应位于源码目录外，已存在时拒绝覆盖。
原生能力需在 ToolBox 中验证；普通浏览器没有 `window.ToolBox`。

- [完整开发手册](sdk/help/manual.md)：与 App 离线 Developer Help 共用，包含教程、返回手势、权限、API 和打包排错。
- [TypeScript 接口](sdk/toolbox-api.d.ts) 与 [manifest schema](schema/manifest.schema.json)：参数、返回值、声明字段及约束。
- [合同维护规则](docs/ToolBox_Android_技术方案.md#61-单一协议来源)：修改 API 后同步合同、实现、声明、schema 和手册。

## 宿主构建与验证

构建环境为 JDK 21、Android SDK 37，依赖固定在 [版本目录](gradle/libs.versions.toml)。
构建和测试通过 GitHub Actions 执行：[Android CI](.github/workflows/android.yml) 验证宿主，
[TBX CI](.github/workflows/tbx.yml) 构建独立小工具；宿主检查入口为
[scripts/qa/run-host-gate.sh](scripts/qa/run-host-gate.sh)。

宿主通过协议、安全、编译和相关行为检查后，构建固定签名的 release APK，并上传 APK、
SHA256 清单和同提交回执。release 关闭调试，启用 R8 和资源裁剪；映射独立归档。
本地 `candidate` 未配置固定签名时使用 debug key，不能覆盖已交付的 GitHub 同签名版本。

GitHub 模拟器执行指定的存储事务与浏览器启动回归，NextFlux 浏览器测试使用合成数据。
[NextFlux 性能比较](examples/nextflux/test/performance/README.md) 说明同配置前后对比方法；
[宿主性能采集](docs/performance/BASELINE.md) 说明授权真机测量的输入与结果解释。
这些证据不替代 Android 真机、真实服务或 HyperOS 展示验证。

自动截图测试、插件和 PNG 基线已退役。`app/src/debug` 仅保留 IDE 手动预览；回执记录
`HOST_SCREENSHOT_VALIDATION=REMOVED_BY_USER_REQUEST`，不是视觉验收通过。

## 维护文档与源码

| 入口 | 职责 |
|---|---|
| [AGENTS.md](AGENTS.md) | 开发约定、不可放宽的安全边界和验证要求。 |
| [技术方案](docs/ToolBox_Android_技术方案.md) | 产品范围、模块、包生命周期、权限、API、运行隔离和后台机制。 |
| [设计规范](DESIGN.md) | 唯一主题、页面布局、组件映射、交互和可访问性。 |
| `app/` | 宿主页面、路由、系统结果和运行会话协调。 |
| `core-ui/` / `core-data/` | 主题与组件；Room、DataStore、目录、授权和存储。 |
| `tool-package/` / `tool-runtime/` / `tool-api/` | 包安装；硬化 WebView；协议、消息桥与原生能力。 |

依赖实际版本以 Gradle 目录和锁定信息为准，不在多份文档重复维护版本表。外部参考：
[Miuix 源码](https://github.com/compose-miuix-ui/miuix)、[Miuix 文档](https://compose-miuix-ui.github.io/miuix/)、
[WebKit 版本说明](https://developer.android.com/jetpack/androidx/releases/webkit)、
[AssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)、
[原生消息桥](https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge)。
第三方用途与授权信息见 [Third-party notices](THIRD_PARTY_NOTICES.md)，各独立小工具另保留自己的许可与上游来源。
