# 参与开发

本地构建环境和常用 Gradle 命令见 [README](README.md#本地构建)；工具开发、清单格式及权限接口见 [开发手册](sdk/help/manual.md)。本文说明持续集成、发布流程和更新渠道的规则。

## 持续集成

| 工作流 | 触发 | 内容 |
| --- | --- | --- |
| `android.yml` | 推送到 `main`、PR、手动 | 接口契约检查、浏览器脚本测试、Lint、Debug/Release 构建、全部 JVM 单元测试；PR 没有改到 Gradle 编译或打包的内容（只改 CI 脚本或文档）时跳过构建和 JVM 测试（`scripts/ci/pr_scope.py`）。手动运行可开启模拟器行为测试，也可以用 `android_test_filter` 只跑指定的类（`模块=完整类名#方法`，逗号分隔，模块为 `app`、`tool-runtime` 或 `core-data`，省略时为 `app`）。在 `main` 上（推送和手动运行）先按 `changes/` 里的更新说明算出宿主版本号（见下文「版本号」），本次运行的所有 APK 构建都用这个版本号；另用正式签名构建 Release 并在模拟器上冷启动验证，与上面的检查同时进行；两者都通过后才上传并更新滚动预发布 `dev`（应用内“测试版”渠道）。自上次发布以来有会打进 APK 的改动却没有新增更新说明时，计算版本号这一步就失败，不构建也不更新 `dev`。推送到 `main` 的运行不会被后来的合并取消，连续合并时只有尚在排队的旧运行会被最新的一次取代。 |
| `tbx.yml` | 示例、SDK 或运行时变更，手动 | 先跑 `scripts/tests` 下的 Python 脚本测试（只改 `tbx.yml` 或 `tbx.py` 的 PR 不触发 `android.yml`，这些守护测试在这里也会运行），再按 `scripts/ci/tbx-targets.json` 选出受影响的工具，运行各自测试并打包 `.tbx`。需要先构建的工具（`npm: true`）改了 `package.json` 或 `package-lock.json` 时跑该工具的全部测试。在 `main` 上通过的包会同步到预发布 `dev`：每个成功的打包任务都必须交出自己的 `.tbx`，下载不到即失败；推送改了某个工具会发布的文件却没提高它的版本号时，这个工具不发布（其它工具照常发布），任务最后失败。 |
| `version-bump.yml` | 所有 PR | 按下文「版本号」的规则检查：改动了会打进 APK 的文件却没有新增 `changes/` 更新说明、更新说明格式不对、或者改了 `app/build.gradle.kts` 里的 `versionCode` / `versionName` 时失败；改动了某个小工具 `.tbx` 会打包的文件却没有提高它的版本号时失败。日志里写明要改什么。 |
| `webview-debugging.yml` | 相关文件的 PR，手动 | 在用户版系统镜像上验证 Debug / Release 的 WebView 调试状态。镜像自带 WebView 的已知原生崩溃（`scripts/ci/webview-debugging-connected.sh` 里写明的特征）会让这项测试重跑一次，其它失败不重跑。 `app/build.gradle.kts` 只改版本号时跳过（宿主版本号现由 CI 计算，PR 不再改它）。 |
| `release.yml` | 手动 | 取一次成功的 Android CI 签名产物发布 GitHub Release 并设为最新正式版。新标签必须按版本号严格高于当前的 `releases/latest`。每个登记的小工具都按该次构建所在提交里清单的版本附上 `.tbx`：先从 `dev` 取（按 GitHub 记录的 SHA-256 校验），`dev` 已经换成更新版本时再从 `reuse_tbx_from` 指定的旧版 Release 取（按它的 `SHA256SUMS.txt` 校验）；包内清单的 ID 和版本也要一致，哪个工具找不到对应版本就整个发布失败，不会带上旧包（`scripts/ci/stable_release.py`）。 |

编译、JVM 单测和 TBX 打包通过，不等于设备运行通过。

## 版本号

每次发布出去的更新都必须带新的版本号；同一个版本号不能对应两份不同的内容。宿主的版本号由 `main` 上的 CI 在发布时计算（见下面的「宿主：更新说明」），小工具的版本在各自清单（`version` / `versionCode`）。

- 版本号一律为 `主版本.次版本.修订号`（如 `0.9.0`），三段都是数字，按数值比较。
- 修复 bug：修订号加 1（`0.9.0` → `0.9.1`）。
- 新增功能或明显的界面变化：次版本加 1，修订号归 0（`0.9.1` → `0.10.0`）。
- 只有大的重新设计才提高主版本，次版本和修订号归 0（`0.10.0` → `1.0.0`）。
- `versionCode` 每次至少加 1，与 `versionName` 一起提高，不能只改其中一个，也不能降低。

PR 改动了会发布出去的内容，就要在同一个 PR 里写明版本变化（宿主：新增一条更新说明；小工具：提高清单里的版本号），由 `version-bump.yml`（`scripts/ci/version_bump.py`）检查。哪些文件算发布出去的内容：

- 宿主：`settings.gradle.kts` 中各模块（`app`、`core-*`、`tool-*`）除测试源码集以外的文件、根目录 Gradle 配置和 `gradle/`（含版本目录与 wrapper）、`sdk/help/`，以及内置示例（`tbx-targets.json` 中标记 `bundled` 的工具）会打进包里的文件，或内置示例的名单本身。
- 小工具：清单、打包脚本，以及 `tbx-targets.json` 中 `expected_files` 列出的文件；需要先构建的工具（NextFlux、SocialCoach、健康档案）按 `version_bump.py` 里登记的构建输入判断。
- 只改测试、文档、CHANGELOG、CI 工作流或仓库脚本时不需要改版本号，也不需要更新说明。

### 宿主：更新说明（`changes/`）

改动会打进 APK 的 PR **不改** `app/build.gradle.kts` 里的 `versionCode` / `versionName`（改了检查会失败），也不改 `CHANGELOG.md`，而是新增一个文件 `changes/<不与别人重名的名字>.md`（建议用分支名），格式见 [changes/README.md](changes/README.md)：开头用 `bump: patch|minor|major` 写明按上面的规则该提哪一段（修复 patch，新功能或明显的界面变化 minor，大的重新设计 major），下面一句简体中文的更新说明，不写版本号。这样同时打开的多个 PR 不会在版本号和更新日志上互相冲突，合并别的 PR 后也不需要再改版本号重跑 CI。

`main` 上的 Android CI（推送和手动运行）在构建 APK 之前由 `scripts/ci/app_version.py` 计算版本号：从测试版 `dev` 上次发布的构建（其 `BUILD_RECEIPT.txt` 中的 `VERSION_NAME`、`VERSION_CODE` 和 `BUILD_COMMIT`）出发，收集那次提交之后新增的更新说明，按其中最大的一级提高 `versionName`（major > minor > patch，低位归 0），`versionCode` 加 1；没有新增更新说明时版本号不变，发布时按“已发布”跳过。版本号经 `TOOLBOX_VERSION_NAME` / `TOOLBOX_VERSION_CODE` 传给本次运行里每一次 APK 构建；`app/build.gradle.kts` 里写的值只在本地和 PR 构建、以及 `dev` 还没有任何 APK 时使用。CI 不往 `main` 写回任何内容。收集到的更新说明写进构建记录 `BUILD_RECEIPT.txt`（`CHANGELOG_1`、`CHANGELOG_2`……）和 `dev` 的发布说明。

PR 检查看不到它运行之后别的 PR 合并进来的改动，所以计算版本号时会再查一次：自 `dev` 那次构建以来有会打进 APK 的改动却没有任何新增更新说明时失败，不构建也不发布（补一个只加更新说明的 PR 即可）。读取 `dev` 时只有“发布不存在”（HTTP 404）或其中还没有 APK 才退回 `app/build.gradle.kts` 的值，其它读取失败重试后即失败，不会按猜测的版本号构建。推送到 `main` 的运行一次只跑一个；手动运行可能与推送运行重叠，所以发布前再确认 `dev` 在算出版本号之后没有被别的构建更新，否则不发布，需要重新运行全部任务。

### 小工具

检查分不出修复和新功能，只要求两个版本号都高于目标分支；该提哪一段由提交者按上面的规则决定。

PR 检查看不到它运行之后别的 PR 合并进来的版本号，所以推送到 `main` 时发布前会再查一次（`version_bump.py --push`，与推送前的 `main` 比较），在 `tbx.yml` 的发布任务里逐个查。新建分支（推送前为全零）或强制推送后旧提交已不在历史里时，改为与推送前后两者的共同祖先比较，再不行就只与该提交的第一个父提交比较。手动运行没有推送前的提交，不做这项检查。

## 发布与更新渠道

应用内“设置 › 软件更新”读取本仓库的公开 Release：正式版为最新正式发布（`releases/latest`），测试版为预发布 `dev`。小工具按文件名 `<目录名>-v<版本>.tbx` 与已安装工具 `io.toolbox.<目录名去掉连字符>` 对应；测试版对每个小工具取正式版与 `dev` 中较新的一个。`dev` 上已有同一版本号的 APK 或 `.tbx` 时，CI 不会再次发布（只改 CI、文档的提交不会换掉测试版的安装包）；应用按 `versionCode` 判断测试版是否更新（CI 计算的 `versionCode` 每次发布都加 1），所以每个新的测试版都显示新的版本号。正式版（`release.yml`）取某次 Android CI 运行的签名产物，标签须与该次计算出的版本号一致（见 `dev` 的发布说明或构建记录）。

## 更新日志

宿主的新变更写在 `changes/` 的更新说明里（见上文），发布时汇总进构建记录和 `dev` 的发布说明；[CHANGELOG](CHANGELOG.md) 保留此前的历史记录。
