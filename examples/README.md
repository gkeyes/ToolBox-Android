# ToolBox 小工具

这里集中收录本地小工具的源码与可导入安装包。主表列出各工具的最新收录版本，历史版本另行保留。原本的本地文件保留。

在 GitHub 打开包文件链接后，选择 **Download raw file** 保存 `.tbx`；NextFlux 从所链接 CI 的 Artifacts 下载。再到 ToolBox 的工具页导入。可以使用 ToolBox 0.6.6 导入下表各包；“最低 ToolBox”来自包内声明，不表示本轮重新验证了每个旧版本的运行表现。

| 小工具 | 版本 | 最低 ToolBox | 用途 | 文件 |
| --- | --- | --- | --- | --- |
| 后台任务演示 | 1.0.0 | 0.2.0 | 后台任务示例，ToolBox 内置 | [下载](packages/background-task-demo-v1.0.0.tbx) · [源码](background-task-demo/) |
| 2048 | 1.0.0 | 0.2.0 | 离线数字合并游戏 | [下载](packages/game-2048-v1.0.0.tbx) · [源码](game-2048/) |
| GitHub 构建守望 | 1.0.6 | 0.3.5 | GitHub Actions 构建进度 | [下载](packages/github-actions-watcher-v1.0.6.tbx) · [源码](github-actions-watcher/) |
| 健康档案 | 1.0.11 | 0.6.1 | 本地健康记录、趋势与可选 AI 整理 | [下载](packages/health-records-v1.0.11.tbx) · [源码](health-records/) |
| 稳力 | 1.0.2 | 0.6.4 | 离线节奏训练 | [下载](packages/kegel-trainer-v1.0.2.tbx) · [源码](kegel-trainer/) |
| NextFlux | 1.0.4 | 0.6.6 | Miniflux 阅读、AI 摘要与同步 | [CI 产物](https://github.com/gkeyes/ToolBox-Android/actions/workflows/android.yml) · [源码](nextflux/) |
| 通知实验室 | 1.0.0 | 0.3.2 | 通知示例，ToolBox 内置 | [下载](packages/notification-lab-v1.0.0.tbx) · [源码](notification-lab/) |
| 仓位计算器 | 1.0.0 | 0.2.0 | 仓位计算，ToolBox 内置 | [下载](packages/position-calculator-v1.0.0.tbx) · [源码](position-calculator/) |
| 快速笔记 | 1.0.0 | 0.2.0 | 本地笔记，ToolBox 内置 | [下载](packages/quick-notes-v1.0.0.tbx) · [源码](quick-notes/) |
| 行情哨兵 | 1.1.1 | 0.3.2 | 行情与阈值提醒 | [下载](packages/stock-monitor-v1.1.1.tbx) · [源码](stock-monitor/) |

## 目录约定

- 各小工具目录保留源码、说明和封装入口；健康档案的运行源码位于 `health-records/web/`，对应最新收录的 1.0.11。
- `packages/` 只放带版本号的 `.tbx` 和 [SHA-256 清单](packages/SHA256SUMS.txt)，不放 APK、缓存、账号、个人记录或开发环境。
- NextFlux 需要前端编译，使用 GitHub 产物；其他静态小工具按各自说明封装。发布新包时递增版本，使用新文件名，不覆盖已交付文件。

## 本次整理

- 新增 2048 和稳力的源码，补齐此前仅保留在本地的内容。
- 稳力 1.0.2 修正误填的最低 ToolBox 版本，并附完整性清单；它尚未接入 ToolBox 持久存储或触觉接口，记录在当前 ToolBox 中仅保留本次会话。
- 健康档案最新收录版本为 [1.0.11](packages/health-records-v1.0.11.tbx)：相较 1.0.9，AI 单次等待上限从 5 分钟延长至 15 分钟，最低 ToolBox 为 0.6.1；其余业务逻辑不变。[1.0.9](packages/health-records-v1.0.9.tbx) 作为历史版本保留。本次未重新验证真实账号或设备。
- NextFlux 1.0.4 与 ToolBox 0.6.6 配套，通过 GitHub CI 交付；保留完整文章和同步范围，改用批量存储、正文分离及 Worker 处理，并修复列表、正文和图片滚动路径。旧 [1.0.3](packages/nextflux-v1.0.3.tbx) 与 [1.0.1](packages/nextflux-v1.0.1.tbx) 保留。四个内置示例沿用已通过 CI 的 ToolBox 0.6.4 包。
- 本轮核对版本、入口、包结构、完整性和可直接比较的源码文件；未执行本地编译、额外截图检查或手机运行验证。
