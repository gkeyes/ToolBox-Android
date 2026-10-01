import { setPersistentEngine } from "@nanostores/persistent";

const PREFS_KEY = "nextflux.preferences.v1";
const AI_KEY = "nextflux.ai-key.v1";
const SPEECH_KEY = "nextflux.speech-key.v1";
const KEYS = new Set(["settings", "theme", "categoryExpanded", "language"]);
const values = Object.create(null);
let storage;
let initialized;
let pending = Promise.resolve();
let lastWrite = pending;
let savedAiKey = "";
let savedSpeechKey = "";

function reportFailure() {
  globalThis.dispatchEvent?.(new CustomEvent("nextflux:storage-error"));
}

// Settings are flat JSON values. Mounting a store may only reorder its fields.
function sameSettings(previous, next) {
  if (!previous) return false;
  const before = JSON.parse(previous);
  const after = JSON.parse(next);
  const keys = Object.keys(after);
  return keys.length === Object.keys(before).length && keys.every((key) => before[key] === after[key]);
}

function persist() {
  const snapshot = { ...values };
  let aiKey = "";
  let speechKey = "";
  if (snapshot.settings) {
    const settings = JSON.parse(snapshot.settings);
    aiKey = typeof settings.aiApiKey === "string" ? settings.aiApiKey : "";
    speechKey = typeof settings.speechApiKey === "string" ? settings.speechApiKey : "";
    delete settings.aiApiKey;
    delete settings.speechApiKey;
    snapshot.settings = JSON.stringify(settings);
  }
  const operation = pending.then(async () => {
    if (aiKey !== savedAiKey) {
      if (aiKey) await storage.secure.set(AI_KEY, aiKey);
      else await storage.secure.remove(AI_KEY);
      savedAiKey = aiKey;
    }
    if (speechKey !== savedSpeechKey) {
      if (speechKey) await storage.secure.set(SPEECH_KEY, speechKey);
      else await storage.secure.remove(SPEECH_KEY);
      savedSpeechKey = speechKey;
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
      const unchanged = key === "settings" && sameSettings(target[key], value);
      target[key] = value;
      if (storage && !unchanged) persist();
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
    const [stored, secret, speechSecret] = await Promise.all([
      api.storage.get(PREFS_KEY),
      api.storage.secure.get(AI_KEY),
      api.storage.secure.get(SPEECH_KEY),
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
    savedSpeechKey = typeof speechSecret === "string" ? speechSecret : "";
    const settings = JSON.parse(values.settings || "{}");
    settings.aiApiKey = savedAiKey;
    settings.speechApiKey = savedSpeechKey;
    values.settings = JSON.stringify(settings);
    storage = api.storage;
  })();
  return initialized;
}

export const getPreference = (key) => values[key];
export const setPreference = (key, value) => { engine[key] = value; };
export const flushPreferences = () => lastWrite;
