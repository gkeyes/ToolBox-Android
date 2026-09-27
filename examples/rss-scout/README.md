# RSS Scout

ToolBox 内的 RSS 探测小工具。

## v1.3.0

- RSSHub Radar 与私人规则源可以叠加使用。
- 所有官方 Radar 与私人发现规则统一使用一个 RSSHub 实例地址。
- 用户未修改实例地址时使用 `https://rsshub.app`；填写自建实例后，所有规则统一使用该实例。
- 实例地址输入框留空时也会自动回退到 `https://rsshub.app`。
- 私人规则源使用 `RSS Scout Rules v1` 声明式 JSON，不执行远程脚本。
- 私人规则命中后仍会实际请求生成地址并验证 RSS。
- 支持 `paramDeny` / `paramDenyPrefix` 排除 Telegram 的 share/proxy/joinchat/邀请链接等非频道路径。
- `service` 字段已废弃；规则只负责 URL → Route 映射，不决定实例地址。

### 私人规则格式

```json
{
  "schemaVersion": 1,
  "name": "My RSS Rules",
  "rules": [
    {
      "id": "example",
      "title": "Example",
      "hosts": ["example.com"],
      "source": ["/:slug"],
      "target": "/example/:slug",
      "paramDeny": {"slug": ["login"]},
      "paramDenyPrefix": {"slug": ["+"]}
    }
  ]
}
```

`source` 和 `target` 只允许静态路径段与 `:param` / `:param?`，远程规则不能提供 JavaScript、正则执行器或函数。

规则中的 `target` 会统一拼接到用户配置的 RSSHub 实例地址；未自定义时使用 `https://rsshub.app`。

### 打包

运行 `bash package.sh` 会先执行私人规则与规则同步回归测试，再生成可导入 ToolBox 的 `.tbx`；该工具也已注册到仓库的 TBX CI。
