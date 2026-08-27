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

```js
const state = await ToolBox.permissions.request(
  "clipboard.write",
  "复制计算结果"
);
if (state === "granted" || state === "granted_once") {
  await ToolBox.clipboard.writeText("结果：12.50");
}
```

## 文件

```js
const [file] = await ToolBox.files.pick({ mimeTypes: ["text/plain"] });
const text = await ToolBox.files.readText(file, 256 * 1024);

const output = await ToolBox.files.create({
  suggestedName: "result.txt",
  mimeType: "text/plain"
});
await ToolBox.files.writeText(output, "done");
```

## 受控网络

manifest 必须声明 `network` 权限和目标域名：

```js
const response = await ToolBox.network.request({
  url: "https://api.example.com/v1/data",
  method: "GET",
  responseType: "json"
});
```

页面自身的 `fetch`、远程 script、iframe 和 WebSocket 默认被 CSP 与宿主拦截。
