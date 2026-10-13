# ToolBox

ToolBox 是 Android 工具宿主。宿主负责本地工具目录、TBX 安装与更新、权限、运行时、后台任务、备份和独立浏览器；工具页面作为受限的 WebView 资源运行，通过 `tool-api` 定义的接口调用宿主能力。

## 当前架构

| 功能 | 用户入口 | 主要实现 | 数据与平台依赖 |
| --- | --- | --- | --- |
| 首页、全部工具、分组 | `MainActivity` → `ToolBoxNavigation` | `CatalogViewModel`、`CatalogOrganization` | `core-data` 的 Room 目录与布局设置 |
| 导入、更新、卸载 TBX | 首页或全部工具的导入入口 | `ImportViewModel` → `DefaultToolPackageManager` | `tool-package` 的清单、完整性、签名和事务校验；本机文件与 Room |
| 工具运行及原生 API | 工具卡片 → `RuntimeRoute` | `RuntimeSessionManager` → `ToolRuntimePreparer` / `HardenedRuntimeWebView` / `RuntimeRpc` | `tool-runtime`、`tool-api`、宿主权限及本机资源 |
| 网络与后台任务 | 工具授权后的 API | `RuntimeNetworkGateway` → `ToolNetworkProxy`；`BackgroundTaskCoordinator` | OkHttp、WorkManager、Room |
| 权限、设置和备份 | 设置及工具详情 | `PermissionCenterViewModel`、`SettingsViewModel`、`HostBackupService` | 授权存储、DataStore、本机文档选择器 |
| 普通网页浏览 | 工具的浏览器能力 | `BrowserLauncher` → `BrowserActivity` | 独立 WebView、站点规则、系统下载器 |
| 宿主界面 | 上述原生页面 | `core-ui` 的主题 token、按钮、卡片、对话框与动效 | Compose、用户可选的 Miuix / Liquid Glass 外观 |

Gradle 项目包含 `app`、`core-ui`、`core-data`、`tool-package`、`tool-runtime`、`tool-api` 六个模块。TBX 是独立工具包，不是 Android 模块。`examples/` 中的 12 个工具由 `scripts/ci/tbx-targets.json` 登记；其中仓位计算器、快速笔记、后台任务演示、通知实验室四个在 APK 构建时打包为内置示例，其余工具单独分发。

## 本地构建

需要 JDK 21 和 Android SDK 37（`platforms;android-37.0`、`build-tools;37.0.0`）。在 `local.properties` 中写入 `sdk.dir`，或设置 `ANDROID_HOME`，然后使用仓库自带的 Gradle wrapper：

```bash
./gradlew :app:assembleDebug                 # 调试版 APK
./gradlew :app:lint                          # Lint
./gradlew testDebugUnitTest                  # 所有模块的 JVM 单元测试
./gradlew :app:connectedDebugAndroidTest     # 设备或模拟器上的行为测试
```

构建 APK 时会通过 `scripts/package-examples.sh` 把四个内置示例打包进 assets，因此本机还需要 `bash` 和 `python3`。单独打包某个工具使用 `scripts/package-tool.py`，各工具的 `package.sh` 负责准备各自需要发布的资源。

工具开发、清单格式及权限接口见应用内的 [开发手册](sdk/help/manual.md)。

## 持续集成

| 工作流 | 触发 | 内容 |
| --- | --- | --- |
| `android.yml` | 推送到 `main`、PR、手动 | 接口契约检查、浏览器脚本测试、Lint、Debug/Release 构建、全部 JVM 单元测试。手动运行可开启模拟器行为测试，也可以用 `android_test_filter` 只跑指定的类（`模块=完整类名#方法`，逗号分隔，模块为 `app`、`tool-runtime` 或 `core-data`，省略时为 `app`）。推送到 `main` 时另用正式签名构建 Release，在模拟器上冷启动验证后上传，并更新滚动预发布 `dev`（应用内“测试版”渠道）。 |
| `tbx.yml` | 示例、SDK 或运行时变更，手动 | 按 `scripts/ci/tbx-targets.json` 选出受影响的工具，运行各自测试并打包 `.tbx`；在 `main` 上通过的包会同步到预发布 `dev`。 |
| `webview-debugging.yml` | 相关文件的 PR，手动 | 在用户版系统镜像上验证 Debug / Release 的 WebView 调试状态。 |
| `release.yml` | 手动 | 取一次成功的 Android CI 签名产物，可沿用上一版的 `.tbx`，发布 GitHub Release。 |

编译、JVM 单测和 TBX 打包通过，不等于设备运行通过。

应用内“设置 › 软件更新”读取本仓库的公开 Release：正式版为最新正式发布（`releases/latest`），测试版为预发布 `dev`。小工具按文件名 `<目录名>-v<版本>.tbx` 与已安装工具 `io.toolbox.<目录名去掉连字符>` 对应；测试版对每个小工具取正式版与 `dev` 中较新的一个。

## 运行时

宿主前台状态由原生页面和 Activity 生命周期统一决定，SDK 使用 generation / revision 分发；业务后台计时继续运行，工具按状态停止前台绘制。普通退出和重载先保存，超过 2 秒可继续等待、取消或明确放弃。机制细节见 [运行时机制](docs/runtime.md)；浏览器见 [浏览器核心](docs/browser-core.md) 和 [浏览器过滤](docs/browser-filtering.md)；安全存储确认见 [安全存储与启动](docs/secure-storage-startup.md)。

### 调试版与正式版

「设置 → 开发者帮助」中的「小工具 WebView 调试」开关：调试版固定开启；正式版默认关闭，可在应用内临时开启，应用进程重启后自动关闭。构建需求为“调试版”时选择 Debug，为“正式版”时选择 Release：

| 版本 | 构建命令 | 小工具 WebView 调试状态 |
| --- | --- | --- |
| 调试版（Debug） | `./gradlew :app:assembleDebug` | 固定开启，无法关闭 |
| 正式版（Release） | `./gradlew :app:assembleRelease` | 普通用户版 Android 上默认关闭；可在应用内临时开启，重启应用后自动关闭 |

默认状态由 APK 的 `android:debuggable` 编译标志决定。正式版的应用内开关只保存在内存中，不写入设置或备份，只作用于主进程的小工具页面（不含内置浏览器）。构建产物分别位于 `app/build/outputs/apk/debug/` 和 `app/build/outputs/apk/release/`；使用相同签名才能覆盖安装并保留已有数据。WebView 的 Dev / Stable 通道不决定该状态。

开发版 Android 系统（`userdebug` / `eng`）可能强制开启接口，正式版请求关闭也会被忽略；此时开关显示实际开启状态并注明系统限制。连接电脑检查页面的步骤见 [开发手册](sdk/help/manual.md) 中的「小工具 WebView 调试」。

## 版本

当前版本为 ToolBox 0.8.32 / 65，与 NextFlux 1.0.50 / 71 一同发布。版本历史见 [CHANGELOG](CHANGELOG.md)。
