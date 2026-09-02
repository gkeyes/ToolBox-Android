# GitHub 构建守望

独立交付的 ToolBox `.tbx`，只读监控一个 GitHub 仓库中手动选择的 Actions workflow。

## 能力

- 支持仓库、Actions 和具体 workflow 页面链接。
- 公共仓库可匿名读取；私有仓库可保存 fine-grained Token（仅需 `Actions: read`）。
- 分支下拉在页面内展开，不依赖 WebView 的系统弹窗；优先列出仓库分支，再补充近期构建分支。私有仓库未授予 `Contents: read` 时保留默认和近期分支，也可手动输入。
- 使用最近最多 10 次成功构建的算术平均耗时估算 step、总体进度和剩余时间。
- 通过 `background.runtime` 在后台发现新 run，并用 `notifications.live` 更新普通持续通知、Android Live Update 和 HyperOS 超级岛增强数据。
- 只调用 GitHub REST 只读接口，不提供触发、取消或重跑操作。

## 打包与测试

```sh
node --check examples/github-actions-watcher/github-model.js
node --check examples/github-actions-watcher/app.js
node --test examples/github-actions-watcher/github-model.test.js
bash examples/github-actions-watcher/package.sh
```

产物位于 `build/github-actions-watcher/github-actions-watcher-v1.0.1.tbx`。

## 图标来源

图标中的 GitHub Invertocat 来自 [GitHub 官方品牌资源](https://brand.github.com/foundations/logo)，使用官方白色标识置于黑色底板，不改变标识路径。GitHub 构建守望是用户自用的非官方集成工具，不代表 GitHub 官方产品或背书；对外发布时应遵守 GitHub 的商标使用规则。
