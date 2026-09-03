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
