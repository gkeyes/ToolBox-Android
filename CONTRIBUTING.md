# 参与开发

本地构建环境和常用 Gradle 命令见 [README](README.md#本地构建)；工具开发、清单格式及权限接口见 [开发手册](sdk/help/manual.md)。本文说明持续集成、发布流程和更新渠道的规则。

## 持续集成

| 工作流 | 触发 | 内容 |
| --- | --- | --- |
| `android.yml` | 推送到 `main`、PR、手动 | 接口契约检查、浏览器脚本测试、Lint、Debug/Release 构建、全部 JVM 单元测试；PR 没有改到 Gradle 编译或打包的内容（只改 CI 脚本、文档或版本号）时跳过构建和 JVM 测试（`scripts/ci/pr_scope.py`）。手动运行可开启模拟器行为测试，也可以用 `android_test_filter` 只跑指定的类（`模块=完整类名#方法`，逗号分隔，模块为 `app`、`tool-runtime` 或 `core-data`，省略时为 `app`）。推送到 `main` 时另用正式签名构建 Release 并在模拟器上冷启动验证，与上面的检查同时进行；两者都通过后才上传并更新滚动预发布 `dev`（应用内“测试版”渠道）。推送到 `main` 的运行不会被后来的合并取消，连续合并时只有尚在排队的旧运行会被最新的一次取代。 |
| `tbx.yml` | 示例、SDK 或运行时变更，手动 | 按 `scripts/ci/tbx-targets.json` 选出受影响的工具，运行各自测试并打包 `.tbx`；在 `main` 上通过的包会同步到预发布 `dev`。 |
| `webview-debugging.yml` | 相关文件的 PR，手动 | 在用户版系统镜像上验证 Debug / Release 的 WebView 调试状态。`app/build.gradle.kts` 只改版本号时跳过。 |
| `release.yml` | 手动 | 取一次成功的 Android CI 签名产物，可沿用上一版的 `.tbx`，发布 GitHub Release。 |

编译、JVM 单测和 TBX 打包通过，不等于设备运行通过。

## 发布与更新渠道

应用内“设置 › 软件更新”读取本仓库的公开 Release：正式版为最新正式发布（`releases/latest`），测试版为预发布 `dev`。小工具按文件名 `<目录名>-v<版本>.tbx` 与已安装工具 `io.toolbox.<目录名去掉连字符>` 对应；测试版对每个小工具取正式版与 `dev` 中较新的一个。

## 更新日志

版本历史记录在 [CHANGELOG](CHANGELOG.md)；尚未发布的变更写在「未发布」一节。
