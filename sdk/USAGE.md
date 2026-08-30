# ToolBox JS API 使用示例

```js
await ToolBox.ready();
await ToolBox.ui.toast("ToolBox 已连接");
```

## 存储

```js
await ToolBox.storage.set("draft", { value: 12.5, updatedAt: Date.now() });
const draft = await ToolBox.storage.get("draft");
```

## 权限与剪贴板

工具只能调用 manifest 已声明、且用户已在宿主权限页开启的能力。ToolBox API 不提供绕过宿主
权限页的请求方法；缺少授权时调用会返回 `PERMISSION_DENIED`。

```js
await ToolBox.clipboard.writeText("结果：12.50");
```

## 文件

```js
const input = await ToolBox.files.open(["text/plain"]);
if (input) {
  const bytes = await ToolBox.files.read(input.token);
}

await ToolBox.files.save("result.txt", "text/plain", "done");
```

## 受控网络

manifest 必须声明 `network` 权限和目标域名：

```js
const response = await ToolBox.network.request({
  url: "https://api.example.com/v1/data",
  method: "GET"
});
```

首版网络代理只支持 GET，不接受请求体、认证或 cookie。页面自身的 `fetch`、远程 script、
iframe 和 WebSocket 默认被 CSP 与宿主拦截。

## 后台任务

```js
const taskId = await ToolBox.background.enqueue({
  key: `refresh-${Date.now()}`,
  operation: {
    type: "httpGet",
    url: "https://api.example.com/v1/data"
  },
  constraints: { network: "connected" }
});
```

后台任务只支持 `httpGet` 和 `notify`。周期任务最短 15 分钟；不支持指定运行时间、充电条件或
低电量条件。
