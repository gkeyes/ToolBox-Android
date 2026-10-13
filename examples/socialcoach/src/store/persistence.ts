import { appStorage, flushStorage, getManyItems, onBeforeFlush } from "@platform/storage";
import { recordWork } from "@platform/perf";
import type { Session } from "@/lib/types";
import { useApp, type AppState, type Settings } from "./useApp";

/**
 * Storage layout (v2): one small index record plus one record per session.
 *
 * The index holds everything except the transcripts, and the ordered list of
 * session ids. A change to one session rewrites only that session's record;
 * writes are debounced and committed on every flushStorage() (runtime flush
 * handler, page hidden, end of a turn). Nothing is capped or dropped.
 */
export const LEGACY_KEY = "socialcoach.v1";
export const INDEX_KEY = "socialcoach.v2.index";
const SESSION_PREFIX = "socialcoach.v2.session.";
/**
 * Existing keys use encodeURIComponent. An id it cannot encode (a lone UTF-16
 * surrogate, possible in a restored backup) uses UTF-16 code-unit hex under a
 * separate prefix, so every string has a stable key and no two ids collide.
 */
const SESSION_HEX_PREFIX = "socialcoach.v2.session-u16.";
export const sessionKey = (id: string) => {
  try { return SESSION_PREFIX + encodeURIComponent(id); }
  catch { return SESSION_HEX_PREFIX + Array.from({ length: id.length }, (_, i) => id.charCodeAt(i).toString(16).padStart(4, "0")).join(""); }
};

const DEBOUNCE_MS = 500;
const MAX_WAIT_MS = 2000;

const INDEX_FIELDS = ["profile", "proficiency", "customScenarios", "bookmarks", "practiceDays", "todaySessionId", "todayDate", "settings", "patternInsight"] as const;
type IndexField = (typeof INDEX_FIELDS)[number];
type Persisted = Pick<AppState, IndexField | "sessions">;

/** Fields from retired features (speech, telemetry) are dropped on load. */
export function cleanSettings(value: unknown): Settings {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const { tts: _tts, telemetry: _telemetry, voiceNoticeSeen: _voice, ...rest } = value as Record<string, unknown>;
  return rest as Settings;
}

function serialize(value: unknown): string {
  const started = performance.now();
  const raw = JSON.stringify(value);
  if (raw === undefined) throw new Error("练习记录无法序列化。");
  recordWork("serialize", raw.length * 2, performance.now() - started);
  return raw;
}

const dirtySessions = new Set<string>();
const removedSessions = new Set<string>();
/** Sessions whose writes wait for the end of a streaming turn (a flush still writes them). */
const held = new Set<string>();
/** Sessions whose record exists in storage or is queued; the index never lists one that is not. */
const recorded = new Set<string>();
let indexDirty = false;
let timer: ReturnType<typeof setTimeout> | null = null;
let firstDirtyAt = 0;
let unsubscribe: (() => void) | null = null;
let unhook: (() => void) | null = null;

function indexRecord(s: AppState) {
  const record: Record<string, unknown> = { version: 2, sessionIds: s.sessions.map((x) => x.id) };
  for (const field of INDEX_FIELDS) record[field] = s[field];
  return record;
}

/** Hand dirty records to the storage queue. `force` includes sessions held by a streaming turn. */
function commit(force: boolean) {
  if (timer) { clearTimeout(timer); timer = null; }
  firstDirtyAt = 0;
  const state = useApp.getState();
  if (dirtySessions.size) {
    const byId = new Map(state.sessions.map((x) => [x.id, x]));
    for (const id of [...dirtySessions]) {
      if (!force && held.has(id)) continue;
      dirtySessions.delete(id);
      const session = byId.get(id);
      if (session) { appStorage.setItem(sessionKey(id), serialize(session)); recorded.add(id); }
    }
  }
  // The index goes after the records it lists (a held new session keeps it waiting); removals after the index that dropped them.
  if (indexDirty && state.sessions.every((x) => recorded.has(x.id))) { indexDirty = false; appStorage.setItem(INDEX_KEY, serialize(indexRecord(state))); }
  for (const id of removedSessions) { appStorage.removeItem(sessionKey(id)); recorded.delete(id); }
  removedSessions.clear();
}

function schedule() {
  const now = Date.now();
  if (!firstDirtyAt) firstDirtyAt = now;
  if (timer) clearTimeout(timer);
  timer = setTimeout(() => commit(false), Math.max(0, Math.min(DEBOUNCE_MS, firstDirtyAt + MAX_WAIT_MS - now)));
}

function track(next: AppState, prev: AppState) {
  let changed = false;
  if (next.sessions !== prev.sessions) {
    const before = new Map(prev.sessions.map((x) => [x.id, x]));
    if (next.sessions.length !== prev.sessions.length || next.sessions.some((x, i) => x.id !== prev.sessions[i]?.id)) indexDirty = true;
    for (const session of next.sessions) {
      if (before.get(session.id) !== session) { dirtySessions.add(session.id); removedSessions.delete(session.id); changed = true; }
      before.delete(session.id);
    }
    for (const id of before.keys()) { removedSessions.add(id); dirtySessions.delete(id); changed = true; }
  }
  for (const field of INDEX_FIELDS) if (next[field] !== prev[field]) indexDirty = true;
  if (changed || indexDirty) schedule();
}

/** Keep a session's streaming checkpoints in memory until the turn ends. */
export function holdSessionWrites(id: string) { held.add(id); }
export function releaseSessionWrites(id: string) {
  held.delete(id);
  if (dirtySessions.has(id)) schedule();
}

function parse<T>(raw: string): T {
  const started = performance.now();
  const value = JSON.parse(raw) as T;
  recordWork("parse", raw.length * 2, performance.now() - started);
  return value;
}

function persistedFrom(source: Record<string, unknown>, sessions: Session[]): Persisted {
  const pick = <T,>(value: unknown, fallback: T): T => (value === undefined ? fallback : (value as T));
  return {
    profile: pick(source.profile, null),
    proficiency: pick(source.proficiency, {}),
    sessions,
    customScenarios: pick(source.customScenarios, []),
    bookmarks: pick(source.bookmarks, []),
    practiceDays: pick(source.practiceDays, []),
    todaySessionId: pick(source.todaySessionId, null),
    todayDate: pick(source.todayDate, null),
    settings: cleanSettings(source.settings),
    patternInsight: pick(source.patternInsight, null),
  };
}

/**
 * Load the store, migrating the single-blob v1 record on first run. The v1 key
 * is removed only after every migrated record has been confirmed written;
 * until then it remains the fallback for any session record that is missing.
 */
export async function hydrateApp(): Promise<void> {
  unsubscribe?.();
  unhook?.();
  dirtySessions.clear(); removedSessions.clear(); held.clear(); recorded.clear(); indexDirty = false;
  if (timer) { clearTimeout(timer); timer = null; }

  const [rawIndex, rawLegacy] = await getManyItems([INDEX_KEY, LEGACY_KEY]);
  const legacy = rawLegacy === null ? null : (parse<{ state?: Record<string, unknown> }>(rawLegacy)?.state ?? {});
  const legacySessions = new Map(((legacy?.sessions as Session[] | undefined) ?? []).map((x) => [x.id, x]));
  let loaded: Persisted | null = null;
  let migrate = false;
  if (rawIndex !== null) {
    const index = parse<Record<string, unknown> & { sessionIds?: string[] }>(rawIndex);
    const ids = Array.isArray(index.sessionIds) ? index.sessionIds : [];
    const raws = await getManyItems(ids.map(sessionKey));
    const sessions: Session[] = [];
    ids.forEach((id, i) => {
      const raw = raws[i];
      if (raw !== null) { sessions.push(parse<Session>(raw)); recorded.add(id); }
      else if (legacySessions.has(id)) { sessions.push(legacySessions.get(id)!); dirtySessions.add(id); }
    });
    loaded = persistedFrom(index, sessions);
    migrate = legacy !== null;
  } else if (legacy) {
    loaded = persistedFrom(legacy, [...legacySessions.values()]);
    migrate = true;
  }
  if (loaded) useApp.setState(loaded);

  // Anything already in memory that storage does not hold yet gets written (never silently left out of the index).
  const unrecorded = useApp.getState().sessions.filter((x) => !recorded.has(x.id));
  for (const session of unrecorded) dirtySessions.add(session.id);
  if (unrecorded.length) { indexDirty = true; schedule(); }
  unsubscribe = useApp.subscribe(track);
  unhook = onBeforeFlush(() => commit(true));

  if (migrate) {
    if (rawIndex === null) {
      for (const session of useApp.getState().sessions) dirtySessions.add(session.id);
      indexDirty = true;
    }
    try {
      await flushStorage();
      appStorage.removeItem(LEGACY_KEY);
      await flushStorage();
    } catch {
      // The storage banner reports the failure; v1 stays until a later run confirms the copy.
    }
  }
}
