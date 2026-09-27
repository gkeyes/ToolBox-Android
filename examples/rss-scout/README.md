# RSS Scout

ToolBox 内的 RSS 探测小工具。

## v1.2.0

- RSSHub Radar 与私人规则源可以叠加使用。
- 私人规则源使用 `RSS Scout Rules v1` 声明式 JSON，不执行远程脚本。
- 私人规则命中后仍会实际请求 RSSHub 生成地址验证 RSS。
- 支持 `paramDeny` / `paramDenyPrefix` 排除 Telegram 的 share/proxy/joinchat/邀请链接等非频道路径。

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
