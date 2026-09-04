# ToolBox 小工具开发手册

从网页文件到可导入的小工具。章节默认折叠，点击展开；可以搜索接口、复制代码或复制整份手册交给开发助手。适用于 ToolBox 0.3.3，API 1.0。

## 快速开始

先做一个能保存和复制内容的小工具，再按需要增加能力。

### 开发前准备

你只需要会编写 HTML、CSS 和 JavaScript，不需要 Android SDK，也不需要编译 APK。普通浏览器可以预览网页，但没有 window.ToolBox，原生能力必须在 ToolBox 中验证。

仓库：https://github.com/gkeyes/ToolBox-Android
在仓库根目录使用 sdk/templates/minimal 作为起点；也可以把下一节的四段代码分别保存为同名文件。通用打包器只需要 Python 3.9 或更新版本及标准库，不需要额外安装依赖。

改用你自己的 manifest.id、name 和版本。不要把个人 Token 写入源码、manifest 或打包目录；运行时让使用者输入，再用 storage.secure 保存。

### 完整最小工程

下面四个文件可以直接配套使用：保存数据、退出重开恢复，以及点击按钮复制。manifest 已包含实际使用的 storage 和 clipboard.write 声明。icon 是可选字段；添加图标后再把文件路径写入 manifest.icon。

```json sdk/templates/minimal/manifest.json
{
  "schemaVersion": 1,
  "id": "io.example.mytool",
  "name": "我的工具",
  "version": "1.0.0",
  "versionCode": 1,
  "entry": "index.html",
  "apiVersion": "1.0",
  "minHostVersion": "0.3.2",
  "permissions": [
    { "name": "storage", "reason": "保存输入内容" },
    { "name": "clipboard.write", "reason": "点击按钮复制内容" }
  ],
  "securityProfile": "strict"
}
```

```html sdk/templates/minimal/index.html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <title>我的工具</title>
  <link rel="stylesheet" href="style.css">
  <script src="app.js" defer></script>
</head>
<body>
  <main>
    <h1>我的工具</h1>
    <label for="draft">内容</label>
    <textarea id="draft" rows="5" placeholder="写下一段内容"></textarea>
    <div class="actions">
      <button id="save" type="button" disabled>保存</button>
      <button id="copy" type="button" disabled>复制</button>
    </div>
    <p id="status" role="status" aria-live="polite">正在连接 ToolBox…</p>
  </main>
</body>
</html>
```

```css sdk/templates/minimal/style.css
:root {
  color-scheme: light dark;
  font-family: system-ui, sans-serif;
  color: #17181a;
  background: #f7f7f7;
}
* { box-sizing: border-box; }
body { margin: 0; }
main { max-width: 40rem; margin: auto; padding: 1.25rem; }
h1 { font-size: 1.5rem; font-weight: 600; }
label { display: block; margin-bottom: .5rem; }
textarea {
  display: block;
  width: 100%;
  padding: .75rem;
  border: 1px solid #b8b9bf;
  border-radius: .75rem;
  font: inherit;
  color: inherit;
  background: #fff;
}
.actions { display: flex; flex-wrap: wrap; gap: .75rem; margin-top: 1rem; }
button {
  min-width: 6rem;
  min-height: 48px;
  padding: .75rem 1rem;
  border: 0;
  border-radius: .75rem;
  font: inherit;
  color: #fff;
  background: #1266cc;
}
button:disabled { opacity: .5; }
button:focus-visible, textarea:focus-visible { outline: 3px solid #1684ff; outline-offset: 3px; }
#status { line-height: 1.6; overflow-wrap: anywhere; }
@media (prefers-color-scheme: dark) {
  :root { color: #f1f1f3; background: #111214; }
  textarea { background: #1c1d20; border-color: #787a83; }
}
```

```js sdk/templates/minimal/app.js
(async function () {
  "use strict";
  const draft = document.getElementById("draft");
  const status = document.getElementById("status");
  const save = document.getElementById("save");
  const copy = document.getElementById("copy");
  const api = window.ToolBox;

  function showError(error) {
    status.textContent = (error.code || "ERROR") + "：" + (error.message || "操作失败");
  }

  if (!api) {
    status.textContent = "请打包为 .tbx 并在 ToolBox 中打开；普通浏览器没有原生接口。";
    return;
  }
  try {
    await api.ready();
    const saved = await api.storage.get("draft");
    draft.value = typeof saved === "string" ? saved : "";
    save.disabled = false;
    copy.disabled = false;
    status.textContent = "已就绪。保存后退出重开，内容仍会保留。";
  } catch (error) {
    showError(error);
    return;
  }

  save.addEventListener("click", async function () {
    try {
      await api.storage.set("draft", draft.value);
      status.textContent = "已保存";
    } catch (error) { showError(error); }
  });
  copy.addEventListener("click", async function () {
    if (!draft.value) {
      status.textContent = "请输入要复制的内容";
      return;
    }
    try {
      await api.clipboard.writeText(draft.value);
      status.textContent = "已复制";
    } catch (error) { showError(error); }
  });
})();
```

### 打包、导入与第一次验证

1. 把四个文件放入 my-tool 目录。manifest.json 必须在目录根部。
2. 在仓库根目录执行下面命令；输出文件必须位于源目录之外。
3. 在 ToolBox 工具页点击“导入”，选择生成的 .tbx。
4. 打开工具，输入内容并点击“保存”，退出重开后检查内容仍在；点击“复制”后在其他输入框粘贴验证。
5. 新增网络、通知、文件或后台能力后，到工具详情的“权限”开启对应开关。必要时还需允许 Android 系统权限。

```sh
python3 scripts/package-tool.py ./my-tool ./my-tool-v1.0.0.tbx
```

也可以先打包仓库提供的模板，确认自己的开发路径可用：

```sh
python3 scripts/package-tool.py sdk/templates/minimal ./my-tool-v1.0.0.tbx
```

命令只打包网页，不编译 ToolBox App。已有同名输出时默认拒绝覆盖；确认只替换该文件时才添加 --overwrite。完整打包器代码位于“打包与排错”。

## 声明与权限

先声明能力，再由用户开启；声明本身不会弹出或绕过系统授权。

### manifest 字段与版本

必填字段：
- schemaVersion：固定为 1。
- id：稳定的反向域名标识，例如 io.example.mytool；同一个工具更新时不要修改。
- name：显示名称，最多 40 个字符。
- version：形如 1.0.0 的版本号；versionCode：大于 0 的整数，更新时递增。
- entry：包内相对 HTML 路径，例如 index.html；不能使用绝对路径或上级目录。
- apiVersion：固定为 1.0；minHostVersion：要求的最低宿主版本。
- permissions：由 name 和 reason 组成的数组，不需要能力时可以为空。
- securityProfile：新工具使用 strict，把脚本写在独立 .js 文件中。

可选字段：icon、shortName、description、categories、network、ui、limits。字段格式以 schema/manifest.schema.json 为准；不要加入自行发明的顶级字段。

0.3 持续后台、后台位置和闹钟能力要求 minHostVersion 至少 0.3.0；实时通知至少 0.3.1。建议新工具从当前修复基线 0.3.2 起步。仅写高版本号不会自动获得能力，宿主仍会实际检查版本和权限。

### 同一图标用于列表、通知和超级岛

把图标文件放进 `.tbx`，在 manifest 的可选 `icon` 字段填写包内相对路径，例如 `"icon": "assets/icon.png"`。它和其他资源一起进入完整性清单；不需要额外权限，也不需要在通知 API 中重复传图片。

宿主读取当前已安装版本的这张图，供工具列表、最近使用、首页正在运行、详情、普通通知大图标、实时通知和 HyperOS 超级岛使用。小工具内部网页仍需自己引用相同资源。ToolBox 桌面图标与 Android 通知来源的小图标仍代表宿主，不冒充独立安装的 App；通知的具体摆放由系统模板决定。

推荐正方形 256–512px PNG，也支持 JPEG、WebP 和静态 SVG。宿主等比缩放到 256px 的透明画布，不给彩色图标套主题色；纯白透明标记会获得统一深色底板，保证浅深背景均可辨认。SVG 支持路径、形状、文字和本地渐变，不执行脚本、不读取外链、实体、嵌入图片、use、滤镜或遮罩；复杂效果请预先导出 PNG。

图标解码是宿主缩略图任务：位图源文件最多 4MiB、6400 万源像素，SVG 最多 256KiB、2048 个元素和 32 层嵌套。这些限制只影响图标缩略图，不限制网页计算或其他资源。缺失、不支持或损坏时使用默认图标，工具和通知仍正常工作。文件读取、校验、解码不在界面线程；缓存按已安装版本区分，更新和删除后清理旧图。

### 能力清单与默认授权

只有 manifest 声明的能力才会出现在该工具权限页。以下默认状态来自当前 API 合同；如果用户关闭了某项能力，后续调用仍会失败。
- storage：普通 JSON 数据；默认开启。
- storage.secure：加密保存的 JSON 数据；默认开启。
- clipboard.write：写入剪贴板；默认开启；需要近期真实触摸。
- clipboard.read：读取剪贴板；默认关闭；需要真实手势及原生确认。
- share：系统分享；默认关闭；需要近期真实触摸。
- files.open：选择并读取文件；默认关闭；需要近期真实触摸。
- files.save：保存文件；默认关闭；需要近期真实触摸。
- network：原生 HTTPS 请求；默认关闭；系统条件：INTERNET。
- device.basic：系统版本、语言、时区和屏幕分类；默认开启。
- haptics：触觉反馈；默认开启；需要近期真实触摸；系统条件：VIBRATE。
- notifications：普通和实时通知；默认关闭；系统条件：POST_NOTIFICATIONS。
- shortcuts：固定桌面快捷方式；默认关闭；需要近期真实触摸。
- camera：系统相机拍照；默认关闭；需要近期真实触摸。
- location：一次定位与位置监听；默认关闭；系统条件：ACCESS_COARSE_LOCATION。
- background.tasks：WorkManager 后台任务；默认关闭。
- background.runtime：持续网页运行环境；默认关闭。
- location.background：后台接收位置；默认关闭；系统条件：ACCESS_BACKGROUND_LOCATION。
- alarms：精确闹钟；默认关闭。

Android 授权属于 ToolBox App，工具开关属于当前小工具；两者都满足才生效。INTERNET、VIBRATE 这类系统条件不代表每次调用都会弹窗。没有 ToolBox.requestPermission 之类的网页授权接口；页面应给出“请到工具权限开启”的操作提示。

后台位置还必须同时声明并开启 location、location.background 和 background.runtime。files.read 不需要单独声明；它继承创建该文件令牌时的 files.open、files.save 或 camera 授权。

### 网页加载与权限常见陷阱

ToolBox 自动在顶层网页注入 window.ToolBox，不需要下载或手动引入 SDK 脚本。sdk/toolbox-api.d.ts 是类型声明文件，不是浏览器执行文件。

先等待 ToolBox.ready() 再进行原生调用。剪贴板、触觉、文件、相机、分享和快捷方式等交互，要在按钮的真实点击回调中及时发起；不要先等待长网络请求再尝试使用该手势。不能用 JS 自报 userGesture:true 代替触摸。

strict 模式使用本地外链脚本和样式，不要写内联 script、onclick 字符串、eval、new Function、远程脚本或 CDN 依赖。构建工具产出的资源要一起打包。网络数据用 ToolBox.network.request；不要依赖页面 fetch、WebSocket、iframe 或远程子资源绕过宿主网络权限。

图标、字体和图片应引用包内相对路径；不要使用电脑绝对路径。普通网页的文件选择器和摄像头直通不属于本接口，请使用 files.open 和 camera.capture。

## 基础接口

返回值、参数和调用方式，以本手册末尾附带的完整 TypeScript 声明为准。

### ready、提示、摘要与设备信息

ready() 返回 apiVersion、hostVersion、toolId 和 generation。generation 是当前运行版本/环境标识，不是应当长期保存的凭据。

ui.toast(message) 显示简短提示，message 最多 200 个字符。crypto.sha256(value) 接受字符串或 Uint8Array，返回 { hex }。device.getBasicInfo() 需要 device.basic，返回 apiLevel、locale、timeZone、screenClass，不包含设备标识。下方片段放在异步函数中执行：

```js
const ready = await ToolBox.ready();
await ToolBox.ui.toast("工具已就绪");
const digest = await ToolBox.crypto.sha256("hello");
const device = await ToolBox.device.getBasicInfo();
console.log(ready.apiVersion, digest.hex, device.screenClass);
```

### 普通存储与安全存储

storage.get(key) 返回保存的 JSON 值，键不存在时返回 null。set(key, value) 保存 JSON 值；remove(key) 删除单项；keys() 列出键；clear() 清空当前工具的普通存储。key 最多 128 个字符，不能用它存储函数、DOM 对象或未序列化的二进制数据。

storage.secure.get/set/remove 使用相同 JSON 值形式，但由 Android Keystore 保护。使用前声明 storage.secure。Token 由工具自己读取并加入网络 Header，不存在 credentialId 或宿主凭据管理接口。

普通存储在工具更新后保留；安全存储在关闭其授权、工具更新或删除时会清理，更新后需要重新输入 Token。临时文件令牌和 sessionId 不能作为可跨版本复用的数据保存。

```js
await ToolBox.storage.set("settings", { refreshSeconds: 60 });
const settings = await ToolBox.storage.get("settings");
const keys = await ToolBox.storage.keys();
await ToolBox.storage.remove("old-draft");

await ToolBox.storage.secure.set("api-token", "由用户输入的值");
const token = await ToolBox.storage.secure.get("api-token");
await ToolBox.storage.secure.remove("api-token");
```

### 剪贴板与触觉

clipboard.writeText(text) 需要 clipboard.write；clipboard.readText() 需要 clipboard.read，会触发宿主确认。haptics.perform(effect) 需要 haptics，只接受 click、confirm、reject。

复制与触觉默认开启仍以 manifest 已声明为前提。不要把下面按钮回调替换为页面加载时自动执行。读取剪贴板内容不要写入日志。

```js
document.getElementById("copy").addEventListener("click", async () => {
  try {
    await ToolBox.clipboard.writeText("结果：12.50");
    await ToolBox.haptics.perform("confirm");
  } catch (error) {
    document.getElementById("status").textContent =
      error.code + "：请检查工具权限，并重新点击按钮。";
  }
});
```

这一片段比最小模板多调用 haptics；使用时应在 permissions 中额外加入 {"name":"haptics","reason":"复制成功后给予触觉反馈"}。

## 网络请求

允许声明域名的公网 HTTPS。页面自己解析返回内容并处理 HTTP 状态。

### 网络声明与参数

声明 network 时必须提供 network.allowDomains。允许精确域名或 *.example.com 形式的子域通配；通配项不代表根域名。重定向目的地也必须满足域名声明、HTTPS 和地址检查。

下面是添加到 manifest 的相关字段，不是完整 manifest：

```json
{
  "permissions": [
    { "name": "network", "reason": "读取仓库公开状态" }
  ],
  "network": {
    "allowDomains": ["api.github.com"],
    "allowRedirects": true,
    "timeoutMs": 30000,
    "maxResponseBytes": 65536
  }
}
```

network.request 接受 url、method、headers、body、timeoutMs、maxResponseBytes。method 默认为 GET，另支持 POST、PUT、PATCH、DELETE、HEAD；GET/HEAD 不带请求体。

ToolBox 0.3.7 起，单次 HTTP 调用的总时限、读取等待与写入等待均采用请求 timeoutMs 和 manifest timeoutMs 中的较小值；请求未填时仍为 30000 毫秒。连接建立仍以 10 秒为上限，也受较短的调用总时限约束。需要等待 AI 等长响应时，同时声明并传入例如 300000（5 分钟），minHostVersion 至少填写 0.3.7；服务器主动报错或网络断开不会继续等待满 5 分钟。域名、重定向、地址与大小检查不变。

timeoutMs 可为 1000–600000 毫秒，maxResponseBytes 可为 1024–67108864 字节。manifest 网络默认超时 30000 毫秒、响应上限 4 MiB；读取上限取请求值、manifest 网络上限与消息上限的最小值，不预先按 Base64 比例缩小文本响应。消息默认 256 KiB，ToolBox 0.3.5 起可通过 limits.maxBridgePayloadBytes 声明 4096–8388608 字节（最高 8 MiB）；使用超过 1 MiB 的消息上限时，minHostVersion 请至少填写 0.3.5。宿主在返回前检查实际 JSON 编码后的总大小，JSON 转义或 Base64 膨胀也占消息空间；超出时返回 QUOTA_EXCEEDED。

不要把可配置的网络上限理解为可以一次把 64 MiB 数据塞回网页。大数据应由服务端分页，或分段请求并逐段处理。

### GET、JSON POST 与返回值

请求返回 { status, headers, body, bodyEncoding }，没有浏览器 Response 的 json() 方法。bodyEncoding 为 text 时 body 是文本，JSON 需自行 JSON.parse；为 base64 时应解码。4xx/5xx 返回真实 HTTP 状态，并不等于 Promise 必然抛错。

```js
const response = await ToolBox.network.request({
  url: "https://api.github.com/repos/gkeyes/ToolBox-Android",
  method: "GET",
  headers: { Accept: "application/vnd.github+json" },
  timeoutMs: 30000,
  maxResponseBytes: 65536
});
if (response.status < 200 || response.status >= 300) {
  throw new Error("HTTP " + response.status);
}
if (response.bodyEncoding !== "text") throw new Error("不是文本响应");
const repository = JSON.parse(response.body);
```

POST 示例中的 api.example.com 是占位地址。请替换为自己的 HTTPS 服务，并同步修改 manifest 域名；声明 storage.secure 后才可读取保存的 Token。

```js
const token = await ToolBox.storage.secure.get("api-token");
if (typeof token !== "string" || !token) throw new Error("请先设置 Token");
const response = await ToolBox.network.request({
  url: "https://api.example.com/v1/query",
  method: "POST",
  headers: {
    Authorization: "Bearer " + token,
    "Content-Type": "application/json"
  },
  body: { query: "hello" }
});
```

### Header、二进制与错误处理

Authorization、Cookie、X-API-Key、Accept、Content-Type 等普通 Header 可由页面传入；不会复用宿主其他页面的登录状态。Host、Content-Length、Connection、Transfer-Encoding、Upgrade、TE、Trailer 和 Proxy 系列协议 Header 由传输层控制，不能自行设置。

body 可以是字符串、JSON 值或 Uint8Array；字节请求仍受消息预算和请求体上限约束。Base64 响应解码示例：

```js
const bytes = Uint8Array.from(atob(response.body), char => char.charCodeAt(0));
```

只在 bodyEncoding === "base64" 时使用上述解码。不要记录 Header、Token、请求正文或响应正文来排查网络问题；记录错误码、HTTP 状态和操作步骤即可。

NETWORK_BLOCKED：检查错误提示中的域名声明、HTTPS、重定向目标及地址类型。私网、回环、保留地址和 IP 字面量被阻止。NETWORK_UNAVAILABLE：连接或读取响应失败；NETWORK_TIMEOUT：请求超时。这两类失败可以退避重试，不表示被安全策略阻止。HTTP 401/403：检查服务端 Token 与授权；HTTP 429：遵循服务端限额和重试时间。QUOTA_EXCEEDED：响应或编码后的消息过大，按提示提高对应 manifest 上限或分页读取，不要无限重试相同的超大请求。

## 后台与通知

持续运行环境与旧版后台任务不同；实时通知是持续会话上的一份展示状态。

### 会话、定时器与恢复事件

background.start(options?) 返回 { sessionId, startedAt, restoreAfterProcessDeath, restoreAfterReboot }。options 可开启 restoreAfterProcessDeath、restoreAfterReboot。重复 start 会返回当前环境的同一会话，不创建第二个网页。

background.status(sessionId) 返回会话或 null；listSessions() 列出当前工具的持续会话；stop(sessionId) 停止。这里不是旧任务列表 background.list()。

background.setTimer(key, intervalMs) 创建或更新同名计时器；key 最多 128 个字符，intervalMs 是大于 0 的安全整数毫秒。cancelTimer(key) 取消，不存在时会返回 NOT_FOUND。宿主没有把这个间隔当作严格实时调度保证。

通过 background.onTimer(listener) 接收 { key, firedAt }；通过 background.onRestore(listener) 接收 { reason, restoredAt }。二者返回取消订阅函数。先注册监听再完成 ready，避免依赖有限的早到事件缓冲。不要使用 window.addEventListener("background.timer") 代替这些公开监听接口。

离开运行页时只要会话仍活跃，环境会被保留；系统回收或重启后只是尝试重载，业务状态由页面自行保存和恢复。每连续运行 12 小时发送提醒，持续通知提供停止入口。关闭后台总开关不会在重新开启时偷偷恢复旧会话。

### 实时通知字段与回执

先通过 background.start 获取当前工具的 sessionId，再调用 notifications.live.start(request)。重复 start 幂等；update(request) 更新同一展示，但请求仍需包含 sessionId、title、primaryText，不是只提交变化字段。end(sessionId) 仅结束实时展示，不停止后台环境。

ToolBox 0.3.6 起，不同工具的持续会话分别显示独立通知卡，独立更新、打开和停止，不再合成一张卡。end 后后台仍在运行时，同一张卡退回普通后台状态。划掉、隐藏和降级遵循手机默认机制；超级岛实际同时展示的数量与排序由系统决定。公开调用和回执不变，现有工具不需要提高 minHostVersion 或重新打包。

必填：
- sessionId：当前工具、当前运行版本的会话。
- title：标题，最多 64 个字符。
- primaryText：主值，最多 32 个字符。

可选：
- secondaryText：最多 96 个字符；body：最多 256 个字符。
- shortText：最多 12 个字符，供状态栏或岛摘要使用。
- updatedAt：Unix 毫秒，不是秒。
- progress：0–100 的整数；不需要进度时省略。
- accentColor：只接受 #RRGGBB。
- tone：neutral、positive、negative 或 warning。

通知文字不允许控制字符；需要多项信息时用可读分隔符连接，不要塞入换行。长度按字符串长度计算，emoji 可能占两个单位。字段更新会由宿主在 500ms 窗口内合并，网页无需自行提高刷新频率。

```js
const session = await ToolBox.background.start();
const result = await ToolBox.notifications.live.start({
  sessionId: session.sessionId,
  title: "后台示例",
  primaryText: "运行中",
  secondaryText: "每 10 秒更新",
  shortText: "运行中",
  updatedAt: Date.now(),
  tone: "neutral",
  accentColor: "#1677E8"
});
```

result.standard 为 POSTED；androidLive 为 REQUESTED、UNAVAILABLE 或 NOT_ALLOWED；hyperOsIsland 为 REQUESTED 或 UNAVAILABLE；另有 hyperOsProtocolVersion 和 hyperOsPermissionReported。

REQUESTED 只说明数据已提交，不能证明系统真的显示了超级岛。普通持续通知是基础，Android Live 与 HyperOS 是增强；系统、权限、系统版本及用户外部模块共同影响最终展示。ToolBox 不提供白名单绕过接口。

### 可运行的后台通知流程

在最小工程基础上，保留原有权限并新增 background.runtime 和 notifications。到工具权限页开启它们，并确认设置中的后台总开关及系统通知权限已开启。

将下面两段分别加入 HTML 和保存为 background.js。HTML 的脚本仍是外链；此示例每 10 秒更新计数，不访问网络。普通网页浏览器无法执行它。

```html
<button id="start-background" type="button">开始后台</button>
<button id="stop-background" type="button" disabled>停止后台</button>
<p id="background-status" role="status" aria-live="polite">未启动</p>
<script src="background.js" defer></script>
```

```js
(function () {
  "use strict";
  const api = window.ToolBox;
  const startButton = document.getElementById("start-background");
  const stopButton = document.getElementById("stop-background");
  const status = document.getElementById("background-status");
  const timerKey = "demo-tick";
  let sessionId = null;
  let tick = 0;
  let busy = false;
  if (!api) {
    status.textContent = "请在 ToolBox 中运行";
    startButton.disabled = true;
    return;
  }

  function showError(error) {
    status.textContent = (error.code || "ERROR") + "：请检查工具权限和后台保障。";
  }
  async function publish(id) {
    try {
      await api.notifications.live.update({
        sessionId: id,
        title: "后台示例",
        primaryText: "第 " + tick + " 次更新",
        secondaryText: new Date().toLocaleTimeString("zh-CN"),
        shortText: String(tick).slice(0, 12),
        updatedAt: Date.now()
      });
      if (sessionId === id) status.textContent = "后台已更新 " + tick + " 次";
    } catch (error) {
      if (sessionId === id) showError(error);
    }
  }
  async function cleanup(id) {
    try {
      await api.background.cancelTimer(timerKey);
    } catch (error) {
      if (error.code !== "NOT_FOUND") showError(error);
    } finally {
      try {
        await api.notifications.live.end(id);
      } finally {
        await api.background.stop(id);
      }
    }
  }
  async function start() {
    if (busy || sessionId) return;
    busy = true;
    startButton.disabled = true;
    try {
      await api.ready();
      const session = await api.background.start({
        restoreAfterProcessDeath: true,
        restoreAfterReboot: true
      });
      sessionId = session.sessionId;
      try {
        await api.notifications.live.start({
          sessionId,
          title: "后台示例",
          primaryText: "正在启动",
          shortText: "启动中",
          updatedAt: Date.now()
        });
      } catch (error) { showError(error); }
      await api.background.setTimer(timerKey, 10000);
      await publish(sessionId);
    } catch (error) {
      const id = sessionId;
      sessionId = null;
      if (id) {
        try { await cleanup(id); } catch (stopError) { showError(stopError); }
      }
      showError(error);
    } finally {
      busy = false;
      startButton.disabled = sessionId !== null;
      stopButton.disabled = sessionId === null;
    }
  }
  async function stop() {
    if (busy || !sessionId) return;
    busy = true;
    stopButton.disabled = true;
    const id = sessionId;
    sessionId = null;
    try {
      await cleanup(id);
      status.textContent = "已停止后台";
    } catch (error) { showError(error); }
    finally {
      busy = false;
      startButton.disabled = false;
    }
  }
  api.background.onTimer(event => {
    if (event.key !== timerKey || !sessionId || busy) return;
    tick += 1;
    void publish(sessionId);
  });
  api.background.onRestore(() => { void start(); });
  startButton.addEventListener("click", () => { void start(); });
  stopButton.addEventListener("click", () => { void stop(); });
  void api.ready().catch(showError);
})();
```

这个计数仅为演示，进程重载会从零开始；需要保留业务状态时自行通过 storage 保存。监听器在本页面只注册一次；组件反复挂载时应调用 onTimer/onRestore 返回的取消订阅函数，避免重复回调。

验证顺序：开始后台 → 返回 ToolBox 工具列表 → 回到桌面或锁屏 → 观察计数变化 → 重新打开工具 → 停止后台。仅结束实时展示后，普通“后台运行”通知仍可能保留，这是后台环境还没停止。

加入真实网络轮询时，在计时回调内调用上一章的 network.request，设置请求进行中标志避免重叠；每次 await 返回后检查会话是否仍为本次会话，再更新通知。网络失败可以保留最近结果，但应显示数据时间和失败状态，不能伪装成最新数据。

### 普通通知与独立提醒

notifications.post(id, title, body) 发布普通通知；update(id, title, body) 更新同一工具内同一 id；cancel(id) 删除。id 最多 64 个字符，使用简短稳定标识，不要每次刷新都生成新 id。title 最多 64、body 最多 256 个字符，两者都不能为空。

普通通知不要求创建持续会话；在后台主动发送仍需要有效的持续环境或受支持的后台执行场景。点击通知由宿主打开所属工具，工具不传入任意 Android Intent。

```js
await ToolBox.notifications.post("result", "任务完成", "共处理 12 条数据");
await ToolBox.notifications.update("result", "任务完成", "共处理 14 条数据");
await ToolBox.notifications.cancel("result");
```

### WorkManager 后台任务

background.enqueue(spec) 返回 taskId；schedulePeriodic(spec) 增加 intervalMinutes。spec 包含 key、operation 和可选 constraints.network（none 或 connected）。

operation 只有 httpGet 与 notify。httpGet 仍需 network 声明和目标域名；notify 仍需 notifications。这是委托给原生系统的固定任务，不会执行任意 JS。每工具最多 8 个活动任务、4 个周期任务，周期最短 15 分钟；这些是旧任务模型的约束，不适用于持续运行会话数量。

```js
const taskId = await ToolBox.background.enqueue({
  key: "example-request",
  operation: {
    type: "httpGet",
    url: "https://api.github.com/repos/gkeyes/ToolBox-Android"
  },
  constraints: { network: "connected" }
});
const tasks = await ToolBox.background.list();
const result = await ToolBox.background.getResult(taskId);
await ToolBox.background.cancel(taskId);
```

TaskState 为 QUEUED、RUNNING、COMPLETED、CANCELLED；RunOutcome 为 SUCCEEDED、FAILED、CANCELLED。尚无结果时 getResult 返回 null，周期任务返回最近一次结果。httpGet 仅 2xx 成功；4xx 本次失败，网络/超时/5xx 最多退避重试三次。后台任务结果最大 256 KiB，保留 7 天。

## 系统能力

文件、相机、分享等均通过 ToolBox 接口打开真实系统界面。

### 文件选择、读取与保存

files.open(mimeTypes?) 打开 SAF 文件选择器，取消时返回 null，否则返回 { token, name, mimeType, size }。files.read(token) 返回 Uint8Array，并消耗一次性令牌。令牌仅属于当前运行环境，不能持久化为文件路径或跨工具传递。

files.save(suggestedName, mimeType, content) 让用户选择保存位置；content 为字符串或 Uint8Array，取消同样返回 null。不会申请广泛存储权限，也不会永久保存 URI 授权。

必须由用户点击触发；读取令牌没有第二次确认。文件大小还受消息/Base64 预算限制，不要用单次调用读取大型视频或任意大文件。

```js
document.getElementById("open-file").addEventListener("click", async () => {
  try {
    const file = await ToolBox.files.open(["text/plain", "application/json"]);
    if (!file) return;
    const bytes = await ToolBox.files.read(file.token);
    document.getElementById("file-content").textContent = new TextDecoder().decode(bytes);
  } catch (error) {
    document.getElementById("status").textContent = error.code + "：文件读取未完成";
  }
});
document.getElementById("save-file").addEventListener("click", async () => {
  try {
    const file = await ToolBox.files.save("result.txt", "text/plain", "Hello ToolBox");
    document.getElementById("status").textContent = file ? "已保存" : "已取消";
  } catch (error) {
    document.getElementById("status").textContent = error.code + "：文件保存未完成";
  }
});
```

上面片段需要自行添加 open-file、save-file 按钮及 file-content 显示区域，并声明 files.open、files.save。不要在同一次示例执行中自动连续打开多个系统界面。

### 相机、分享与快捷方式

camera.capture() 打开系统拍照界面，返回 FileToken 或 null；需要读取时调用 files.read。不会给 WebView 摄像头直通权限，大照片仍受读取预算限制。

share.text(text) 打开系统 Sharesheet；当前公开接口仅分享文本，不要传任意 Intent 或假设支持文件分享。

shortcuts.pin(name?) 请求固定当前工具的桌面快捷方式，返回 boolean。系统/桌面可能拒绝或需要用户确认，不能把提交请求当作已经固定成功。

各操作分别声明 camera、share、shortcuts，在真实点击回调中调用：

```js
const photo = await ToolBox.camera.capture();
if (photo) {
  const imageBytes = await ToolBox.files.read(photo.token);
}

await ToolBox.share.text("来自我的工具的结果");
const accepted = await ToolBox.shortcuts.pin("我的工具");
```

以上是三个独立操作的调用形式，不应把整段绑定到一次点击连续弹出三个系统界面。

### 一次定位与位置监听

location.getCurrent(accuracy?, timeoutMs?) 返回 { latitude, longitude, accuracyMeters, capturedAt }。accuracy 为 coarse 或 precise，默认 coarse；timeoutMs 默认 10000，允许 1000–30000 毫秒。没有可用位置时会返回 typed error，而不是虚假坐标。

location.watch(options?) 返回 watchId。options 支持 accuracy、intervalMs、minDistanceMeters、allowBackground；更新由 location.onChanged(listener) 接收，其事件在位置字段外包含 watchId。clearWatch(watchId) 停止监听；onChanged 返回的函数只取消 JS 订阅，不等于停止原生监听。

允许后台定位需要 location、location.background、background.runtime 及相应 Android 权限/前台服务条件。宿主只透传位置，不保存轨迹或计算路线；业务记录由页面自己决定。

```js
const fix = await ToolBox.location.getCurrent("coarse", 10000);
let watchId = null;
const unsubscribe = ToolBox.location.onChanged(event => {
  if (event.watchId !== watchId) return;
  document.getElementById("location").textContent =
    event.latitude + ", " + event.longitude;
});
watchId = await ToolBox.location.watch({
  accuracy: "precise",
  intervalMs: 5000,
  minDistanceMeters: 10,
  allowBackground: false
});

async function stopWatching() {
  if (watchId) await ToolBox.location.clearWatch(watchId);
  watchId = null;
  unsubscribe();
}
```

5000 毫秒是此例选择的间隔，不是宿主规定的产品范围；实际更新时机由系统位置服务决定。一次性 getCurrent 只用于前台。

### 精确闹钟

alarms.schedule({ id, triggerAt }) 返回 { id, triggerAt, scheduledAt }；triggerAt 为将来的 Unix 毫秒。list() 返回登记列表，cancel(id) 取消。需要 alarms 授权和系统精确闹钟权限。

运行环境存在时，alarms.onAlarm(listener) 接收 { id, triggerAt, scheduledAt, firedAt }；没有运行环境时宿主发送普通通知，点击后打开对应工具。监听返回取消订阅函数。

宿主仅持久化调度标识与时间，不接受任意业务 payload。业务含义可由页面以 id 为键保存到 storage。不要把收到普通通知当成网页已在后台执行了业务代码。

```js
const unsubscribe = ToolBox.alarms.onAlarm(event => {
  document.getElementById("status").textContent = "闹钟触发：" + event.id;
});
const alarm = await ToolBox.alarms.schedule({
  id: "reminder-1",
  triggerAt: Date.now() + 60000
});
const alarms = await ToolBox.alarms.list();

async function cancelReminder() {
  await ToolBox.alarms.cancel("reminder-1");
  unsubscribe();
}
```

### 高性能计算与 Worker

大量计算放在随包安装的同源 worker.js 中；页面主线程只负责输入、渲染和 ToolBox 调用。worker 不能直接访问 ToolBox，计算结果用 postMessage 返回顶层页面。

远程、blob、data Worker 与 ServiceWorker 不可用。把 worker.js 和它引用的包内文件一起打包；通用打包器会递归包含这些资源。不要把未经信任的返回文本赋给 innerHTML。

```js
const worker = new Worker("worker.js");
worker.onmessage = ({ data }) => {
  document.getElementById("result").textContent = String(data.sum);
};
worker.postMessage({ values: [1, 2, 3, 4] });
```

```js
self.onmessage = ({ data }) => {
  const sum = data.values.reduce((total, value) => total + value, 0);
  self.postMessage({ sum });
};
```

第一段放 app.js，第二段保存为 worker.js；不再需要时调用 worker.terminate()。同时避免重叠的网络轮询、每次计时器都重建整页 DOM，以及在每秒更新中重复读取全部存储。

## 打包与排错

打包器、完整接口与导入规则可以直接复制；不用阅读宿主实现才能继续。

### 通用打包器

仓库命令为 python3 scripts/package-tool.py 源目录 输出.tbx。它递归收集资源，忽略 .git、.DS_Store、__MACOSX、node_modules、__pycache__，重新生成 integrity.json，并使用稳定排序与时间戳。

这是源码目录的打包器，不是前端编译器：React/Vue 等项目先生成静态产物，再打包产物目录。不要把 node_modules、APK、其他压缩包或构建缓存塞进 .tbx。

只打包未签名包；旧 integrity.json 和 signature.json 不会原样保留。未签名工具可以正常导入。打包器仅做基础结构检查，最终以宿主导入检查为准，不能把“打包成功”当作全部权限或功能已经验证。

没有仓库时，可把下列完整代码保存为 package-tool.py，然后执行 python3 package-tool.py ./my-tool ./my-tool.tbx。

```python scripts/package-tool.py
#!/usr/bin/env python3
"""Package a static web directory as a reproducible, unsigned ToolBox .tbx."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
import unicodedata
import zipfile


IGNORED = {".DS_Store", "__MACOSX", ".git", "__pycache__", "node_modules"}
GENERATED = {"integrity.json", "signature.json"}
ARCHIVES = {".zip", ".tbx", ".apk", ".jar", ".aar", ".7z", ".rar", ".tar", ".gz"}


def package_tool(source, destination, overwrite=False):
    source = Path(source).absolute()
    destination = Path(destination).absolute()
    if source.is_symlink() or not source.is_dir():
        raise ValueError("Source must be a real directory, not a symbolic link")
    source = source.resolve()
    destination = destination.parent.resolve() / destination.name
    if destination == source or source in destination.parents:
        raise ValueError("Keep the output .tbx outside the source directory")
    if destination.suffix.lower() != ".tbx":
        raise ValueError("Output filename must end in .tbx")
    if destination.exists() and not overwrite:
        raise FileExistsError("Output exists; choose another name or pass --overwrite")
    if destination.is_symlink() or destination.is_dir():
        raise ValueError("Output must not be a directory or symbolic link")

    entries = {}
    normalized_names = set()
    for directory, directories, filenames in os.walk(source, followlinks=False):
        directories[:] = sorted(name for name in directories if name not in IGNORED)
        for name in directories:
            if (Path(directory) / name).is_symlink():
                raise ValueError(f"Symbolic link is not allowed: {name}")
        for name in sorted(filenames):
            if name in IGNORED:
                continue
            path = Path(directory) / name
            relative = path.relative_to(source).as_posix()
            if relative in GENERATED:
                continue
            if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode):
                raise ValueError(f"Not a regular file: {relative}")
            if "\\" in relative or any(ord(char) < 32 for char in relative):
                raise ValueError(f"Invalid resource path: {relative}")
            if path.suffix.lower() in ARCHIVES:
                raise ValueError(f"Remove nested archives: {relative}")
            normalized = unicodedata.normalize("NFC", relative).casefold()
            if normalized in normalized_names:
                raise ValueError(f"Case or Unicode path collision: {relative}")
            normalized_names.add(normalized)
            entries[relative] = path.read_bytes()

    if "manifest.json" not in entries:
        raise ValueError("Missing manifest.json at the source root")
    manifest = json.loads(entries["manifest.json"].decode("utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("manifest.json must be an object")
    entry = manifest.get("entry")
    if not isinstance(entry, str) or not entry.endswith(".html") or entry not in entries:
        raise ValueError("manifest.entry must name an existing .html file")
    icon = manifest.get("icon")
    if icon is not None and (not isinstance(icon, str) or icon not in entries):
        raise ValueError("manifest.icon must name an existing resource")

    integrity = {
        "schemaVersion": 1,
        "algorithm": "SHA-256",
        "files": {name: hashlib.sha256(entries[name]).hexdigest() for name in sorted(entries)},
    }
    entries["integrity.json"] = (json.dumps(integrity, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, suffix=".tmp", delete=False) as handle:
            temporary = Path(handle.name)
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in sorted(entries):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                archive.writestr(info, entries[name], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
        if overwrite:
            os.replace(temporary, destination)
        else:
            os.link(temporary, destination)
        return digest
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="Directory containing manifest.json")
    parser.add_argument("output", help="Output .tbx outside the source directory")
    parser.add_argument("--overwrite", action="store_true", help="Replace the specified output file")
    args = parser.parse_args()
    try:
        digest = package_tool(args.source, args.output, args.overwrite)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Cannot package tool: {error}\n")
    print(f"{digest}  {args.output}")


if __name__ == "__main__":
    main()
```

### 包结构、完整性与更新

ZIP 根部应直接出现 manifest.json 和入口文件，不要多包一层 my-tool/。资源路径大小写必须一致，不能存在符号链接、路径碰撞、上级路径、嵌套压缩包或原生/动态代码负载。

完整性文件使用 SHA-256 对原始文件字节逐项计算；不把 integrity.json 或 signature.json 本身列入 files。资源发生任何改动都应重新打包，不要在打包后直接替换 ZIP 内文件。

```json
{
  "schemaVersion": 1,
  "algorithm": "SHA-256",
  "files": {
    "manifest.json": "这里由打包器生成实际的 64 位十六进制摘要",
    "index.html": "这里由打包器生成实际的 64 位十六进制摘要"
  }
}
```

上面只展示格式，文字占位不是有效摘要；实际文件清单由打包器生成。存在完整性清单时必须与包内容一致。没有签名不需要额外审核或申请发行资格。

更新同一工具：保持 id 不变，同时提高 version 和 versionCode，再重新导入。相同或更低版本会被拒绝。更新会停止旧运行环境、任务和通知；普通 KV 保留，旧权限按新 manifest/默认策略重新建立，安全存储和临时令牌清理。删除会同时清理代码、数据、授权、任务和通知。

### 错误码与定位顺序

先区分“导入失败”和“接口调用失败”。导入失败时检查 ZIP 根目录、manifest、入口、版本和完整性；接口失败时优先查看 error.code，不要只显示“出错了”。

- UNSUPPORTED：当前宿主或系统不支持方法，检查 hostVersion 与公开接口。
- INVALID_REQUEST：参数名称、类型、长度或数值范围不正确；不要添加未公开参数。
- NOT_DECLARED：manifest.permissions 缺少对应能力，补声明后提高版本并重新打包。
- PERMISSION_DENIED：到该工具权限页打开开关；不能仅在 Android 设置中授权。
- SYSTEM_PERMISSION_DENIED：允许 ToolBox 系统权限，或从“后台保障”进入对应设置。
- USER_GESTURE_REQUIRED：重新点击网页按钮并及时调用，不要在加载、后台或长耗时之后调用手势能力。
- INVALID_SESSION、SESSION_ENDED：会话已失效，重新 ready 并获取当前 sessionId；不要复用旧版本标识。
- WRONG_ORIGIN、NOT_MAIN_FRAME：只在当前工具顶层页面调用，不要从 iframe、worker 或任意网站调用。
- NETWORK_BLOCKED：检查 manifest 域名、HTTPS、端口、重定向目标和地址类型。
- NETWORK_UNAVAILABLE、NETWORK_TIMEOUT：连接、读取失败或请求超时，检查网络并退避重试；与权限或域名阻止不同。
- RATE_LIMITED：降低调用频率并等待，不要即时无限重试。
- QUOTA_EXCEEDED：缩小消息、文件或响应，分页处理，并检查普通存储配额。
- BUSY：当前系统交互或操作尚未完成，等结束后再试。
- DUPLICATE_TASK：后台任务 key 已存在，复用已有任务或选择新的业务标识。
- CANCELLED：用户或系统取消了操作，页面应回到可继续操作的状态。
- NOT_FOUND：键以外的任务、会话资源或文件令牌不存在；文件令牌只能读取一次。
- INTERNAL_ERROR：保留错误码、操作路径、宿主/工具版本，再提供截图反馈；不要附带 Token 或私人文件内容。

空白页还要检查 index.html 的 charset/viewport、相对资源路径、外链脚本及 CSP 限制。普通浏览器能显示页面，不代表宿主已授予网络、文件或后台权限。

### 四个内置范例怎么参考

点击本页“安装四个范例”会走与外部 .tbx 相同的导入器，不会自动开启默认关闭的能力。

- 仓位计算器：examples/position-calculator，参考 storage、clipboard.write、haptics。
- 快速笔记：examples/quick-notes，参考数据增删改、重开恢复和复制。
- 后台任务演示：examples/background-task-demo，参考 background.tasks、HTTPS GET、普通通知与任务结果。
- 通知实验室：examples/notification-lab，参考持续会话、普通/实时通知、恢复、计时更新与增强回执。

它们是可运行范例，不代表所有能力无需授权。不要为了制作自己的工具直接修改四个内置范例；复制模板到新目录并使用自己的工具 id。examples 中独立交付的其他工具不是默认内置范例。

### TypeScript 完整接口

下面内容与 sdk/toolbox-api.d.ts 一致，可以复制用于编辑器提示或交给开发助手。它完整列出当前方法、参数对象、返回结果与事件监听签名；不是要放进 script 标签执行的 JavaScript。

除事件订阅外，原生接口返回 Promise；订阅接口返回取消订阅函数。示例代码中的 await 应放在 async 函数或真正的 ES module 中，不要把它直接放进普通 script 的顶层。

```ts sdk/toolbox-api.d.ts
export type ToolBoxContractSha256 = "a4753d4287ac9b4a35faee65ef2f06109cb89bfe434c52e8c60cbe3551dea352";

export type ToolBoxCapability =
  | "storage"
  | "storage.secure"
  | "clipboard.write"
  | "clipboard.read"
  | "share"
  | "files.open"
  | "files.save"
  | "network"
  | "device.basic"
  | "haptics"
  | "notifications"
  | "shortcuts"
  | "camera"
  | "location"
  | "background.tasks"
  | "background.runtime"
  | "location.background"
  | "alarms";

export type ToolBoxMethodName =
  | "ready"
  | "ui.toast"
  | "crypto.sha256"
  | "storage.get"
  | "storage.set"
  | "storage.remove"
  | "storage.keys"
  | "storage.clear"
  | "storage.secure.get"
  | "storage.secure.set"
  | "storage.secure.remove"
  | "device.getBasicInfo"
  | "haptics.perform"
  | "clipboard.writeText"
  | "network.request"
  | "notifications.post"
  | "notifications.update"
  | "notifications.cancel"
  | "notifications.live.start"
  | "notifications.live.update"
  | "notifications.live.end"
  | "background.enqueue"
  | "background.schedulePeriodic"
  | "background.start"
  | "background.stop"
  | "background.status"
  | "background.list"
  | "background.listSessions"
  | "background.getResult"
  | "background.cancel"
  | "background.setTimer"
  | "background.cancelTimer"
  | "clipboard.readText"
  | "share.text"
  | "files.open"
  | "files.read"
  | "files.save"
  | "shortcuts.pin"
  | "camera.capture"
  | "location.getCurrent"
  | "location.watch"
  | "location.clearWatch"
  | "alarms.schedule"
  | "alarms.list"
  | "alarms.cancel";

export type ToolBoxErrorCode =
  | "UNSUPPORTED"
  | "INVALID_REQUEST"
  | "INVALID_SESSION"
  | "WRONG_ORIGIN"
  | "NOT_MAIN_FRAME"
  | "NOT_DECLARED"
  | "PERMISSION_DENIED"
  | "SYSTEM_PERMISSION_DENIED"
  | "USER_GESTURE_REQUIRED"
  | "BUSY"
  | "RATE_LIMITED"
  | "QUOTA_EXCEEDED"
  | "CANCELLED"
  | "SESSION_ENDED"
  | "NOT_FOUND"
  | "DUPLICATE_TASK"
  | "NETWORK_BLOCKED"
  | "NETWORK_UNAVAILABLE"
  | "NETWORK_TIMEOUT"
  | "INTERNAL_ERROR";

export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };

export interface ToolBoxApiError {
  code: ToolBoxErrorCode;
  message: string;
}

export interface ReadyResult {
  apiVersion: "1.0";
  hostVersion: string;
  toolId: string;
  generation: string;
}

export interface Sha256Result {
  hex: string;
}

export interface BasicDeviceInfo {
  apiLevel: number;
  locale: string;
  timeZone: string;
  screenClass: "compact" | "medium" | "expanded";
}

export type HapticEffect = "click" | "confirm" | "reject";

export interface NetworkRequest {
  readonly url: string;
  readonly method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD";
  readonly headers?: Readonly<Record<string, string>>;
  readonly body?: string | JsonValue | Uint8Array;
  /** 1000–600000 ms; defaults to 30000 and is capped by the manifest. Host 0.3.7+ applies this budget to call, read and write waits; connection establishment remains bounded to 10 seconds. */
  readonly timeoutMs?: number;
  readonly maxResponseBytes?: number;
}

export interface NetworkResponse {
  readonly status: number;
  readonly headers: Readonly<Record<string, string>>;
  readonly body: string;
  readonly bodyEncoding: "text" | "base64";
}

export type LiveNotificationTone = "neutral" | "positive" | "negative" | "warning";

export interface LiveNotificationRequest {
  readonly sessionId: string;
  readonly title: string;
  readonly primaryText: string;
  readonly secondaryText?: string;
  readonly body?: string;
  readonly shortText?: string;
  readonly updatedAt?: number;
  readonly progress?: number;
  readonly accentColor?: string;
  readonly tone?: LiveNotificationTone;
}

export interface LiveNotificationResult {
  readonly standard: "POSTED";
  readonly androidLive: "REQUESTED" | "UNAVAILABLE" | "NOT_ALLOWED";
  readonly hyperOsIsland: "REQUESTED" | "UNAVAILABLE";
  readonly hyperOsProtocolVersion: number;
  readonly hyperOsPermissionReported: boolean;
}

export interface FileToken {
  token: string;
  name: string;
  mimeType: string;
  size: number;
}

export interface FileReadResult {
  base64: string;
}

export interface TaskConstraints {
  network?: "none" | "connected";
}

export interface HttpGetTaskOperation {
  type: "httpGet";
  url: string;
}

export interface NotifyTaskOperation {
  type: "notify";
  title: string;
  body: string;
}

export type BackgroundTaskOperation = HttpGetTaskOperation | NotifyTaskOperation;

export interface BackgroundTaskSpec {
  key: string;
  operation: BackgroundTaskOperation;
  constraints?: TaskConstraints;
}

export interface PeriodicTaskSpec extends BackgroundTaskSpec {
  intervalMinutes: number;
}

export type TaskState = "QUEUED" | "RUNNING" | "COMPLETED" | "CANCELLED";
export type RunOutcome = "SUCCEEDED" | "FAILED" | "CANCELLED";

export interface TaskSummary {
  readonly kind?: "task";
  readonly taskId: string;
  readonly key: string;
  readonly state: TaskState;
  readonly periodic: boolean;
  readonly nextRunAt?: number;
}

export interface BackgroundStartOptions {
  readonly restoreAfterProcessDeath?: boolean;
  readonly restoreAfterReboot?: boolean;
}

export interface BackgroundSessionSummary {
  readonly sessionId: string;
  readonly startedAt: number;
  readonly restoreAfterProcessDeath: boolean;
  readonly restoreAfterReboot: boolean;
}

export interface BackgroundRestoreEvent {
  readonly reason: "process" | "reboot";
  readonly restoredAt: number;
}

export interface BackgroundTimerEvent {
  readonly key: string;
  readonly firedAt: number;
}

export interface TaskRunResult {
  taskId: string;
  outcome: RunOutcome;
  completedAt: number;
  status?: number;
  body?: string;
  error?: ToolBoxApiError;
}

export interface LocationResult {
  readonly latitude: number;
  readonly longitude: number;
  readonly accuracyMeters: number;
  readonly capturedAt: number;
}

export interface LocationWatchOptions {
  readonly accuracy?: "coarse" | "precise";
  readonly intervalMs?: number;
  readonly minDistanceMeters?: number;
  readonly allowBackground?: boolean;
}

export interface LocationChangedEvent extends LocationResult {
  readonly watchId: string;
}

export interface AlarmScheduleOptions {
  readonly id: string;
  readonly triggerAt: number;
}

export interface AlarmSummary extends AlarmScheduleOptions {
  readonly scheduledAt: number;
}

export interface AlarmEvent extends AlarmSummary {
  readonly firedAt: number;
}

export interface ToolBoxApi {
  ready(): Promise<ReadyResult>;
  ui: {
    toast(message: string): Promise<void>;
  };
  crypto: {
    sha256(value: string | Uint8Array): Promise<Sha256Result>;
  };
  storage: {
    get(key: string): Promise<JsonValue | null>;
    set(key: string, value: JsonValue): Promise<void>;
    remove(key: string): Promise<void>;
    keys(): Promise<string[]>;
    clear(): Promise<void>;
    secure: {
      get(key: string): Promise<JsonValue | null>;
      set(key: string, value: JsonValue): Promise<void>;
      remove(key: string): Promise<void>;
    };
  };
  device: {
    getBasicInfo(): Promise<BasicDeviceInfo>;
  };
  haptics: {
    perform(effect: HapticEffect): Promise<void>;
  };
  clipboard: {
    writeText(text: string): Promise<void>;
    readText(): Promise<string>;
  };
  network: {
    request(request: NetworkRequest): Promise<NetworkResponse>;
  };
  notifications: {
    post(id: string, title: string, body: string): Promise<void>;
    update(id: string, title: string, body: string): Promise<void>;
    cancel(id: string): Promise<void>;
    live: {
      start(request: LiveNotificationRequest): Promise<LiveNotificationResult>;
      update(request: LiveNotificationRequest): Promise<LiveNotificationResult>;
      end(sessionId: string): Promise<void>;
    };
  };
  background: {
    enqueue(spec: BackgroundTaskSpec): Promise<string>;
    schedulePeriodic(spec: PeriodicTaskSpec): Promise<string>;
    start(options?: BackgroundStartOptions): Promise<BackgroundSessionSummary>;
    stop(sessionId: string): Promise<void>;
    status(sessionId: string): Promise<BackgroundSessionSummary | null>;
    list(): Promise<TaskSummary[]>;
    listSessions(): Promise<BackgroundSessionSummary[]>;
    getResult(taskId: string): Promise<TaskRunResult | null>;
    cancel(taskId: string): Promise<void>;
    setTimer(key: string, intervalMs: number): Promise<void>;
    cancelTimer(key: string): Promise<void>;
    onRestore(listener: (event: BackgroundRestoreEvent) => void): () => void;
    onTimer(listener: (event: BackgroundTimerEvent) => void): () => void;
  };
  share: {
    text(text: string): Promise<void>;
  };
  files: {
    open(mimeTypes?: string[]): Promise<FileToken | null>;
    read(token: string): Promise<Uint8Array>;
    save(suggestedName: string, mimeType: string, content: string | Uint8Array): Promise<FileToken | null>;
  };
  shortcuts: {
    pin(name?: string): Promise<boolean>;
  };
  camera: {
    capture(): Promise<FileToken | null>;
  };
  location: {
    getCurrent(accuracy?: "coarse" | "precise", timeoutMs?: number): Promise<LocationResult>;
    watch(options?: LocationWatchOptions): Promise<string>;
    clearWatch(watchId: string): Promise<void>;
    onChanged(listener: (event: LocationChangedEvent) => void): () => void;
  };
  alarms: {
    schedule(options: AlarmScheduleOptions): Promise<AlarmSummary>;
    list(): Promise<AlarmSummary[]>;
    cancel(id: string): Promise<void>;
    onAlarm(listener: (event: AlarmEvent) => void): () => void;
  };
}

declare global {
  interface Window {
    ToolBox: ToolBoxApi;
  }
}
```
