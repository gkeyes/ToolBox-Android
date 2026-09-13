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

在 ToolBox 中导入 .tbx。更新时提高 versionCode；同版本或降级需在宿主确认。无效签名、路径穿越、符号链接、内容篡改及不支持的原生代码不会安装。安装失败保留旧版本。

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

网络未指定 timeoutMs 时不施加宿主总时限；0 表示关闭调用时限。调用方明确指定的超时用于该次请求。request 返回完整正文，files.read 返回完整 Uint8Array；这两种一次性返回仍须能够放入当前可用内存，并不意味着可以一次读取任意大的文件。超出实际资源时应显示错误，并改为分页或分块。

普通及安全存储写入保持原子性，不自动淘汰用户数据。storage.getMany 保持请求键顺序；storage.apply 的写删冲突或非法值使整批失败。文件 token 属于当前运行环境，只能读取一次，不能作为长期文件路径保存。

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
    readStream(streamId: string): Promise<NetworkStreamChunk>;
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
