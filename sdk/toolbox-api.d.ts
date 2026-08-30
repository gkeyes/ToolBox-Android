export type ToolBoxContractSha256 = "aad95df52b9265d15bee16cbd003b39500788d16d7b082aa1b5e0748b219ddbd";

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
  | "background.tasks";

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
  | "notifications.cancel"
  | "background.enqueue"
  | "background.schedulePeriodic"
  | "background.list"
  | "background.getResult"
  | "background.cancel"
  | "clipboard.readText"
  | "share.text"
  | "files.open"
  | "files.read"
  | "files.save"
  | "shortcuts.pin"
  | "camera.capture"
  | "location.getCurrent";

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
  url: string;
  method?: "GET" | "POST";
  body?: string;
  contentType?: "text/plain" | "application/json";
}

export interface NetworkResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
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
  requiresCharging?: boolean;
  batteryNotLow?: boolean;
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
  earliestAt?: number;
  constraints?: TaskConstraints;
}

export interface PeriodicTaskSpec extends BackgroundTaskSpec {
  intervalMinutes: number;
}

export type TaskState = "QUEUED" | "RUNNING" | "COMPLETED" | "CANCELLED";
export type RunOutcome = "SUCCEEDED" | "FAILED" | "CANCELLED";

export interface TaskSummary {
  taskId: string;
  key: string;
  state: TaskState;
  periodic: boolean;
  nextRunAt?: number;
}

export interface TaskRunResult {
  taskId: string;
  outcome: RunOutcome;
  completedAt: number;
  status?: number;
  body?: string;
  error?: ToolBoxApiError;
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
    cancel(id: string): Promise<void>;
  };
  background: {
    enqueue(spec: BackgroundTaskSpec): Promise<string>;
    schedulePeriodic(spec: PeriodicTaskSpec): Promise<string>;
    list(): Promise<TaskSummary[]>;
    getResult(taskId: string): Promise<TaskRunResult | null>;
    cancel(taskId: string): Promise<void>;
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
    getCurrent(accuracy?: "coarse" | "precise", timeoutMs?: number): Promise<{
      latitude: number;
      longitude: number;
      accuracyMeters: number;
      capturedAt: number;
    }>;
  };
}

declare global {
  interface Window {
    ToolBox: ToolBoxApi;
  }
}
