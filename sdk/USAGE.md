# ToolBox 小工具开发

[完整开发手册](help/manual.md) 是 App「设置 → 开发帮助」离线读取的同一份内容，包含分层教程、
完整最小工程、权限、网络、后台与通知、系统能力、打包和错误排查。

## 从零开始

1. 复制 [最小工程](templates/minimal) 到自己的目录，修改 `manifest.json` 中的工具身份。
2. 在仓库根目录执行下面的通用打包命令。只需要 Python 3.9+，不编译 APK。
3. 在 ToolBox 导入生成的 `.tbx`，按手册验证保存、重开恢复和复制。

```sh
python3 scripts/package-tool.py sdk/templates/minimal ./my-tool-v1.0.0.tbx
```

自己的目录也使用同一命令；输出要放在源目录之外。输出已存在时默认拒绝覆盖。
`scripts/package-examples.sh` 仅用于四个内置范例，不是任意目录打包器。

## 接口与维护

- [TypeScript 接口](toolbox-api.d.ts)：参数、返回值和事件订阅的当前合同。
- [manifest schema](../schema/manifest.schema.json)：声明字段及约束。
- [通用打包器](../scripts/package-tool.py)：递归包含静态资源，重新生成完整性清单。
- `node scripts/check-developer-help.mjs`：静态检查手册、嵌入源码与接口覆盖，不启动 Gradle。

网络已支持 GET、POST、PUT、PATCH、DELETE、HEAD、普通认证 Header 和请求体；始终受
network 声明与授权、HTTPS、TLS 校验及消息预算约束。持续运行使用 `background.start/listSessions`；
`background.list` 仍表示 WorkManager 任务。具体示例均在完整手册中。

`ToolBox.browser.open(url)` 在系统浏览器中打开 HTTP/HTTPS 绝对地址。单独声明 `browser`，
并由用户开启“浏览器打开”权限；不依赖 network 或 Android 运行时权限。调用需要前台和近期
真实触摸，URL 最多 2048 个字符且不能含凭据或控制字符，每分钟最多 10 次。成功仅表示系统
接受启动，不表示目标网页已加载；不会退回任意 App 跳转或 WebView 导航。

接口错误仍提供 `code` 和 `message`；限流错误可以附带可选的 `retryAfterMs`，表示限流窗口
剩余的非负有限整数毫秒。页面可据此提示等待时间；旧宿主或其他错误可不带此字段。需要用户
手势的能力应等用户再次点击，不能在倒计时结束后自动重试。
