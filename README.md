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

Android 构建使用 JDK 21、Android SDK 37 和仓库中的 Gradle wrapper。构建和验证在 GitHub Actions 执行。主要入口是 `.github/workflows/android.yml`；TBX 的目标选择、打包和校验入口是 `.github/workflows/tbx.yml` → `scripts/ci/tbx.py`。工具包的 ZIP 与 `integrity.json` 由 `scripts/package-tool.py` 生成；各工具的 `package.sh` 负责准备各自需要发布的资源。

Android 实机或模拟器交互需要单独运行 `androidTest`；编译、JVM 单测和 TBX 打包通过不等于设备运行通过。工具开发、清单格式及权限接口见应用内的 `sdk/help/manual.md`；浏览器现行行为见 `docs/browser-core.md` 和 `docs/browser-filtering.md`。

自动验证按变更路径选择受影响的用例；未登记的代码路径会报错，要求补映射，不自动退回全量测试。GitHub Android CI 手动入口选择 `targeted`，可填写 `unit_test_filter` 和 `android_test_filter`：`模块=完整测试类名#方法名`，多个用逗号分隔。Android 测试支持 `app`、`tool-runtime`，省略模块表示 `app`。TBX 手动入口可以按工具及测试文件选择验证范围。报告保留实际范围，失败后只重跑失败及随后修改直接影响的检查。

发布时可通过 `reuse_verified_run` 指定已经通过的宿主验证。流程核对同仓库来源、提交已并入当前版本及 Android/SDK/内置工具的构建输入未变后，保留原验证范围并复用结果，仅检查 CI 调整和最终签名 APK 的冷启动。独立 TBX 的源码和打包由对应工具的工作流单独验证。

## 运行时与资源调度

宿主前台状态由原生页面和 Activity 生命周期统一决定，SDK 使用 generation / revision 分发；业务后台计时继续运行，工具按状态停止前台绘制。普通退出和重载先保存，超过 2 秒可继续等待、取消或明确放弃。后台最后一个会话被外部停止时，保存总计等待 2 秒；更新、撤权和数据清理直接结束旧 generation。

事件队列按会话和进程的逻辑字节预算准入，原生在 JS 确认收件后释放预算；网络等待队列共用一个 50 ms 探测器，按工具轮转并保持工具内 FIFO；后台任务由 SQL 分别查询活动状态和每页 50 条的历史。参数选取、具体调用过程、保存边界和验证范围见 [运行时性能实现记录](docs/runtime-performance.md)。

### 调试版与正式版切换（0.8.28 起）

「设置 → 开发者帮助」中的「小工具 WebView 调试」开关只显示编译版本的状态，应用内不可切换。构建需求为“调试版”时选择 Debug，为“正式版”时选择 Release：

| 版本 | 构建命令 | 小工具 WebView 调试状态 |
| --- | --- | --- |
| 调试版（Debug） | `./gradlew :app:assembleDebug` | 固定开启，无法关闭 |
| 正式版（Release） | `./gradlew :app:assembleRelease` | 普通用户版 Android 上固定关闭，无法开启 |

状态由 APK 的 `android:debuggable` 编译标志决定。需要切换时，重新构建并安装对应的已签名 APK；使用相同签名才能覆盖安装并保留已有数据。构建产物分别位于 `app/build/outputs/apk/debug/` 和 `app/build/outputs/apk/release/`。重启应用不改变调试状态，WebView 的 Dev / Stable 通道也不决定该状态。

开发版 Android 系统（`userdebug` / `eng`）可能强制开启接口，正式版请求关闭也会被忽略；此时开关显示实际开启状态并注明系统限制。连接电脑检查页面的步骤见 [开发手册](sdk/help/manual.md) 中的「小工具 WebView 调试」。
