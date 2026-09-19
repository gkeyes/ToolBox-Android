# ToolBox 小工具开发帮助

ToolBox 在工具顶层网页注入 window.ToolBox。下面保留可复制的最小工程和完整接口；普通浏览器没有这些原生能力。

## 创建与导入

### 最小工程

把下面四段代码保存为对应文件，修改 manifest 的 id、名称和版本。id 是工具数据的稳定身份，更新时保持不变。

```json manifest.json
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

```html index.html
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

```css style.css
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

```js app.js
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

### 打包与更新

在仓库根目录运行：

```sh
python3 scripts/package-tool.py ./my-tool ./my-tool-v1.0.0.tbx
```

React 等项目先构建静态产物，再打包静态目录。输出应在源目录外；已有同名输出只在明确需要覆盖时使用 --overwrite。资源变化后重新打包，完整性清单由打包器生成。

在 ToolBox 中导入 .tbx。更新时提高 versionCode；未签名工具更新、同版本覆盖或降级需在宿主确认。同签名身份的更高版本可直接更新。无效签名、路径穿越、符号链接、内容篡改及不支持的原生代码不会安装。安装失败保留旧版本。

### 声明格式

manifest 必填 schemaVersion、id、name、version、versionCode、entry、apiVersion、minHostVersion、permissions、securityProfile；格式见构建使用的 schema/manifest.schema.json。entry 和 icon 是包内相对路径，strict 工具使用外链本地脚本，不执行远程脚本或 eval。

SDK 自动注入，不要把类型声明放进 script 标签。四个内置范例可从本页安装：仓位计算器、快速笔记、后台任务演示、通知实验室；它们使用相同的导入器和工具权限。

## 权限与运行

### 能力开关

先在 manifest.permissions 声明，再由用户在工具权限页控制。接口没有隐式授权方法；工具授权与 Android 系统权限分别生效。新增能力默认关闭，已有授权在同身份更新中按声明继承。关闭安全存储权限或卸载工具会清理相应安全数据。

需要前台交互的剪贴板、分享、浏览器、文件选择、相机、快捷方式和震动，由当前可见且有窗口焦点的工具发起。授权后没有触屏倒计时或每分钟调用额度。系统文件选择器、相机、分享及桌面确认仍由 Android 处理。

网络通过 ToolBox 原生 HTTPS 接口，不依赖域名白名单；TLS 验证和跨源重定向凭据剥离继续生效。旧 allowDomains/allowUserDomains/allowRedirects 只供旧包兼容。外部网页、iframe 和 Worker 不获得 ToolBox bridge。

### 数据与资源

不设置统一的包大小、解压大小、笔记条数或持续任务时长额度。宿主在分配和写入前检查当前进程可用堆与目标磁盘空间，安装仍保持事务与回滚。调用方明确填写的网络请求预算继续生效；旧 limits 字段仅兼容解析，不再施加额度。新工具不必填写 limits 或 network 预算。

大网络响应使用 openStream/readStream 逐块消费，处理完一块再读取下一块；每块长度只是传输单位，不是总数据上限。openStream 支持 AbortSignal。EOF、取消、撤权和运行环境结束释放连接。

网络未指定 timeoutMs 时不施加宿主总时限；0 表示关闭调用时限。调用方明确指定的超时用于该次请求。request 返回完整正文，files.read 返回完整 Uint8Array；这两种一次性返回仍须能够放入当前可用内存，并不意味着可以一次读取任意大的文件。超出实际资源时应显示错误。网络可改用 openStream 分块；files.read 暂无文件分块接口，应选择可放入内存的文件，或由工具自行提供分页数据。

安装支持 ZIP32 和 ZIP64；复制、目录扫描、解压、哈希及暂存复制均分块处理，依据目标卷可用空间、当前可用堆和系统内存压力检查资源，暂存占用也计入。高压缩比本身不会导致拒绝；CRC、中央目录、本地头、数据描述符及实际输出须一致。integrity.json 流式逐项核对文件集合和原始字节哈希；重复键、路径碰撞或篡改会失败，签名仍针对完整性文件原始字节验证。

导入反馈提供“取消”。原子提交前取消或失败会清理临时内容并保留旧工具；提交开始后完成提交或回滚，再显示实际最终结果，已成功安装不会误报为“已取消”。空间不足时释放设备空间再重试，系统内存压力较高时稍后重试。

普通及安全存储写入保持原子性，不自动淘汰用户数据。storage.getMany 保持请求键顺序；storage.apply 的写删冲突或非法值使整批失败。文件 token 属于当前运行环境，只能读取一次，不能作为长期文件路径保存。

### 首页、收藏与分组（0.8.0 起）

宿主分为首页、全部工具和设置。首页顶部显示最近使用，下方是收藏与可展开分组；同一工具可同时属于多个分组。编辑模式可拖动收藏、组和组内工具，也可用前移/后移按钮调整顺序。删除组不会卸载工具。全部工具可搜索，并按名称（中文拼音）、首次安装时间或最后打开时间排序；更新不会重置首次安装时间。最后打开记录宿主接受打开请求的时间，不代表网页已加载完成。

收藏、分组和排序保存在宿主，重启及工具更新后保留，不新增工具权限。备份包含布局；旧备份没有布局时保留本机关系并按最终工具列表清理失效引用。不设收藏、组或成员数量配额。

### 兼容性与仍保留的边界

原生网络使用系统 HTTP 代理选择器，系统代理变更对后续请求生效；不提供代理凭据管理，认证代理返回 407 时由工具给出明确提示。此行为不改变 Android VPN/TUN 路由。跨来源重定向保留明确非凭据的标准协商、Range 和缓存条件头，删除 Authorization、Cookie 及未分类自定义头，并删除失效的消息体和连接专用头；回跳不恢复已删除凭据。原生 API 暂无按工具保存的 CookieJar，不保存响应 Set-Cookie，也不把浏览器 Cookie 自动带入工具请求。

重定向请求头处置如下（大小写不敏感）：

| 时机 | 字段 | 行为 |
| --- | --- | --- |
| 所有请求 | Connection、Keep-Alive、Proxy-Authenticate、Proxy-Authorization、TE、Trailer、Transfer-Encoding、Upgrade、Host、Content-Length，以及 Connection 点名的字段 | 由宿主/HTTP 客户端管理，不转发调用方值 |
| 跨来源 | Accept、Accept-Encoding、Accept-Language、Range、If-Range、If-Match、If-None-Match、If-Modified-Since、If-Unmodified-Since、Cache-Control、Pragma、User-Agent、Content-Type、Content-Language、Content-Encoding | 保留 |
| 跨来源 | Authorization、Cookie、X-API-Key、其他自定义或未分类字段、Origin、Referer | 删除；后续回跳也不恢复 |
| 301/302/303 将带正文的方法改为 GET | Content-Type、Content-Length、Content-Encoding、Content-Language、Content-Location、Digest | 随正文一起删除 |

可见工具的 alert、confirm、prompt 使用原生对话框，显示工具身份。页面退出、导航、宿主暂停或运行环境结束会结束未完成的对话框；prompt 可返回空串，取消返回 null。JS 对话框不新增次数限制。剪贴板、分享及通知正文允许空串和换行，路径、标识及请求头仍遵守各自格式。

HTML 入口不要求以 doctype 或 html 标签起始，可以包含 BOM、注释及前导空白；文件路径与原生载荷、归档内容检查仍保留。图标支持设备能解码的位图，动图显示静态首帧；SVG 使用 AndroidSVG 1.4 的静态能力，允许纯本地引用、裁剪、蒙版及样式，不执行脚本或读取外部资源，不承诺渲染器未实现的滤镜。

媒体自动播放仍要求用户手势，这是防打扰策略。宿主备份目前只接受本地文档位置；尚未提供云文档备份语义。缺少独立 WebView profile 能力时使用无状态隔离，标准网页存储不可用，可使用已授权的 ToolBox 存储接口。HTTPS、精确来源、能力授权、签名与原生载荷检查继续生效。

独立后台任务可并行；每次执行有独立宿主身份，取消或更新后到达的旧结果不会覆盖新结果。瞬时错误最多自动重试 3 次（总计最多 4 次尝试），并遵守 WorkManager 调度；一个工具的持续 runtime 会话会复用，不代表整个宿主只能运行一个工具。

### WebAssembly 与二进制资源

strict、compat 均可使用 WebView 的标准 WebAssembly API，无需添加 manifest 字段、权限或签名。可以安装 .wasm、大小写后缀、无后缀及以 .so 命名的 Wasm；内容检查仍拒绝 ELF、DEX、class 和伪装的嵌套压缩包。Wasm 编译由 WebView 完成，不调用 Android 原生执行引擎。

.wasm 或具有 Wasm 文件头的资源以 application/wasm 返回且不附 charset，支持流式实例化。已通过安装检查的其他未知扩展名资源以 application/octet-stream 返回，所以 .data、.bin 或无后缀数据都可用 arrayBuffer() 读取。工具包资源及单次本地资源读取不设固定体积配额，实际能力取决于设备空间、内存和 WebView。

假设包内 add.wasm 导出 add(a, b)，以下代码放入独立 app.js；页面需要 id 为 result 的输出元素。模块的 imports 按实际构建要求传入第二个参数。

```js
(async function () {
  const result = document.getElementById("result");
  try {
    const { instance } = await WebAssembly.instantiateStreaming(fetch("add.wasm"), {});
    result.textContent = String(instance.exports.add(20, 22));
  } catch (error) {
    result.textContent = "Wasm 加载失败：" + error.message;
  }
})();
```

也可以先读取字节，再实例化，并读取随包提供的数据文件；此例放入异步函数中：

```js
const response = await fetch("add.wasm");
if (!response.ok) throw new Error("模块读取失败：" + response.status);
const bytes = await response.arrayBuffer();
const { instance } = await WebAssembly.instantiate(bytes, {});
const dataResponse = await fetch("assets/model.data");
if (!dataResponse.ok) throw new Error("数据读取失败：" + dataResponse.status);
const modelBytes = new Uint8Array(await dataResponse.arrayBuffer());
document.getElementById("result").textContent =
  instance.exports.add(20, 22) + "，数据字节数：" + modelBytes.byteLength;
```

也允许编译内存中的 Uint8Array 或 ArrayBuffer，包括经 ToolBox 已授权 API 获取的字节；宿主不要求字节只能来自包内。内存加载时遵守来源 API 的权限及调用方明确设置的请求预算。

WebView 已支持的 SIMD、GC、异常处理等 Wasm 特性自然可用，没有宿主指令白名单或额外 Wasm 配额。宿主不提供 WASI 接口；工具可携带 JS 适配层。此环境未提供跨源隔离、共享内存/pthreads 配套环境；依赖这些功能的库应选择适合普通 WebView 的构建。ToolBox 不因模块声明这些特性而拒绝安装，但不保证 WebView 能实例化或运行。

损坏模块、缺少 imports 或引擎不支持的特性会产生标准 WebAssembly 错误，应捕获并显示具体原因。若是 WebView 版本不支持，需要更新 Android System WebView；不通过开启 JS unsafe-eval 降级。Wasm 所需 wasm-unsafe-eval 仅允许 Wasm 编译，eval/new Function 仍不可用。流式加载失败时不要一律归因于引擎过旧，还应检查路径、HTTP 状态、模块内容和 imports。

### 高性能计算与 Worker

大量计算放在随包安装的同源 worker.js 中；支持 classic 和 module 两种 Dedicated Worker。页面主线程只负责输入、渲染和 ToolBox 调用。worker 不能直接访问 ToolBox，计算结果用 postMessage 返回顶层页面。Worker 脚本沿用同一 CSP，可读取当前工具资源并执行 Wasm。

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

同一个不含 import/export 的脚本可作为 classic 或 module Worker 使用。下面同时演示两种启动方式，分别计算 42；实际工具按自己的构建方式选择一种即可。第一段放 app.js，第二段保存为 wasm-worker.js，并打包前节的 add.wasm。

```js
for (const type of ["classic", "module"]) {
  const worker = new Worker("wasm-worker.js", { type });
  worker.onmessage = ({ data }) => {
    document.getElementById("result").textContent =
      type + "：" + (data.error || data.result);
    worker.terminate();
  };
  worker.onerror = () => {
    document.getElementById("result").textContent = type + " Worker 启动失败";
    worker.terminate();
  };
  worker.postMessage({ a: 20, b: 22 });
}
```

```js
self.onmessage = async ({ data }) => {
  try {
    const { instance } = await WebAssembly.instantiateStreaming(fetch("add.wasm"), {});
    self.postMessage({ result: instance.exports.add(data.a, data.b) });
  } catch (error) {
    self.postMessage({ error: error.message });
  }
};
```

### 后台与系统能力

background.start 启动持续运行环境；stop 停止。工具应由用户明确开始需要持续执行的工作，并提供停止入口。位置监听、闹钟和通知还需要对应工具/系统权限。精确闹钟的系统入口在工具权限页。

background.enqueue/schedulePeriodic 是委托原生系统的 httpGet/notify 任务。WorkManager 周期至少 15 分钟是所用调度器的要求；短间隔持续任务使用 background.runtime 的计时器。Android 可以因进程生命周期或后台执行规则停止任务，宿主不会以任意总时长提前停止正常任务。

普通通知和实时通知沿用 notifications。实时通知绑定持续会话；Android 和桌面决定最终显示与固定快捷方式的结果。接口成功不表示用户已经看见通知、浏览器已加载页面或快捷方式已经固定。

### 错误处理

等待 ready 后调用接口，并捕获包含 code、message 的错误。NOT_DECLARED 表示缺少清单声明；PERMISSION_DENIED 表示工具开关关闭；SYSTEM_PERMISSION_DENIED 表示系统权限未满足。SESSION_ENDED/INVALID_SESSION 需要重新取得运行环境，不能复用旧 token 或 sessionId。

QUOTA_EXCEEDED 表示实际资源不足或调用方预算无法满足；BUSY 表示同一个资源已有冲突操作。NETWORK_TIMEOUT/NETWORK_UNAVAILABLE 应显示网络错误；不能把失败当成功，也不应无限立即重试。旧宿主可能返回 USER_GESTURE_REQUIRED/RATE_LIMITED；可选 retryAfterMs 仅是重试提示。不要把 Token、文件正文或私人数据写入日志。

## API

### TypeScript 完整声明

复制为 toolbox-api.d.ts 可获得编辑器提示。除事件订阅返回取消函数外，原生调用返回 Promise。

```ts toolbox-api.d.ts
export type ToolBoxCapability =
  | "storage"
  | "storage.secure"
  | "clipboard.write"
  | "clipboard.read"
  | "share"
  | "browser"
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
  | "storage.getMany"
  | "storage.apply"
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
  | "network.authorizeDomain"
  | "network.listDomains"
  | "network.request"
  | "network.openStream"
  | "network.readStream"
  | "network.cancelStream"
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
  | "browser.open"
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

export interface StorageApplyRequest {
  readonly set?: readonly { readonly key: string; readonly value: JsonValue }[];
  readonly remove?: readonly string[];
}

export interface ToolBoxApiError {
  code: ToolBoxErrorCode;
  message: string;
  /** Optional non-negative safe integer milliseconds (at most Number.MAX_SAFE_INTEGER) remaining in the rate-limit window. A pacing hint; recheck permissions and context before retrying. Older hosts and other errors may omit it. */
  retryAfterMs?: number;
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
  /** URL and request body admission use current available process heap. */
  readonly url: string;
  readonly method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD";
  readonly headers?: Readonly<Record<string, string>>;
  readonly body?: string | JsonValue | Uint8Array;
  /** Optional non-negative caller-selected deadline in milliseconds; 0 disables the deadline. Omitted requests use the manifest setting, otherwise no host deadline. The HTTP client uses a signed 32-bit millisecond representation. */
  readonly timeoutMs?: number;
  /** Positive safe integer cumulative budget (up to Number.MAX_SAFE_INTEGER; values above 2147483647 require host 0.8.0+). Omitted uses manifest; no manifest budget means no extra cumulative limit. Full responses remain constrained by shared memory and array/string capacity. */
  readonly maxResponseBytes?: number;
}

export interface NetworkResponse {
  readonly status: number;
  readonly headers: Readonly<Record<string, string>>;
  readonly body: string;
  readonly bodyEncoding: "text" | "base64";
}

export interface NetworkStreamOptions {
  /** Cancels while waiting for headers and while reading. */
  readonly signal?: AbortSignal;
}

export interface NetworkStreamReadOptions {
  /** Host 0.8.0+: desired positive safe-integer chunk size. Host may return fewer bytes based on shared memory and bridge envelope capacity. Omit for automatic chunks. */
  readonly expectedChunkBytes?: number;
}

export interface NetworkStreamResponse {
  readonly streamId: string;
  readonly status: number;
  readonly headers: Readonly<Record<string, string>>;
}

export interface NetworkStreamChunk {
  /** An incremental byte chunk. Decode text with TextDecoder's stream option. */
  readonly data: Uint8Array;
  readonly done: boolean;
  readonly receivedBytes: number;
}

export type LiveNotificationTone = "neutral" | "positive" | "negative" | "warning";

export interface LiveNotificationRequest {
  readonly sessionId: string;
  readonly title: string;
  readonly primaryText: string;
  /** Optional supplementary text; omit together with body for a title-first minimal live card (host 0.7.5+). */
  readonly secondaryText?: string;
  /** Optional expanded prose. Host 0.7.5+ does not repeat primaryText when this is omitted. */
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
  /** Since host 0.6.5, persisted storage has no per-tool capacity quota; available device space applies. Legacy limits.storageBytes is ignored. Writes remain atomic. */
  storage: {
    get(key: string): Promise<JsonValue | null>;
    /** Reads keys from one snapshot, preserving order and duplicates. Missing keys return null; actual memory and any explicit caller budget apply. */
    getMany(keys: readonly string[]): Promise<(JsonValue | null)[]>;
    /** Atomically applies the batch. Duplicate keys (including write/remove conflicts) and invalid entries reject the entire batch. Empty batches are a no-op. */
    apply(mutation: StorageApplyRequest): Promise<void>;
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
    /** Granted foreground read; no touch deadline or additional host confirmation dialog. */
    readText(): Promise<string>;
  };
  network: {
    /** @deprecated Compatibility check: returns true when the network capability is declared and granted. No domain prompt or destination allowlist is used. */
    authorizeDomain(domain: string): Promise<boolean>;
    /** @deprecated Returns an empty list. Network access is controlled by the network permission, not a domain list. */
    listDomains(): Promise<string[]>;
    request(request: NetworkRequest): Promise<NetworkResponse>;
    openStream(request: NetworkRequest, options?: NetworkStreamOptions): Promise<NetworkStreamResponse>;
    readStream(streamId: string, options?: NetworkStreamReadOptions): Promise<NetworkStreamChunk>;
    cancelStream(streamId: string): Promise<void>;
  };
  notifications: {
    /** Text admission uses current available process heap; Android controls display truncation and IPC capacity. */
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
  browser: {
    /** Opens an absolute HTTP/HTTPS URL (without credentials or control characters) in ToolBox's isolated in-app browser (host 0.7.8+). Requires the separate, default-off browser capability and the current visible foreground tool. No per-minute allowance, browsing deadline, or fixed page-size quota applies. No network capability or Android runtime permission is required. Resolves when the browser Activity starts, not when the page loads. Website cookies are shared across browser sessions and tools, separate from tool storage, until expiry or explicit browser-data clearing. Remote pages have no ToolBox bridge. Uploads, downloads and website device permissions are unsupported; the menu offers an explicit system-browser fallback. */
    open(url: string): Promise<void>;
  };
  files: {
    open(mimeTypes?: string[]): Promise<FileToken | null>;
    read(token: string): Promise<Uint8Array>;
    /** Checks current available process heap for the complete response; use chunked APIs for large files. */
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
  /** The tool grant and Android exact-alarm access must both be enabled; the permission screen provides the system entry. */
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
