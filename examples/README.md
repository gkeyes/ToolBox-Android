# ToolBox 小工具

这里集中收录本地小工具的源码与可导入安装包。主表列出各工具的最新收录版本，历史版本另行保留。原本的本地文件保留。

在 GitHub 打开包文件链接后，选择 **Download raw file** 保存 `.tbx`；需要从源码重新打包时，使用 [TBX CI](https://github.com/gkeyes/ToolBox-Android/actions/workflows/tbx.yml) 并从 Artifacts 下载。再到 ToolBox 的工具页导入。可以使用 ToolBox 0.6.7 导入下表各包；“最低 ToolBox”来自包内声明，不表示本轮重新验证了每个旧版本的运行表现。

| 小工具 | 版本 | 最低 ToolBox | 用途 | 文件 |
| --- | --- | --- | --- | --- |
| 后台任务演示 | 1.0.0 | 0.2.0 | 后台任务示例，ToolBox 内置 | [下载](packages/background-task-demo-v1.0.0.tbx) · [源码](background-task-demo/) |
| 2048 | 1.0.0 | 0.2.0 | 离线数字合并游戏 | [下载](packages/game-2048-v1.0.0.tbx) · [源码](game-2048/) |
| GitHub 构建守望 | 1.0.6 | 0.3.5 | GitHub Actions 构建进度 | [下载](packages/github-actions-watcher-v1.0.6.tbx) · [源码](github-actions-watcher/) |
| 健康档案 | 1.0.11 | 0.6.1 | 本地健康记录、趋势与可选 AI 整理 | [下载](packages/health-records-v1.0.11.tbx) · [源码](health-records/) |
| 稳力 | 1.0.2 | 0.6.4 | 离线节奏训练 | [下载](packages/kegel-trainer-v1.0.2.tbx) · [源码](kegel-trainer/) |
| NextFlux | 1.0.5 | 0.6.7 | Miniflux 阅读、AI 摘要与同步 | [CI 产物](https://github.com/gkeyes/ToolBox-Android/actions/workflows/tbx.yml) · [源码](nextflux/) |
| 通知实验室 | 1.0.0 | 0.3.2 | 通知示例，ToolBox 内置 | [下载](packages/notification-lab-v1.0.0.tbx) · [源码](notification-lab/) |
| 仓位计算器 | 1.0.0 | 0.2.0 | 仓位计算，ToolBox 内置 | [下载](packages/position-calculator-v1.0.0.tbx) · [源码](position-calculator/) |
| 快速笔记 | 1.0.0 | 0.2.0 | 本地笔记，ToolBox 内置 | [下载](packages/quick-notes-v1.0.0.tbx) · [源码](quick-notes/) |
| 行情哨兵 | 1.1.1 | 0.3.2 | 行情与阈值提醒 | [下载](packages/stock-monitor-v1.1.1.tbx) · [源码](stock-monitor/) |

## 目录约定

- 各小工具目录保留源码、说明和封装入口；健康档案的运行源码位于 `health-records/web/`，对应最新收录的 1.0.11。
- `packages/` 只放带版本号的 `.tbx` 和 [SHA-256 清单](packages/SHA256SUMS.txt)，不放 APK、缓存、账号、个人记录或开发环境。
- NextFlux 需要前端编译，使用 GitHub 产物；其他静态小工具按各自说明封装。发布新包时递增版本，使用新文件名，不覆盖已交付文件。

## 通用 TBX CI

在 Actions 中打开 **TBX CI → Run workflow**，选择分支，然后填写 `tool`：

- 单个：`nextflux` 或 `github-actions-watcher`。
- 多个：`nextflux,health-records`，以逗号分隔。
- 全部：`all`。

当前支持 `background-task-demo`、`game-2048`、`github-actions-watcher`、`health-records`、`kegel-trainer`、`nextflux`、`notification-lab`、`position-calculator`、`quick-notes`、`stock-monitor`。工作流只接受已登记名称，不执行输入中的命令或任意路径。

推送和 PR 按改动目录选择小工具；SDK、协议、导入器、共享打包脚本及宿主版本等共同依赖变化时检查全部已登记工具。每个工具使用独立任务，保留自己的测试与构建方式，执行两次打包字节比较、清单及完整性检查和当前宿主生产导入器验证。没有现有单元测试的工具会在回执记录 `NOT_AVAILABLE`，不记为通过；NextFlux 继续运行生产 Worker 的无截图浏览器交互检查。

每个成功任务上传 `<工具目录>-v<清单版本>-<提交号>` artifact，其中包含带版本号的 `.tbx`、`SHA256SUMS.txt` 和 `BUILD_AND_TEST_RECEIPT.txt`。它们是 CI 产物，不会自动发布 GitHub Release 或覆盖 `examples/packages/` 中的历史包。

Android CI 保留宿主构建、安全检查、模拟器测试、APK 签名和内置示例验证。独立 TBX 由 TBX CI 验证交付；两个工作流各自报告结果，APK 回执不代替 TBX 检查结果。

新增工具在 [tbx-targets.json](../scripts/ci/tbx-targets.json) 登记清单位置、检查文件和打包入口即可，无需再建专属工作流。打包命令以工具目录为工作目录，`{output}` 为 CI 新建输出路径，`{version}` 来自清单；沿用固定输出路径的脚本通过 `built_path` 指定产物。构建脚本 [tbx.py](../scripts/ci/tbx.py) 只允许在 GitHub Actions 执行构建，`plan --tool all` 可只读核对已登记清单。

## 本次整理

- 新增 2048 和稳力的源码，补齐此前仅保留在本地的内容。
- 稳力 1.0.2 修正误填的最低 ToolBox 版本，并附完整性清单；它尚未接入 ToolBox 持久存储或触觉接口，记录在当前 ToolBox 中仅保留本次会话。
- 健康档案最新收录版本为 [1.0.11](packages/health-records-v1.0.11.tbx)：相较 1.0.9，AI 单次等待上限从 5 分钟延长至 15 分钟，最低 ToolBox 为 0.6.1；其余业务逻辑不变。[1.0.9](packages/health-records-v1.0.9.tbx) 作为历史版本保留。本次未重新验证真实账号或设备。
- NextFlux 1.0.7 需要 ToolBox 0.6.7 或更新版本，支持通过 GitHub CI 构建；保留 1.0.6 外链弹窗改版，新增正文键帽 Emoji 显示兼容，原始文章、链接和代码保持不变；保留完整文章和同步范围，增加系统浏览器打开、长文分批显示、按可见块加载字体与高亮，并优化增量写入和同步交互调度。旧 [1.0.3](packages/nextflux-v1.0.3.tbx) 与 [1.0.1](packages/nextflux-v1.0.1.tbx) 保留。四个内置示例沿用已通过 CI 的 ToolBox 0.6.4 包。
- 本轮核对版本、入口、包结构、完整性和可直接比较的源码文件；未执行本地编译、额外截图检查或手机运行验证。
