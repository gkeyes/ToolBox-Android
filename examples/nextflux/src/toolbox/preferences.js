import { setPersistentEngine } from "@nanostores/persistent";

const PREFS_KEY = "nextflux.preferences.v1";
const AI_KEY = "nextflux.ai-key.v1";
const KEYS = new Set(["settings", "theme", "categoryExpanded", "language"]);
const values = Object.create(null);
let storage;
let initialized;
let pending = Promise.resolve();
let lastWrite = pending;
let savedAiKey = "";

function reportFailure() {
  globalThis.dispatchEvent?.(new CustomEvent("nextflux:storage-error"));
}

function persist() {
  const snapshot = { ...values };
  let aiKey = "";
  if (snapshot.settings) {
    const settings = JSON.parse(snapshot.settings);
    aiKey = typeof settings.aiApiKey === "string" ? settings.aiApiKey : "";
    delete settings.aiApiKey;
    snapshot.settings = JSON.stringify(settings);
  }
  if (new TextEncoder().encode(JSON.stringify(snapshot)).length > 128 * 1024) {
    lastWrite = Promise.reject(new Error("设置内容过长，请缩短 AI 提示词后重试。"));
    pending = lastWrite.catch(reportFailure);
    return;
  }
  const operation = pending.then(async () => {
    if (aiKey !== savedAiKey) {
      if (aiKey) await storage.secure.set(AI_KEY, aiKey);
      else await storage.secure.remove(AI_KEY);
      savedAiKey = aiKey;
    }
    await storage.set(PREFS_KEY, snapshot);
  });
  lastWrite = operation;
  pending = operation.catch(reportFailure);
}

const engine = new Proxy(values, {
  set(target, key, value) {
    if (!KEYS.has(key) || typeof value !== "string") return true;
    if (target[key] !== value) {
      target[key] = value;
      if (storage) persist();
    }
    return true;
  },
  deleteProperty(target, key) {
    if (KEYS.has(key) && key in target) {
      delete target[key];
      if (storage) persist();
    }
    return true;
  },
});

// Nanostores supports a custom persistence engine; browser APIs stay untouched.
setPersistentEngine(engine, { addEventListener() {}, removeEventListener() {} });

export function initializePreferences(api = globalThis.ToolBox) {
  if (!initialized) initialized = (async () => {
    if (!api?.storage) throw new Error("请在 ToolBox 中打开 NextFlux。");
    const [stored, secret] = await Promise.all([
      api.storage.get(PREFS_KEY),
      api.storage.secure.get(AI_KEY),
    ]);
    if (stored && typeof stored === "object" && !Array.isArray(stored)) {
      for (const key of KEYS) {
        if (typeof stored[key] !== "string") continue;
        if (key !== "language") {
          try {
            const parsed = JSON.parse(stored[key]);
            if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) continue;
          } catch { continue; }
        }
        values[key] = stored[key];
      }
    }
    savedAiKey = typeof secret === "string" ? secret : "";
    const settings = JSON.parse(values.settings || "{}");
    settings.aiApiKey = savedAiKey;
    values.settings = JSON.stringify(settings);
    storage = api.storage;
  })();
  return initialized;
}

export const getPreference = (key) => values[key];
export const setPreference = (key, value) => { engine[key] = value; };
export const flushPreferences = () => lastWrite;
