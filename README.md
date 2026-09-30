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

## 构建与检查

Android 构建使用 JDK 21、Android SDK 37 和仓库中的 Gradle wrapper。主要检查与构建入口是 `.github/workflows/android.yml`；TBX 的目标选择、打包和校验入口是 `.github/workflows/tbx.yml` → `scripts/ci/tbx.py`。工具包的 ZIP 与 `integrity.json` 由 `scripts/package-tool.py` 生成；各工具的 `package.sh` 仅负责准备各自需要发布的资源。可在本机运行：

```sh
./gradlew :app:lint :core-data:testDebugUnitTest :tool-package:testDebugUnitTest :tool-runtime:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
python3 scripts/ci/tbx.py build --tool all
```

Android 实机或模拟器交互需要单独运行 `androidTest`；编译、JVM 单测和 TBX 打包通过不等于设备运行通过。工具开发、清单格式及权限接口见应用内的 `sdk/help/manual.md`；浏览器现行行为见 `docs/browser-core.md` 和 `docs/browser-filtering.md`。

GitHub Android CI 的手动入口可填写 `android_test_filter`（完整宿主测试类名或 `类名#方法名`），仅编译所需 debug/测试 APK 并执行指定用例，报告会明确标记为局部验证。
