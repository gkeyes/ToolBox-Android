/** ToolBox 0.8+ subset. No Android bridge is exposed to provider pages. */
export interface Host {
  ready(): Promise<{hostVersion: string}>;
  storage: {
    get(key: string): Promise<unknown>; set(key: string, value: unknown): Promise<void>; remove(key: string): Promise<void>;
    secure: {get(key: string): Promise<unknown>; set(key: string, value: unknown): Promise<void>; remove(key: string): Promise<void>};
  };
  network: {
    openStream(request: {url: string; method: string; headers: Record<string,string>; body?: string; timeoutMs: number}, options?: {signal?: AbortSignal}): Promise<{streamId: string; status: number; headers: Record<string,string>}>;
    readStream(id: string): Promise<{data: Uint8Array; done: boolean}>;
    cancelStream(id: string): Promise<void>;
  };
  files: {open(types?: string[]): Promise<{token: string; name: string} | null>; read(token: string): Promise<Uint8Array>; save(name: string, mime: string, content: string): Promise<unknown | null>};
  clipboard: {writeText(text: string): Promise<void>};
  share: {text(text: string): Promise<void>};
  browser: {open(url: string): Promise<void>};
}
export function host(): Host | undefined {
  return typeof window === 'undefined' ? undefined : (window as unknown as {ToolBox?: Host}).ToolBox;
}
export async function initializeHost() {
  const api = host();
  if (api) {
    await api.ready();
    if (!api.network?.openStream || !api.storage?.secure) throw new Error('请更新 ToolBox，并开启存储、安全存储和网络权限。');
  }
}
export function permissionMessage(error: unknown): string {
  const e = error as {code?: string; message?: string};
  if (/PERMISSION|DENIED|CAPABILITY/.test(e?.code ?? '')) return '请在 ToolBox 的小工具权限中开启对应权限，再重试。';
  return e?.message || '操作失败，请重试。';
}
