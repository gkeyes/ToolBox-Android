# 参与开发

本地构建环境和常用 Gradle 命令见 [README](README.md#本地构建)；工具开发、清单格式及权限接口见 [开发手册](sdk/help/manual.md)。本文说明持续集成、发布流程和更新渠道的规则。

## 持续集成

| 工作流 | 触发 | 内容 |
| --- | --- | --- |
| `android.yml` | 推送到 `main`、PR、手动 | 接口契约检查、浏览器脚本测试、Lint、Debug/Release 构建、全部 JVM 单元测试；PR 没有改到 Gradle 编译或打包的内容（只改 CI 脚本、文档或版本号）时跳过构建和 JVM 测试（`scripts/ci/pr_scope.py`）。手动运行可开启模拟器行为测试，也可以用 `android_test_filter` 只跑指定的类（`模块=完整类名#方法`，逗号分隔，模块为 `app`、`tool-runtime` 或 `core-data`，省略时为 `app`）。推送到 `main` 时另用正式签名构建 Release 并在模拟器上冷启动验证，与上面的检查同时进行；两者都通过后才上传并更新滚动预发布 `dev`（应用内“测试版”渠道）。推送到 `main` 的运行不会被后来的合并取消，连续合并时只有尚在排队的旧运行会被最新的一次取代。 |
| `tbx.yml` | 示例、SDK 或运行时变更，手动 | 按 `scripts/ci/tbx-targets.json` 选出受影响的工具，运行各自测试并打包 `.tbx`；在 `main` 上通过的包会同步到预发布 `dev`。 |
| `version-bump.yml` | 所有 PR | 按下文「版本号」的规则检查：改动了会打进 APK 或某个小工具 `.tbx` 的文件，却没有提高对应的版本号时失败，并在日志里写明要改哪个文件、改成多少。 |
| `webview-debugging.yml` | 相关文件的 PR，手动 | 在用户版系统镜像上验证 Debug / Release 的 WebView 调试状态。镜像自带 WebView 的已知原生崩溃（`scripts/ci/webview-debugging-connected.sh` 里写明的特征）会让这项测试重跑一次，其它失败不重跑。 `app/build.gradle.kts` 只改版本号时跳过。 |
| `release.yml` | 手动 | 取一次成功的 Android CI 签名产物，可沿用上一版的 `.tbx`，发布 GitHub Release。 |

编译、JVM 单测和 TBX 打包通过，不等于设备运行通过。

## 版本号

每次发布出去的更新都必须带新的版本号；同一个版本号不能对应两份不同的内容。宿主的版本在 `app/build.gradle.kts`（`versionName` / `versionCode`），小工具的版本在各自清单（`version` / `versionCode`）。

- 版本号一律为 `主版本.次版本.修订号`（如 `0.9.0`），三段都是数字，按数值比较。
- 修复 bug：修订号加 1（`0.9.0` → `0.9.1`）。
- 新增功能或明显的界面变化：次版本加 1，修订号归 0（`0.9.1` → `0.10.0`）。
- 只有大的重新设计才提高主版本，次版本和修订号归 0（`0.10.0` → `1.0.0`）。
- `versionCode` 每次至少加 1，与 `versionName` 一起提高，不能只改其中一个，也不能降低。

PR 改动了会发布出去的内容，就要在同一个 PR 里提高版本号，由 `version-bump.yml`（`scripts/ci/version_bump.py`）检查：

- 宿主：`settings.gradle.kts` 中各模块（`app`、`core-*`、`tool-*`）除测试源码集以外的文件、根目录 Gradle 配置和 `gradle/`（含版本目录与 wrapper）、`sdk/help/`，以及内置示例（`tbx-targets.json` 中标记 `bundled` 的工具）会打进包里的文件，或内置示例的名单本身。
- 小工具：清单、打包脚本，以及 `tbx-targets.json` 中 `expected_files` 列出的文件；需要先构建的工具（NextFlux、SocialCoach、健康档案）按 `version_bump.py` 里登记的构建输入判断。
- 只改测试、文档、CHANGELOG、CI 工作流或仓库脚本时不需要改版本号。

检查分不出修复和新功能，只要求两个版本号都高于目标分支；该提哪一段由提交者按上面的规则决定。

## 发布与更新渠道

应用内“设置 › 软件更新”读取本仓库的公开 Release：正式版为最新正式发布（`releases/latest`），测试版为预发布 `dev`。小工具按文件名 `<目录名>-v<版本>.tbx` 与已安装工具 `io.toolbox.<目录名去掉连字符>` 对应；测试版对每个小工具取正式版与 `dev` 中较新的一个。`dev` 上已有同一版本号的 APK 或 `.tbx` 时，CI 不会再次发布（只改 CI、文档的提交不会换掉测试版的安装包）；应用按 `versionCode` 判断测试版是否更新，所以每个新的测试版都显示新的版本号。

## 更新日志

版本历史记录在 [CHANGELOG](CHANGELOG.md)；尚未发布的变更写在「未发布」一节。
