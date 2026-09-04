import { copy, emptyArchive, normalizeArchive, HealthError } from "./model.mjs";

const HEAD = "health.v1.head";
const PREFIX = "health.v1.bucket.";
const BUCKETS = 8;

function bucketFor(id) {
  let hash = 0;
  for (const ch of id) hash = (Math.imul(hash, 31) + ch.charCodeAt(0)) | 0;
  return (hash >>> 0) % BUCKETS;
}

export function createStore(storage, makeId = () => crypto.randomUUID()) {
  let state = null, head = null, revision = 0, tail = Promise.resolve();
  let encodedBuckets = Array(BUCKETS).fill("[]");

  async function cleanUnused() {
    const keep = new Set(head?.buckets || []);
    const keys = await storage.keys();
    for (const key of keys) if (key.startsWith(PREFIX) && !keep.has(key)) await storage.remove(key);
  }

  async function load() {
    const saved = await storage.get(HEAD);
    if (saved === null) {
      state = emptyArchive(); head = null;
    } else {
      if (saved.format !== 1 || !Array.isArray(saved.buckets) || saved.buckets.length !== BUCKETS || !saved.meta) throw new HealthError("本地档案索引损坏，未新建或覆盖任何记录", "CORRUPT_STORAGE");
      const buckets = [];
      for (const key of saved.buckets) {
        if (key === null) { buckets.push([]); continue; }
        if (typeof key !== "string" || !key.startsWith(PREFIX)) throw new HealthError("本地档案索引无效", "CORRUPT_STORAGE");
        const value = await storage.get(key);
        if (!Array.isArray(value)) throw new HealthError("部分记录无法读取，未覆盖原数据，请恢复备份或重试", "CORRUPT_STORAGE");
        buckets.push(value);
      }
      state = normalizeArchive({ ...saved.meta, records: buckets.flat() }, makeId);
      encodedBuckets = buckets.map((rows) => JSON.stringify(rows));
      head = saved;
    }
    try { await cleanUnused(); } catch { /* A committed head remains valid if orphan cleanup is unavailable. */ }
    return copy(state);
  }

  async function commit(mutator) {
    if (!state) throw new HealthError("记录尚未读取完成，请稍后重试", "NOT_READY");
    const draft = copy(state);
    const replacement = await mutator(draft);
    const next = normalizeArchive(replacement || draft, makeId);
    const buckets = Array.from({ length: BUCKETS }, () => []);
    for (const record of next.records) buckets[bucketFor(record.id)].push(record);
    for (const rows of buckets) rows.sort((a, b) => a.id.localeCompare(b.id));
    const serialized = buckets.map((rows) => JSON.stringify(rows));
    const keys = head?.buckets.slice() || Array(BUCKETS).fill(null);
    const staged = [];
    const { records: _records, ...meta } = next;
    try {
      for (let i = 0; i < BUCKETS; i++) {
        if (serialized[i] === encodedBuckets[i]) continue;
        if (!buckets[i].length) { keys[i] = null; continue; }
        const key = `${PREFIX}${makeId()}`;
        staged.push(key);
        await storage.set(key, buckets[i]);
        keys[i] = key;
      }
      const nextHead = { format: 1, buckets: keys, meta };
      await storage.set(HEAD, nextHead);
      head = nextHead; state = next; encodedBuckets = serialized; revision++;
    } catch (error) {
      for (const key of staged) { try { await storage.remove(key); } catch { /* Retried by cleanUnused on reopen. */ } }
      throw error;
    }
    try { await cleanUnused(); } catch { /* Saving succeeded; cleanup must not turn it into a false failure. */ }
    return copy(state);
  }

  return {
    load,
    get value() { if (!state) throw new HealthError("档案尚未加载"); return copy(state); },
    get revision() { return revision; },
    update(mutator) {
      const pending = tail.then(() => commit(mutator));
      tail = pending.catch(() => {});
      return pending;
    },
  };
}
