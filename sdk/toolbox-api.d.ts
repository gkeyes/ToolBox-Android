export type ToolBoxContractSha256 = "dbe81127fe54d37775006add8c64747243b0b14365f2bc482cbacb7d14908998";

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
  /** Optional non-negative safe integer milliseconds (at most Number.MAX_SAFE_INTEGER) remaining in the rate-limit window. A pacing hint; recheck permissions, context and gestures before retrying. Older hosts and other errors may omit it. */
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
  readonly url: string;
  readonly method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD";
  readonly headers?: Readonly<Record<string, string>>;
  readonly body?: string | JsonValue | Uint8Array;
  /** 1000–3600000 ms; defaults to 30000 and is capped by the manifest. Host 0.3.7+ applies this budget to call, read and write waits; values above 600000 require host 0.6.1+; connection establishment remains bounded to 10 seconds. */
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
    /** Host 0.6.6+: reads up to 256 keys from one snapshot, preserving order and duplicates. Missing keys return null. Subject to the existing response size limit. */
    getMany(keys: readonly string[]): Promise<(JsonValue | null)[]>;
    /** Host 0.6.6+: atomically applies at most 256 total keys. Duplicate keys (including write/remove conflicts) and invalid entries reject the entire batch. Empty batches are a no-op. */
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
    /** Opens an absolute HTTP/HTTPS URL (at most 2048 characters, without credentials or control characters) in a system browser. Requires the separate, default-off browser capability, foreground context and a recent real touch; at most 10 calls per minute. No network capability or Android runtime permission is required. Resolves when the system accepts the launch, not when the page loads. */
    open(url: string): Promise<void>;
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
