# GitHub 构建守望

独立交付的 ToolBox `.tbx`，只读监控一个 GitHub 仓库中手动选择的 Actions workflow。

1.0.6 需要 ToolBox 0.3.5 或更新版本。包内和页面均使用用户提供的白色猫剪影 PNG；保留终态步骤同步修复、刷新状态与 Token 持久化反馈，并将宿主状态收进标题行以减少顶部占用。

顶部每秒更新下一次刷新倒计时，同步中显示等待时长，失败后显示重试倒计时，后台调度延迟会明确提示。此倒计时不额外请求 GitHub，认证/匿名基础轮询间隔不变。

使用 4 MiB 网络响应与 8 MiB 消息上限容纳 GitHub 的完整构建列表；响应过大、超时、连接失败与域名阻止会显示不同错误，不把它们统一描述为断网。更新工具后按宿主规则重新开启网络、通知和后台权限；安全存储中的 Token 如被清理，需要重新填写。

## 能力

- 支持仓库、Actions 和具体 workflow 页面链接。
- 公共仓库可匿名读取；私有仓库可保存 fine-grained Token（仅需 `Actions: read`）。点击“保存 Token”可独立保存，点击“读取仓库”也会先保存新输入；即使随后网络失败，下次打开仍自动复用。输入框留空表示继续使用已保存 Token，不代表 Token 丢失。可填写新值替换，或点击“清除已保存 Token”删除。
- 分支下拉在页面内展开，不依赖 WebView 的系统弹窗；优先列出仓库分支，再补充近期构建分支。私有仓库未授予 `Contents: read` 时保留默认和近期分支，也可手动输入。
- 使用最近最多 10 次成功构建的算术平均耗时估算 step、总体进度和剩余时间。
- 通过 `background.runtime` 在后台发现新 run，并用 `notifications.live` 更新普通持续通知、Android Live Update 和 HyperOS 超级岛增强数据。
- 只调用 GitHub REST 只读接口，不提供触发、取消或重跑操作。

Token 只写入宿主 `storage.secure`，不写入普通状态或网页本地存储；安全存储不可用会明确提示，不会假装保存成功。正常关闭、重新打开和后台恢复会重新读取已保存值；当前宿主在导入新版 `.tbx` 时会清除安全存储，因此更新工具包后仍需重新保存。这是宿主更新策略，本轮未改变。

## 打包与测试

```sh
node --check examples/github-actions-watcher/github-model.js
node --check examples/github-actions-watcher/app.js
node --test examples/github-actions-watcher/github-model.test.js examples/github-actions-watcher/app.test.js
bash examples/github-actions-watcher/package.sh
```

产物位于 `build/github-actions-watcher/github-actions-watcher-v1.0.6.tbx`。

## 图标来源

`icon.png` 是用户提供的 512 × 512 透明底白色猫剪影，原图保持不变，页面以黑色底板保证浅色和深色主题下可见。manifest 和页面引用同一份图标。

ToolBox 0.3.6 的宿主工具列表仍使用固定分类图标，不读取包内图标；通知栏与超级岛仍使用宿主图标，本次均未修改。GitHub 构建守望是非官方集成工具，不代表 GitHub 官方产品或背书。
