# RSS Scout

ToolBox 内的 RSS 探测小工具。

## v1.4.7

- 修改 RSSHub 地址时中途打错又改回，不再清空并删除已保存的实例密钥；是否保留密钥在保存时按最终地址决定。
- 首次同步只拿到一半规则（Radar 或私人规则）时，缓存会记下缺少的部分，下次探测时重新同步，设置摘要也会注明。

## v1.4.6

- 重绘图标（iOS 27 风格）。

## v1.4.5

- 订阅带 RSSHub 访问密钥（或其他鉴权参数）的地址到 Miniflux 前会先确认，提醒密钥将明文保存在服务器上。
- 支持带 DOCTYPE 声明的 RSS（如 RSS 0.91）；仍拒绝含内部实体声明的文档。
- 私人规则地址无效时，Radar 规则同步不再整体失败，只在同步详情中提示私人规则问题。
- 删除永远无法生效的旧版 Radar v1 缓存迁移，并清理遗留的旧缓存键。
- 页面增加内容安全策略（CSP）；修复 RSS 服务设置页多显示的 “\n” 字样。
- 新增 node:test 单元测试（订阅源解析、网页提取、字符集解码、Miniflux 客户端），并接入 TBX CI。

## v1.4.4

- 默认私人规则地址改为仓库 `main` 分支；已保存旧分支地址的设置会自动换成新地址。

## v1.4.2

- 小红书 RSSWorker 首次冷抓验证时限单独提高到 45 秒，避免有效订阅因 Browser Run 首次加载超过默认 20 秒而被误判失败。
- 其他 RSS 候选仍使用原有全局超时设置。

## v1.4.1

- 小红书用户完整分享链接会保留公开分享参数，自动生成可用的 RSSWorker 订阅地址。
- 支持白名单式查询参数透传；只转发规则明确允许的参数，不复制其他查询参数。
- xhslink.cn 短链在探测发生跳转后，可使用最终小红书主页 URL 继续匹配规则。

## v1.4.0

- 支持同时配置 **RSSHub** 与 **RSSWorker** 两个服务地址。
- RSSHub 留空时自动使用 `https://rsshub.app`；RSSWorker 为可选项，留空时不探测 Worker。
- RSSHub Radar 与私人规则源可以叠加使用。
- 私人规则使用 `targets.rsshub` / `targets.worker` 描述各服务的 Route；规则不保存实例域名。
- 同一网页可同时生成两边候选并并行验证，只展示各自真实验证状态，由用户自行选择订阅哪个来源。
- 旧版 `target + service` 私人规则仍可读取，但新规则不再使用该结构。
- 私人规则仍是声明式 JSON，不执行远程 JavaScript、函数或正则执行器。

### 私人规则格式

```json
{
  "schemaVersion": 1,
  "name": "My RSS Rules",
  "rules": [
    {
      "id": "telegram-channel",
      "title": "Telegram 频道",
      "hosts": ["t.me"],
      "source": ["/:channel"],
      "targets": {
        "rsshub": "/telegram/channel/:channel",
        "worker": "/rss/telegram/channel/:channel"
      }
    }
  ]
}
```

某个服务没有对应实现时，直接省略该 target。例如 GitHub Actions 成功运行目前只配置 Worker：

```json
{
  "targets": {
    "worker": "/rss/github/actions/:owner/:repo/:workflow"
  }
}
```

`source` 和各 target 只允许静态路径段与 `:param` / `:param?`。实例地址由 RSS Scout 设置管理，与规则 Repo 解耦。

### 打包

运行 `bash package.sh` 会先执行私人规则与规则同步回归测试，再生成可导入 ToolBox 的 `.tbx`；该工具已注册到仓库 TBX CI。

单元测试：`npm ci && npm test`（使用 jsdom 提供 DOMParser，仅用于测试，不打进 `.tbx`）。
