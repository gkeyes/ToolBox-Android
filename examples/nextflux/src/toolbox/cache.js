import { waitForRateLimit } from "./rate-limit.js";
import { deriveArticleMetadata } from "./cache-metadata.js";
import { decodeCacheValue, loadLegacyCache, LEGACY_PREFIX, LEGACY_MANIFEST_KEY } from "./cache-legacy.js";

// The production caller runs this engine in cache-worker.js. No DOM, body
// archive, compression or query index crosses back to the window.
export const CACHE_PREFIX = "nextflux.cache.v3.";
export const ROOT_KEY = `${CACHE_PREFIX}root`;
export { LEGACY_PREFIX } from "./cache-legacy.js";
const TABLES = ["articles", "feeds", "categories", "feedIcons"];
const FANOUT = 128;
const PART_CHARS = 48 * 1024;
const BATCH_BYTES = 1024 * 1024;
const BATCH_KEYS = 256;
const encoder = new TextEncoder();
export const jsonBytes = (value) => encoder.encode(JSON.stringify(value)).length;
const record = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const clone = (value) => value === undefined ? undefined : structuredClone(value);
const fail = (message, code = "CACHE_INVALID") => Object.assign(new Error(message), { code });
const cancelled = () => fail("登录状态已改变，此次操作已停止。", "ACCOUNT_CHANGED");
const invalid = () => fail("阅读缓存格式或内容损坏，请退出登录后重新同步。");
const validId = (id) => Number.isSafeInteger(id) && id >= 0;
const keyOf = (table, row) => table === "feedIcons" ? row.feedId : row.id;
const emptyMaps = () => Object.fromEntries(TABLES.map((name) => [name, new Map()]));
const emptyRoot = (account = null, revision = 0) => ({
  version: 3, revision, account, transaction: null, lastSyncTime: null,
  tables: Object.fromEntries(TABLES.map((name) => [name, null])),
});
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const publicRow = (row) => {
  if (!row) return null;
  const { bodyRef, ...value } = row;
  return clone(value);
};

async function encodeDocument(value, compress) {
  const text = JSON.stringify(value);
  if (!compress || typeof globalThis.CompressionStream !== "function" ||
      typeof globalThis.DecompressionStream !== "function") return { codec: "json", text };
  const bytes = new Uint8Array(await new Response(new Blob([text]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer());
  const parts = [];
  for (let offset = 0; offset < bytes.length; offset += 32768) {
    parts.push(String.fromCharCode(...bytes.subarray(offset, offset + 32768)));
  }
  return { codec: "gzip-base64", text: btoa(parts.join("")) };
}

async function bodyDigest(value) {
  const bytes = encoder.encode(JSON.stringify(value));
  const digest = await globalThis.crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, "0")).join("");
}

export function createArticleCache(storage, options = {}) {
  if (!storage?.getMany || !storage?.apply || !storage?.keys) {
    throw fail("请更新 ToolBox 后重新打开 NextFlux。", "UNSUPPORTED");
  }
  let snapshot = { root: emptyRoot(), maps: emptyMaps(), nodes: new Map(), refs: new Set() };
  let loaded = null;
  let epoch = 0;
  let serial = 0;
  let writer = Promise.resolve();
  let stagingWriter = Promise.resolve();
  let syncStage = null;
  let clearing = null;
  let clearingFailed = false;
  let uncertainCommit = null;
  let gcPromise = null;
  let gcTimer = null;
  let gcAgain = false;
  const stages = new Set();
  const pins = new Map();
  const queries = new Map();
  const countIndexes = new WeakMap();
  const retired = new Set();
  let sweepVersion = 1;
  let sweptVersion = 0;
  const tokenPrefix = options.tokenPrefix || globalThis.crypto.randomUUID().replaceAll("-", "");
  const digest = options.digest || bodyDigest;
  const deriveMetadata = options.deriveMetadata || deriveArticleMetadata;
  const yieldBackground = async (expectedEpoch) => {
    await new Promise((resolve) => setTimeout(resolve, 0));
    checkEpoch(expectedEpoch);
  };
  const checkEpoch = (value) => { if (value !== epoch) throw cancelled(); };
  const checkStage = (stage) => { checkEpoch(stage.epoch); if (!stages.has(stage) || stage.aborted) throw cancelled(); };
  const enqueue = (operation) => {
    const expectedEpoch = epoch;
    const pending = writer.then(() => { checkEpoch(expectedEpoch); return operation(); });
    writer = pending.catch(() => {});
    return pending;
  };

  async function native(method, args, expectedEpoch = epoch, intent = () => true) {
    while (true) {
      checkEpoch(expectedEpoch);
      if (!intent()) return;
      try { const result = await storage[method](...args); checkEpoch(expectedEpoch); return result; }
      catch (error) {
        checkEpoch(expectedEpoch);
        if (error?.code !== "RATE_LIMITED") throw error;
        // Only native admission rejection is replayable. GC rechecks every key
        // after waiting; account invalidation cancels reads and writes promptly.
        if (!await waitForRateLimit(error, () => { checkEpoch(expectedEpoch); return intent(); }, options.rateLimit)) return;
      }
    }
  }

  async function readMany(keys, expectedEpoch = epoch) {
    const results = [];
    async function batch(part) {
      try {
        const values = await native("getMany", [part], expectedEpoch);
        if (!Array.isArray(values) || values.length !== part.length) throw invalid();
        results.push(...values);
      } catch (error) {
        if (error?.code !== "QUOTA_EXCEEDED" || part.length < 2) throw error;
        const middle = Math.ceil(part.length / 2);
        await batch(part.slice(0, middle));
        await batch(part.slice(middle));
      }
    }
    for (let offset = 0; offset < keys.length; offset += BATCH_KEYS) await batch(keys.slice(offset, offset + BATCH_KEYS));
    return results;
  }

  function validateRef(ref) {
    if (typeof ref !== "string" || !ref.startsWith(`${CACHE_PREFIX}d.`) ||
        !/^[A-Za-z0-9.]+$/.test(ref.slice(CACHE_PREFIX.length)) || ref.length > 112) throw invalid();
  }

  async function readDocuments(refs, expectedEpoch = epoch) {
    refs.forEach(validateRef);
    const headers = await readMany(refs, expectedEpoch);
    const partRequests = [];
    for (let index = 0; index < headers.length; index += 1) {
      const header = headers[index];
      if (!record(header) || header.format !== 1 || !["json", "gzip-base64"].includes(header.codec)) throw invalid();
      if (typeof header.text === "string") {
        if (!header.text.length || header.text.length > PART_CHARS) throw invalid();
        continue;
      }
      if (!Number.isSafeInteger(header.parts) || header.parts < 1 || !Number.isSafeInteger(header.length) || header.length < 1 ||
          header.parts < Math.ceil(header.length / (PART_CHARS + 1)) || header.parts > Math.ceil(header.length / PART_CHARS) ||
          !Array.isArray(header.partBytes) || header.partBytes.length !== header.parts ||
          header.partBytes.some((size) => !Number.isSafeInteger(size) || size < 2 || size > 6 * (PART_CHARS + 1) + 2)) throw invalid();
      for (let part = 0; part < header.parts; part += 1) partRequests.push({ key: `${refs[index]}.p${part}`, bytes: header.partBytes[part] + 64 });
    }
    const parts = [];
    let requestKeys = [];
    let requestBytes = 0;
    for (const request of partRequests) {
      if (requestKeys.length && (requestBytes + request.bytes > BATCH_BYTES || requestKeys.length >= BATCH_KEYS)) {
        parts.push(...await readMany(requestKeys, expectedEpoch)); requestKeys = []; requestBytes = 0;
      }
      requestKeys.push(request.key); requestBytes += request.bytes;
    }
    if (requestKeys.length) parts.push(...await readMany(requestKeys, expectedEpoch));
    const values = [];
    let offset = 0;
    for (const header of headers) {
      let encoded = header.text;
      if (encoded === undefined) {
        const chunks = parts.slice(offset, offset + header.parts);
        offset += header.parts;
        if (chunks.some((part, index) => typeof part !== "string" || !part.length || part.length > PART_CHARS + 1 ||
            (index < chunks.length - 1 && part.length < PART_CHARS) || jsonBytes(part) !== header.partBytes[index]) ||
            chunks.reduce((total, part) => total + part.length, 0) !== header.length) throw invalid();
        encoded = chunks.join("");
      }
      try { values.push(await decodeCacheValue(header.codec, encoded)); }
      catch (cause) { if (cause?.code === "UNSUPPORTED") throw cause; throw invalid(); }
      checkEpoch(expectedEpoch);
    }
    return values;
  }

  function newStage(settings = {}) {
    const stage = {
      token: `${tokenPrefix}.${++serial}`, epoch, count: 0, writes: [], bytes: 0,
      refs: new Set(), retainedRefs: new Set(), base: snapshot, maps: null, changes: new Map(), changesAt: new Map(), ...settings,
    };
    stages.add(stage);
    return stage;
  }

  async function flush(stage) {
    checkStage(stage);
    if (!stage.writes.length) return;
    const writes = stage.writes;
    await native("apply", [{ set: writes.map(({ key, value }) => ({ key, value })) }], stage.epoch, () => { checkStage(stage); return true; });
    checkStage(stage);
    stage.writes = [];
    stage.bytes = 0;
    if (stage.background) await yieldBackground(stage.epoch);
  }

  async function queueValue(stage, key, value) {
    const bytes = jsonBytes({ key, value }) + 1;
    if (stage.writes.length && (stage.bytes + bytes > BATCH_BYTES || stage.writes.length >= BATCH_KEYS)) await flush(stage);
    checkStage(stage);
    stage.writes.push({ key, value });
    stage.bytes += bytes;
  }

  async function writeDocument(stage, value, compress = false) {
    checkStage(stage);
    const ref = `${CACHE_PREFIX}d.${stage.token}.${++stage.count}`;
    stage.refs.add(ref);
    const encoded = await encodeDocument(value, compress);
    checkStage(stage);
    // Small bodies occupy one logical KV entry. Keeping an extra header key per
    // article would double first-sync calls under the existing method window.
    if (compress && encoded.text.length <= PART_CHARS) {
      await queueValue(stage, ref, { format: 1, codec: encoded.codec, text: encoded.text });
      return ref;
    }
    // JSON escapes lone surrogates. At a valid pair boundary, include both code
    // units in this chunk so UTF-8 storage never changes the original string.
    let offset = 0;
    let part = 0;
    const partBytes = [];
    while (offset < encoded.text.length) {
      let end = Math.min(offset + PART_CHARS, encoded.text.length);
      if (end < encoded.text.length && /[\uD800-\uDBFF]/.test(encoded.text[end - 1]) && /[\uDC00-\uDFFF]/.test(encoded.text[end])) end += 1;
      const value = encoded.text.slice(offset, end);
      partBytes.push(jsonBytes(value));
      await queueValue(stage, `${ref}.p${part++}`, value);
      offset = end;
    }
    await queueValue(stage, ref, { format: 1, codec: encoded.codec, parts: part, length: encoded.text.length, partBytes });
    return ref;
  }

  function validateRoot(root) {
    if (!record(root) || root.version !== 3 || !Number.isSafeInteger(root.revision) || root.revision < 0 ||
        !record(root.tables) || TABLES.some((name) => !(name in root.tables)) ||
        (root.lastSyncTime !== null && (typeof root.lastSyncTime !== "string" || !Number.isFinite(Date.parse(root.lastSyncTime))))) throw invalid();
    for (const value of Object.values(root.tables)) {
      if (value === null) continue;
      if (!record(value) || !Number.isInteger(value.level) || value.level < 0 || value.level > 7) throw invalid();
      validateRef(value.key);
    }
  }

  async function restore(root, expectedEpoch) {
    validateRoot(root);
    const maps = emptyMaps();
    const nodes = new Map();
    const refs = new Set();
    let pending = TABLES.filter((name) => root.tables[name]).map((table) => ({ table, ...root.tables[table], prefix: 0 }));
    while (pending.length) {
      const current = pending;
      pending = [];
      const values = await readDocuments(current.map((value) => value.key), expectedEpoch);
      for (let index = 0; index < current.length; index += 1) {
        const item = current[index];
        const value = values[index];
        if (refs.has(item.key)) throw invalid();
        refs.add(item.key);
        nodes.set(item.key, value);
        if (item.level === -1) {
          if (!record(value) || value.kind !== "leaf" || !Array.isArray(value.rows) || value.rows.length > FANOUT) throw invalid();
          for (const row of value.rows) {
            if (!record(row)) throw invalid();
            const id = keyOf(item.table, row);
            if (!validId(id) || Math.floor(id / FANOUT) !== item.prefix || maps[item.table].has(id)) throw invalid();
            if (item.table === "articles") { validateRef(row.bodyRef); refs.add(row.bodyRef); }
            maps[item.table].set(id, row);
          }
        } else {
          if (!record(value) || value.kind !== "branch" || value.level !== item.level || !record(value.entries) || Object.keys(value.entries).length > FANOUT) throw invalid();
          for (const [slot, ref] of Object.entries(value.entries)) {
            const position = Number(slot);
            if (!Number.isInteger(position) || position < 0 || position >= FANOUT || String(position) !== slot) throw invalid();
            validateRef(ref);
            pending.push({ table: item.table, key: ref, level: item.level - 1, prefix: item.prefix + position * FANOUT ** item.level });
          }
        }
      }
    }
    return { root, maps, nodes, refs };
  }

  // Callers supply dirty IDs; status commits never enumerate the article map.
  async function updateTree(stage, table, nextMap, nodes, dirtyIds, replaced) {
    const dirty = new Set([...dirtyIds].map((id) => Math.floor(id / FANOUT)));
    let tree = stage.base.root.tables[table];
    if (!dirty.size) return tree;
    const leafRefs = new Map();
    for (const bucket of dirty) {
      const rows = [];
      for (let id = bucket * FANOUT; id < (bucket + 1) * FANOUT && id <= Number.MAX_SAFE_INTEGER; id += 1) {
        options.onIndexAccess?.({ kind: "row", table, id });
        const row = nextMap.get(id);
        if (row) rows.push(row);
      }
      if (!rows.length) leafRefs.set(bucket, null);
      else {
        const node = { kind: "leaf", rows };
        const ref = await writeDocument(stage, node);
        nodes.set(ref, node);
        leafRefs.set(bucket, ref);
      }
    }
    let level = tree?.level || 0;
    let maxBucket = 0;
    for (const bucket of dirty) maxBucket = Math.max(maxBucket, bucket);
    while (maxBucket >= FANOUT ** (level + 1)) {
      level += 1;
      if (tree) {
        const node = { kind: "branch", level, entries: { 0: tree.key } };
        const key = await writeDocument(stage, node);
        nodes.set(key, node);
        tree = { key, level };
      }
    }
    const replace = async (oldRef, currentLevel, changes) => {
      if (oldRef) {
        options.onIndexAccess?.({ kind: "node", table, key: oldRef });
        replaced.add(oldRef);
      }
      const previous = oldRef ? nodes.get(oldRef) : null;
      if (oldRef && (!previous || previous.kind !== "branch" || previous.level !== currentLevel)) throw invalid();
      const entries = { ...previous?.entries };
      const grouped = new Map();
      for (const [bucket, ref] of changes) {
        const slot = Math.floor(bucket / FANOUT ** currentLevel) % FANOUT;
        if (!grouped.has(slot)) grouped.set(slot, []);
        grouped.get(slot).push([bucket, ref]);
      }
      for (const [slot, values] of grouped) {
        if (currentLevel === 0 && entries[slot]) replaced.add(entries[slot]);
        const ref = currentLevel === 0 ? values[0][1] : await replace(entries[slot], currentLevel - 1, values);
        if (ref === null) delete entries[slot]; else entries[slot] = ref;
      }
      if (!Object.keys(entries).length) return null;
      const node = { kind: "branch", level: currentLevel, entries };
      const ref = await writeDocument(stage, node);
      nodes.set(ref, node);
      return ref;
    };
    const key = await replace(tree?.key, level, leafRefs);
    return key ? { key, level } : null;
  }

  function dirtyRows(before, after) {
    if (before === after) return new Set();
    const dirty = new Set();
    for (const [id, row] of after) if (before.get(id) !== row) dirty.add(id);
    for (const id of before.keys()) if (!after.has(id)) dirty.add(id);
    return dirty;
  }

  function rememberConfirmed(stage) {
    if (!syncStage || syncStage === stage || !stage.confirmed) return;
    for (const [id, fields] of stage.confirmed) {
      syncStage.confirmed.set(id, { ...syncStage.confirmed.get(id), ...fields });
    }
  }

  // This synchronous publication is reached only after the exact root is known
  // durable. Readers holding a row already own its immutable metadata/body lease.
  function publishCommit(stage, next) {
    if (next.preparedSnapshot) {
      snapshot = { ...next.preparedSnapshot, root: next.root };
      countIndexes.set(snapshot, next.counts);
      for (const ref of [...stage.gcCandidates, ...stage.refs]) if (!snapshot.refs.has(ref)) retired.add(ref);
      stages.delete(stage);
      scheduleGc();
      return;
    }
    const { root, maps, additions, replaced, bodyAdded, bodyRemoved, counts, articleChanges } = next;
    if (syncStage && syncStage !== stage) {
      for (const ref of [...replaced, ...bodyRemoved, ...bodyAdded, ...additions.keys()]) syncStage.gcCandidates.add(ref);
    }
    const nodes = stage.base.nodes;
    const refs = stage.base.refs;
    for (const ref of replaced) { nodes.delete(ref); refs.delete(ref); retired.add(ref); }
    for (const [ref, node] of additions) {
      if (!replaced.has(ref)) { nodes.set(ref, node); refs.add(ref); }
    }
    for (const ref of bodyRemoved) { refs.delete(ref); retired.add(ref); }
    for (const ref of bodyAdded) refs.add(ref);
    if (articleChanges) {
      for (const [id, row] of articleChanges) {
        if (row) maps.articles.set(id, row); else maps.articles.delete(id);
      }
    }
    for (const ref of stage.refs) if (!refs.has(ref)) retired.add(ref);
    snapshot = { root, maps, nodes, refs };
    if (counts) countIndexes.set(snapshot, counts);
    countValue(snapshot);
    rememberConfirmed(stage);
    stages.delete(stage);
    scheduleGc();
  }

  async function buildDelta(stage, maps, articleChanges) {
    checkStage(stage);
    const additions = new Map();
    const replaced = new Set();
    const nodes = { get: (key) => additions.get(key) ?? stage.base.nodes.get(key), set: (key, node) => additions.set(key, node) };
    const tables = {};
    const bodyAdded = new Set();
    const bodyRemoved = new Set();
    for (const table of TABLES) {
      const dirty = table === "articles" && articleChanges ? new Set(articleChanges.keys()) : dirtyRows(stage.base.maps[table], maps[table]);
      const view = table === "articles" && articleChanges
        ? { get: (id) => articleChanges.has(id) ? articleChanges.get(id) : maps.articles.get(id) } : maps[table];
      if (table === "articles") for (const id of dirty) {
        const before = stage.base.maps.articles.get(id)?.bodyRef;
        const after = view.get(id)?.bodyRef;
        if (before !== after) { if (before) bodyRemoved.add(before); if (after) bodyAdded.add(after); }
      }
      tables[table] = await updateTree(stage, table, view, nodes, dirty, replaced);
    }
    await flush(stage);
    return { maps, tables, additions, replaced, bodyAdded, bodyRemoved, articleChanges };
  }

  async function commit(stage, maps, { syncedAt = stage.base.root.lastSyncTime, account = stage.base.root.account,
    retireLegacy = false, counts, articleChanges } = {}) {
    checkStage(stage);
    if (snapshot !== stage.base) throw fail("缓存已更新，请重新同步。", "BUSY");
    const delta = await buildDelta(stage, maps, articleChanges);
    const root = { version: 3, revision: stage.base.root.revision + 1, transaction: stage.token,
      account, tables: delta.tables, lastSyncTime: syncedAt };
    return commitRoot(stage, { ...delta, root, counts }, retireLegacy);
  }

  async function commitRoot(stage, next, retireLegacy = false) {
    const { root } = next;
    if (!Number.isSafeInteger(root.revision)) throw invalid();
    const change = { set: [{ key: ROOT_KEY, value: root }], remove: retireLegacy ? [LEGACY_MANIFEST_KEY] : [] };
    try { await native("apply", [change], stage.epoch); }
    catch (error) {
      checkStage(stage);
      // Lost replies are resolved by transaction ID, never by replaying a root.
      let observed;
      try { [observed] = await readMany([ROOT_KEY], stage.epoch); }
      catch (cause) {
        checkStage(stage);
        uncertainCommit = { stage, next };
        sweepVersion += 1;
        throw Object.assign(new Error("缓存提交结果暂时无法确认，请恢复连接后重试。", { cause }), { code: "CACHE_COMMIT_UNCERTAIN" });
      }
      if (!same(observed, root)) throw error;
    }
    checkStage(stage);
    publishCommit(stage, next);
    return metaValue();
  }

  async function settleCommit() {
    if (!uncertainCommit) return;
    const pending = uncertainCommit;
    const [root] = await readMany([ROOT_KEY]);
    if (same(root, pending.next.root)) publishCommit(pending.stage, pending.next);
    else if (!same(root, pending.stage.base.root) && !(root === null && pending.stage.base.root.revision === 0)) {
      if (!root || !same(root.account, pending.stage.base.root.account)) throw cancelled();
      snapshot = await restore(root, epoch);
    }
    stages.delete(pending.stage);
    uncertainCommit = null;
    scheduleGc();
  }

  function releaseStage(stage) {
    if (uncertainCommit?.stage === stage) return;
    if (stages.delete(stage) && stage.refs.size) { sweepVersion += 1; scheduleGc(); }
  }

  async function articleRow(stage, article, previous) {
    if (!record(article) || !validId(article.id)) throw invalid();
    const body = { content: article.content ?? "", enclosures: article.enclosures ?? [] };
    if (typeof body.content !== "string" || !Array.isArray(body.enclosures)) throw invalid();
    if (previous?.bodyRef) stage.retainedRefs.add(previous.bodyRef);
    const hash = await digest(body);
    checkStage(stage);
    const bodyRef = previous?.bodyDigest === hash ? previous.bodyRef : await writeDocument(stage, body, true);
    const { content, enclosures, feed, originalContent, bodyRef: ignoredRef, ...metadata } = article;
    const derived = previous?.bodyDigest === hash && previous.title === article.title && previous.url === article.url
      ? { titleText: previous.titleText, previewText: previous.previewText, coverUrl: previous.coverUrl }
      : deriveMetadata(article);
    return { ...metadata, ...derived, bodyRef, bodyDigest: hash };
  }

  function mapRows(table, values, before = new Map()) {
    if (!Array.isArray(values)) throw invalid();
    const result = new Map();
    for (const row of values) {
      if (!record(row)) throw invalid();
      const id = keyOf(table, row);
      if (!validId(id) || result.has(id)) throw invalid();
      result.set(id, same(before.get(id), row) ? before.get(id) : clone(row));
    }
    return result;
  }

  function initialize(account) {
    if (clearing) return clearing.then(() => initialize(account));
    if (clearingFailed) return Promise.reject(cancelled());
    const callerEpoch = epoch;
    if (!loaded) {
      const expectedEpoch = epoch;
      loaded = (async () => {
        const [root] = await readMany([ROOT_KEY], expectedEpoch);
        if (root !== null) {
          if (account !== undefined && root.account !== null && !same(account, root.account)) throw cancelled();
          const restored = await restore(root, expectedEpoch);
          checkEpoch(expectedEpoch);
          snapshot = restored;
          countValue(snapshot);
          if (uncertainCommit) { stages.delete(uncertainCommit.stage); uncertainCommit = null; }
        } else {
          const legacy = await loadLegacyCache((keys) => readMany(keys, expectedEpoch));
          checkEpoch(expectedEpoch);
          snapshot = { root: emptyRoot(account ?? null), maps: emptyMaps(), nodes: new Map(), refs: new Set() };
          if (legacy) {
            const stage = newStage();
            try {
              const maps = emptyMaps();
              for (const article of legacy.articles) maps.articles.set(article.id, await articleRow(stage, article));
              for (const table of TABLES.slice(1)) maps[table] = mapRows(table, legacy[table]);
              await commit(stage, maps, { syncedAt: legacy.meta.lastSyncTime ?? null, account: account ?? null, retireLegacy: true });
            } finally { releaseStage(stage); }
          }
        }
        checkEpoch(expectedEpoch);
        scheduleGc();
        return metaValue();
      })().catch((error) => { loaded = null; throw error; });
    }
    return loaded.then(() => {
      checkEpoch(callerEpoch);
      if (account !== undefined && snapshot.root.account !== null && !same(account, snapshot.root.account)) throw cancelled();
      if (account !== undefined && account !== null && snapshot.root.account === null) {
        if (snapshot.maps.articles.size || snapshot.maps.feeds.size) throw cancelled();
        snapshot = { ...snapshot, root: { ...snapshot.root, account: clone(account) } };
      }
      return metaValue();
    });
  }

  const metaValue = () => clone({ version: 3, revision: snapshot.root.revision,
    lastSyncTime: snapshot.root.lastSyncTime, account: snapshot.root.account });
  async function readSnapshot() { const expectedEpoch = epoch; await initialize(); checkEpoch(expectedEpoch); return snapshot; }
  async function selectSnapshot(select) {
    const expectedEpoch = epoch;
    await initialize();
    checkEpoch(expectedEpoch);
    return select(snapshot);
  }
  const matches = (row, query = {}) => (!query.feedIds || query.feedIds.has(row.feedId)) &&
    (!query.ids || query.ids.has(row.id)) && (query.filter === "unread" ? row.status === "unread" :
      query.filter === "starred" ? row.starred === 1 : row.status !== "removed") &&
    (query.statusNot === undefined || row.status !== query.statusNot) &&
    (!query.keyword || row.title?.toLowerCase().includes(query.keyword.toLowerCase()));
  const selectedRows = (current, wanted) => wanted.ids
    ? [...wanted.ids].map((id) => current.maps.articles.get(id)).filter(Boolean) : current.maps.articles.values();
  const criteria = (query = {}) => ({ ...query, feedIds: query.feedIds ? new Set(query.feedIds) : null, ids: query.ids ? new Set(query.ids) : null });
  function countValue(current, feedIds) {
    if (!countIndexes.has(current)) {
      options.onIndexBuild?.({ kind: "counts", rows: current.maps.articles.size });
      const unread = {};
      const starred = {};
      for (const id of current.maps.feeds.keys()) { unread[id] = 0; starred[id] = 0; }
      for (const row of current.maps.articles.values()) {
        if (row.status === "unread") unread[row.feedId] = (unread[row.feedId] || 0) + 1;
        if (row.starred === 1) starred[row.feedId] = (starred[row.feedId] || 0) + 1;
      }
      countIndexes.set(current, { unread, starred });
    }
    const counts = countIndexes.get(current);
    if (!feedIds) return clone(counts);
    return { unread: Object.fromEntries(feedIds.map((id) => [id, counts.unread[id] || 0])),
      starred: Object.fromEntries(feedIds.map((id) => [id, counts.starred[id] || 0])) };
  }

  function scheduleGc() {
    if (options.autoGc === false) return;
    if (gcPromise) { gcAgain = true; return; }
    if (gcTimer !== null) return;
    gcTimer = setTimeout(() => { gcTimer = null; collectGarbage().catch(() => {}); }, 1000);
    gcTimer?.unref?.();
  }

  function protectedKey(key) {
    if (key === ROOT_KEY) return true;
    const ref = key.replace(/\.p\d+$/, "");
    return snapshot.refs.has(ref) || pins.has(ref) || [...stages].some((stage) => stage.refs.has(ref) || stage.retainedRefs.has(ref));
  }

  function collectGarbage() {
    if (gcPromise) return gcPromise;
    const expectedEpoch = epoch;
    gcPromise = (async () => {
      if (loaded) await loaded;
      if (uncertainCommit) return;
      const [root] = await readMany([ROOT_KEY], expectedEpoch);
      if (!root || root.version !== 3 || !same(root, snapshot.root)) return;
      let pending = [];
      let bytes = 32;
      const skippedRefs = new Set();
      const flushRemove = async () => {
        if (!pending.length) return;
        const change = { remove: pending };
        pending = []; bytes = 32;
        const intent = () => {
          if (uncertainCommit) return false;
          change.remove = change.remove.filter((key) => {
            if (!protectedKey(key)) return true;
            skippedRefs.add(key.replace(/\.p\d+$/, ""));
            return false;
          });
          return change.remove.length > 0;
        };
        try { await native("apply", [change], expectedEpoch, intent); }
        catch (error) { sweepVersion += 1; scheduleGc(); throw error; }
        await yieldBackground(expectedEpoch);
      };
      const removeKey = async (key) => {
        const size = jsonBytes(key) + 1;
        if (pending.length && (pending.length >= BATCH_KEYS || bytes + size > BATCH_BYTES)) await flushRemove();
        if (!uncertainCommit && !protectedKey(key)) { pending.push(key); bytes += size; }
      };
      if (sweptVersion !== sweepVersion) {
        const version = sweepVersion;
        const keys = await native("keys", [], expectedEpoch);
        for (let index = 0; index < keys.length; index += 1) {
          if (uncertainCommit) return;
          const key = keys[index];
          if (key.startsWith(CACHE_PREFIX) || key.startsWith(LEGACY_PREFIX)) await removeKey(key);
          if (index && index % BATCH_KEYS === 0) await yieldBackground(expectedEpoch);
        }
        await flushRemove();
        if (!uncertainCommit) sweptVersion = version;
      } else {
        // Coalesce keys across retired documents. A state update no longer pays
        // one native remove transaction per leaf/branch/body reference.
        const candidates = [...retired].filter((ref) => !protectedKey(ref));
        const completed = [];
        for (let offset = 0; offset < candidates.length; offset += BATCH_KEYS) {
          if (uncertainCommit) return;
          const refs = candidates.slice(offset, offset + BATCH_KEYS);
          const headers = await readMany(refs, expectedEpoch);
          for (let index = 0; index < refs.length; index += 1) {
            const ref = refs[index]; const header = headers[index];
            if (protectedKey(ref)) continue;
            if (header === null) { retired.delete(ref); continue; }
            if (!record(header) || header.format !== 1 ||
                (typeof header.text !== "string" && (!Number.isSafeInteger(header.parts) || header.parts < 1 ||
                  !Array.isArray(header.partBytes) || header.partBytes.length !== header.parts))) {
              sweepVersion += 1; retired.delete(ref); scheduleGc(); continue;
            }
            // Delete the header last, so a failed earlier batch is recoverable.
            if (typeof header.text !== "string") for (let part = 0; part < header.parts; part += 1) await removeKey(`${ref}.p${part}`);
            await removeKey(ref);
            completed.push(ref);
          }
          await yieldBackground(expectedEpoch);
        }
        await flushRemove();
        if (!uncertainCommit) for (const ref of completed) if (!protectedKey(ref) && !skippedRefs.has(ref)) retired.delete(ref);
      }
    })().finally(() => { gcPromise = null; if (gcAgain) { gcAgain = false; scheduleGc(); } });
    return gcPromise;
  }

  async function patchState(patches) {
    return enqueue(async () => {
      await initialize();
      await settleCommit();
      if (!Array.isArray(patches)) throw invalid();
      const stage = newStage({ confirmed: new Map() });
      const changes = new Map();
      const counts = countValue(snapshot);
      try {
        for (const patch of patches) {
          if (!record(patch) || !validId(patch.id) || (patch.status !== undefined && !["read", "unread", "removed"].includes(patch.status)) ||
              (patch.starred !== undefined && patch.starred !== 0 && patch.starred !== 1)) throw invalid();
          const previous = changes.has(patch.id) ? changes.get(patch.id) : snapshot.maps.articles.get(patch.id);
          if (!previous) continue;
          const fields = { ...(patch.status === undefined ? {} : { status: patch.status }), ...(patch.starred === undefined ? {} : { starred: patch.starred }) };
          stage.confirmed.set(patch.id, { ...stage.confirmed.get(patch.id), ...fields });
          const next = { ...previous, ...fields };
          changes.set(patch.id, next.status === "removed" ? null : next);
        }
        for (const [id, after] of changes) {
          const before = stage.base.maps.articles.get(id);
          if (same(before, after)) { changes.delete(id); continue; }
          for (const [row, direction] of [[before, -1], [after, 1]]) {
            if (!row) continue;
            if (row.status === "unread") counts.unread[row.feedId] = (counts.unread[row.feedId] || 0) + direction;
            if (row.starred === 1) counts.starred[row.feedId] = (counts.starred[row.feedId] || 0) + direction;
          }
        }
        if (changes.size) await commit(stage, snapshot.maps, { counts, articleChanges: changes });
        else rememberConfirmed(stage);
        return { articles: [...changes.keys()].map((id) => publicRow(snapshot.maps.articles.get(id))).filter(Boolean),
          counts: countValue(snapshot), revision: snapshot.root.revision };
      } finally { releaseStage(stage); }
    });
  }

  async function updateCatalog(change) {
    return enqueue(async () => {
      await initialize();
      await settleCommit();
      if (syncStage) throw fail("同步正在保存，请稍后重试。", "BUSY");
      const stage = newStage();
      try {
        const maps = { ...snapshot.maps };
        for (const table of TABLES.slice(1)) if (change[table] !== undefined) maps[table] = mapRows(table, change[table], maps[table]);
        if (change.upsertFeedIcons !== undefined) {
          maps.feedIcons = new Map(maps.feedIcons);
          for (const [id, icon] of mapRows("feedIcons", change.upsertFeedIcons, maps.feedIcons)) maps.feedIcons.set(id, icon);
        }
        const removed = new Set(change.removeFeedIds || []);
        if (removed.size) {
          maps.feeds = new Map([...maps.feeds].filter(([id]) => !removed.has(id)));
          maps.articles = new Map([...maps.articles].filter(([, row]) => !removed.has(row.feedId)));
          maps.feedIcons = new Map([...maps.feedIcons].filter(([id]) => !removed.has(id)));
        }
        if (change.removeIconIds?.length) {
          const ids = new Set(change.removeIconIds);
          maps.feedIcons = new Map([...maps.feedIcons].filter(([id]) => !ids.has(id)));
        }
        await commit(stage, maps);
        return metaValue();
      } finally { releaseStage(stage); }
    });
  }

  async function prepareSync(settings = {}) {
    return enqueue(async () => {
      await initialize(settings.account);
      await settleCommit();
      if (syncStage) throw fail("已有同步正在保存。", "BUSY");
      const syncedAt = settings.syncedAt instanceof Date ? settings.syncedAt.toISOString() : settings.syncedAt ?? null;
      if (syncedAt !== null && (typeof syncedAt !== "string" || !Number.isFinite(Date.parse(syncedAt)))) throw invalid();
      // full is a server fetch policy, not permission to discard readable local
      // history after a legacy checkpoint reset. Truly first sync starts empty.
      syncStage = newStage({ syncedAt, account: settings.account ?? snapshot.root.account, background: true, confirmed: new Map(), gcCandidates: new Set() });
      return syncStage.token;
    });
  }

  function requireSync(token) {
    if (!syncStage || syncStage.token !== token) throw cancelled();
    checkStage(syncStage);
    return syncStage;
  }

  async function applySyncBatch(token, rows) {
    // Body compression/staging is independent of interactive root commits.
    const operation = stagingWriter.then(async () => {
      const stage = requireSync(token);
      if (stage.preparing || stage.prepared) throw fail("同步结果已完成暂存。", "BUSY");
      if (!Array.isArray(rows)) throw invalid();
      for (let index = 0; index < rows.length; index += 1) {
        if (index && index % 32 === 0) await yieldBackground(stage.epoch);
        checkStage(stage);
        const article = rows[index];
        if (!record(article) || !validId(article.id)) throw invalid();
        const changedAt = Date.parse(article.changed_at);
        const previousTime = stage.changesAt.get(article.id);
        if (Number.isFinite(changedAt)) {
          if (previousTime !== undefined && changedAt < previousTime) continue;
          stage.changesAt.set(article.id, changedAt);
        }
        if (article.status === "removed") stage.changes.set(article.id, null);
        else {
          const previous = stage.changes.has(article.id) ? stage.changes.get(article.id) : snapshot.maps.articles.get(article.id);
          const row = await articleRow(stage, article, previous);
          stage.changes.set(article.id, same(previous, row) ? previous : row);
        }
      }
      await flush(stage);
      return { staged: stage.changes.size };
    });
    stagingWriter = operation.catch(() => {});
    return operation;
  }

  const overlayFields = (row, fields) => fields && Object.entries(fields).some(([key, value]) => row[key] !== value)
    ? { ...row, ...fields } : row;

  function assertSyncConflicts(stage, maps) {
    for (const [id, fields] of stage.confirmed) {
      const incoming = stage.changes.has(id) ? stage.changes.get(id) : maps.articles.get(id);
      if (!incoming || fields.status === "removed" || !maps.feeds.has(incoming.feedId)) {
        throw fail("同步期间文章被删除，已保留已确认的操作，请重新同步。", "SYNC_STATE_CONFLICT");
      }
    }
  }

  function applyDeltaToCandidate(base, delta) {
    const { maps, tables, additions, replaced, bodyAdded, bodyRemoved, articleChanges } = delta;
    for (const ref of replaced) { base.nodes.delete(ref); base.refs.delete(ref); }
    for (const [ref, node] of additions) if (!replaced.has(ref)) { base.nodes.set(ref, node); base.refs.add(ref); }
    for (const ref of bodyRemoved) base.refs.delete(ref);
    for (const ref of bodyAdded) base.refs.add(ref);
    if (articleChanges) for (const [id, row] of articleChanges) {
      if (row) maps.articles.set(id, row); else maps.articles.delete(id);
    }
    return { root: { ...base.root, tables }, maps, nodes: base.nodes, refs: base.refs };
  }

  async function prepareSyncCommit(token, catalog) {
    await stagingWriter;
    const stage = requireSync(token);
    if (stage.prepared) return { token };
    if (stage.preparing) { await stage.preparing; return { token }; }
    const preparation = (async () => {
      // Capture metadata/index references atomically, then build the candidate
      // outside the interactive writer. The copies belong only to this sync.
      await enqueue(async () => {
        await settleCommit();
        requireSync(token);
        stage.base = { root: snapshot.root, maps: { ...snapshot.maps, articles: new Map(snapshot.maps.articles) },
          nodes: new Map(snapshot.nodes), refs: new Set(snapshot.refs) };
        stage.retainedRefs = new Set(stage.base.refs);
      });
      checkStage(stage);
      const maps = { ...stage.base.maps,
        feeds: mapRows("feeds", catalog.feeds, stage.base.maps.feeds),
        categories: mapRows("categories", catalog.categories, stage.base.maps.categories),
        articles: new Map(stage.base.maps.articles),
      };
      assertSyncConflicts(stage, maps);
      for (const [id, row] of stage.changes) {
        if (row) maps.articles.set(id, overlayFields(row, stage.confirmed.get(id))); else maps.articles.delete(id);
      }
      for (const [id, row] of maps.articles) if (!maps.feeds.has(row.feedId) || row.status === "removed") maps.articles.delete(id);
      maps.feedIcons = new Map([...maps.feedIcons].filter(([id]) => maps.feeds.has(id)));
      const delta = await buildDelta(stage, maps);
      checkStage(stage);
      for (const ref of [...delta.replaced, ...delta.bodyRemoved]) stage.gcCandidates.add(ref);
      const candidate = applyDeltaToCandidate(stage.base, delta);
      stage.prepared = candidate;
      countValue(candidate);
    })();
    stage.preparing = preparation;
    try { await preparation; return { token }; }
    finally { stage.preparing = null; }
  }

  async function commitSync(token, catalog) {
    await prepareSyncCommit(token, catalog);
    return enqueue(async () => {
      await settleCommit();
      const stage = requireSync(token);
      try {
        const candidate = stage.prepared;
        assertSyncConflicts(stage, candidate.maps);
        const changes = new Map();
        const counts = countValue(candidate);
        // Only fields confirmed during this round override the fetched result.
        // Unrelated fetched fields, including bodies, remain in the candidate.
        for (const [id, fields] of stage.confirmed) {
          const before = candidate.maps.articles.get(id);
          const after = overlayFields(before, fields);
          if (before === after) continue;
          changes.set(id, after);
          for (const [row, direction] of [[before, -1], [after, 1]]) {
            if (row.status === "unread") counts.unread[row.feedId] = (counts.unread[row.feedId] || 0) + direction;
            if (row.starred === 1) counts.starred[row.feedId] = (counts.starred[row.feedId] || 0) + direction;
          }
        }
        stage.base = candidate;
        stage.background = false;
        const delta = await buildDelta(stage, candidate.maps, changes);
        for (const ref of delta.replaced) stage.gcCandidates.add(ref);
        const preparedSnapshot = applyDeltaToCandidate(candidate, delta);
        // The final root is based on the latest committed revision, after all
        // earlier interactions/recovery have completed in the writer queue.
        stage.base = snapshot;
        const root = { version: 3, revision: snapshot.root.revision + 1, transaction: stage.token,
          account: stage.account, tables: preparedSnapshot.root.tables, lastSyncTime: stage.syncedAt };
        return await commitRoot(stage, { root, preparedSnapshot, counts });
      } finally { releaseStage(stage); if (syncStage === stage) syncStage = null; }
    });
  }

  async function abortSync(token) {
    const abandoned = await enqueue(() => {
      if (!syncStage || syncStage.token !== token) return null;
      const stage = syncStage;
      stage.aborted = true;
      syncStage = null;
      // Capture the current work, then drain outside the writer. A pending
      // preparation may itself be waiting to capture its base on this queue.
      return { stage, pending: [stagingWriter, stage.preparing] };
    });
    if (!abandoned) return;
    await Promise.allSettled(abandoned.pending);
    releaseStage(abandoned.stage);
    scheduleGc();
  }

  function clear() {
    if (clearing) return clearing;
    clearingFailed = false;
    epoch += 1;
    queries.clear();
    const initializing = loaded;
    const operation = enqueue(async () => {
      await initializing?.catch(() => {});
      await stagingWriter.catch(() => {});
      await Promise.allSettled([...stages].map((stage) => stage.preparing));
      await gcPromise?.catch(() => {});
      stages.clear();
      syncStage = null;
      uncertainCommit = null;
      retired.clear();
      // Old readers are invalidated by the account epoch but may still hold a
      // native reply. Keep their retired references until their pins release.
      for (const ref of pins.keys()) retired.add(ref);
      sweepVersion += 1;
      const root = { ...emptyRoot(null, snapshot.root.revision + 1), transaction: `${tokenPrefix}.${++serial}`, cleared: true };
      const expectedEpoch = epoch;
      try { await native("apply", [{ set: [{ key: ROOT_KEY, value: root }], remove: [LEGACY_MANIFEST_KEY] }], expectedEpoch); }
      catch (error) {
        const [observed] = await readMany([ROOT_KEY], expectedEpoch);
        if (!same(observed, root)) throw error;
      }
      snapshot = { root, maps: emptyMaps(), nodes: new Map(), refs: new Set() };
      loaded = Promise.resolve(metaValue());
      await collectGarbage();
    });
    clearing = operation;
    operation.catch(() => { clearingFailed = true; }).finally(() => { if (clearing === operation) clearing = null; }).catch(() => {});
    return operation;
  }

  return {
    initialize,
    meta: () => selectSnapshot(() => metaValue()),
    async getCatalog(table) {
      if (!TABLES.slice(1).includes(table)) throw invalid();
      return selectSnapshot((current) => clone([...current.maps[table].values()]));
    },
    async getCatalogItem(table, id) {
      if (!TABLES.slice(1).includes(table)) throw invalid();
      return selectSnapshot((current) => clone(current.maps[table].get(Number(id)) ?? null));
    },
    updateCatalog,
    readMetadata: (id) => selectSnapshot((current) => publicRow(current.maps.articles.get(Number(id)))),
    async selectIds(query) {
      const wanted = criteria(query);
      return selectSnapshot((current) => [...selectedRows(current, wanted)].filter((row) => matches(row, wanted)).map((row) => row.id));
    },
    async selectMetadata(query) {
      const wanted = criteria(query);
      return selectSnapshot((current) => [...selectedRows(current, wanted)].filter((row) => matches(row, wanted)).map(publicRow));
    },
    counts: (feedIds) => selectSnapshot((current) => countValue(current, feedIds)),
    async readArticle(id) {
      const expectedEpoch = epoch;
      await initialize();
      checkEpoch(expectedEpoch);
      // Capturing the current reference and pinning it must be one synchronous
      // step. A commit/GC cannot slip between an awaited snapshot and this pin.
      const current = snapshot;
      const row = current.maps.articles.get(Number(id));
      if (!row) return null;
      pins.set(row.bodyRef, (pins.get(row.bodyRef) || 0) + 1);
      try {
        const [body] = await readDocuments([row.bodyRef], expectedEpoch);
        if (!record(body) || typeof body.content !== "string" || !Array.isArray(body.enclosures)) throw invalid();
        checkEpoch(expectedEpoch);
        return { ...publicRow(row), ...body, feed: clone(current.maps.feeds.get(row.feedId)) };
      } finally {
        const count = pins.get(row.bodyRef) - 1;
        if (count > 0) pins.set(row.bodyRef, count); else pins.delete(row.bodyRef);
        scheduleGc();
      }
    },
    async openQuery(query = {}) {
      const expectedEpoch = epoch;
      const current = await readSnapshot();
      checkEpoch(expectedEpoch);
      const wanted = criteria(query);
      const field = query.field ?? "published_at";
      if (!["published_at", "created_at"].includes(field)) throw fail("文章排序字段无效。", "INVALID_REQUEST");
      const rows = [...selectedRows(current, wanted)].filter((row) => matches(row, wanted));
      options.onIndexBuild?.({ kind: "query", rows: rows.length });
      rows.sort((a, b) => {
        const left = a[field] ?? ""; const right = b[field] ?? "";
        return left < right ? -1 : left > right ? 1 : a.id - b.id;
      });
      if ((query.direction ?? "desc") === "desc") rows.reverse();
      const pageSize = query.pageSize ?? 30;
      if (!Number.isSafeInteger(pageSize) || pageSize < 1) throw invalid();
      const queryId = `${epoch}.${++serial}`;
      queries.set(queryId, { revision: current.root.revision, ids: rows.map((row) => row.id), pageSize, epoch });
      return { queryId, revision: current.root.revision, total: rows.length };
    },
    async readQueryPage(queryId, page = 1) {
      const query = queries.get(queryId);
      if (!query || query.epoch !== epoch) throw cancelled();
      if (!Number.isSafeInteger(page) || page < 1) throw invalid();
      const offset = (page - 1) * query.pageSize;
      const ids = query.ids.slice(offset, offset + query.pageSize);
      return { items: ids.map((id) => publicRow(snapshot.maps.articles.get(id))).filter(Boolean), total: query.ids.length,
        hasMore: offset + ids.length < query.ids.length, revision: snapshot.root.revision };
    },
    async closeQuery(queryId) { queries.delete(queryId); },
    patchState, prepareSync, applySyncBatch, prepareSyncCommit, commitSync, abortSync, clear,
    // Explicit idle maintenance entry point also permits deterministic fault
    // injection in Node tests; production reads never await this operation.
    collectGarbage,
  };
}
