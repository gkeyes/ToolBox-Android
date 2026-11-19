import { decodeCacheValue } from "../cache-legacy.js";
import { boardArticleIds } from "./board.mjs";

export const REPORT_RETENTION_MS = 48 * 60 * 60 * 1000;
const PREFIX = "nextflux.board.history.v1.";
// Match the article cache's bounded document transport, with a separate
// namespace and commit pointer. Native apply() commits one batch atomically.
const PART_CHARS = 48 * 1024;
const BATCH_BYTES = 1024 * 1024 - 1024;
const BATCH_KEYS = 256;
const encoder = new TextEncoder();
const coordinators = new WeakMap();
const record = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const bytes = (value) => encoder.encode(JSON.stringify(value)).length;
const count = (value) => Number.isSafeInteger(value) && value >= 0;
const instant = (value) => Number.isSafeInteger(value) && value >= 0;
const failure = (code, message, cause) => Object.assign(new Error(message, cause ? { cause } : undefined), { code });
const invalid = (cause) => failure("REPORT_STORAGE_INVALID", "看板历史或任务数据损坏，请保留数据并重试。", cause);
const mismatch = () => failure("REPORT_STORAGE_ACCOUNT_MISMATCH", "看板数据所属账号不一致，已停止读取。请切回对应账号后重试。");
const badInput = (cause) => failure("INVALID_REQUEST", "看板数据格式不正确，无法保存。", cause);
const validId = (id) => typeof id === "string" && id.length > 0 && id.length <= 1024 && !/[\u0000-\u001f\u007f]/.test(id);

function serialize(value) {
  try {
    const text = JSON.stringify(value, (_key, item) => {
      if (typeof item === "number" && !Number.isFinite(item)) throw badInput();
      return item;
    });
    if (typeof text !== "string") throw badInput();
    return text;
  } catch (cause) { throw badInput(cause); }
}

async function hash(text) {
  if (!globalThis.crypto?.subtle) throw failure("UNSUPPORTED", "当前 WebView 不支持保存看板历史，请更新 Android System WebView 后重试。");
  const digest = await globalThis.crypto.subtle.digest("SHA-256", encoder.encode(text));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function sameRoot(a, b) {
  if (!a || !b) return a === b;
  return ["version", "accountKey", "revision", "transaction", "stateRef", "indexRef"].every((key) => a[key] === b[key]);
}

function validateReport(value, corrupt = false) {
  if (!record(value) || !validId(value.id) || !["interval", "today"].includes(value.kind) ||
      !instant(value.periodStart) || !instant(value.periodEnd) || value.periodEnd < value.periodStart ||
      !instant(value.completedAt) || !Number.isSafeInteger(value.completedAt + REPORT_RETENTION_MS)) {
    throw corrupt ? invalid() : badInput();
  }
}

function reportMeta(report) {
  const articles = Array.isArray(report.articles) ? report.articles : [];
  const topics = Array.isArray(report.topics) ? report.topics.filter(record) : [];
  // Keep this index small. Article snapshots and generated text live only in
  // immutable report documents, never in the root or list response.
  const meta = {
    id: report.id, kind: report.kind, periodStart: report.periodStart, periodEnd: report.periodEnd,
    completedAt: report.completedAt, expiresAt: report.expiresAt, unseen: report.unseen,
    total: count(report.total) ? report.total : articles.length,
    includedCount: count(report.includedCount) ? report.includedCount : boardArticleIds(topics.map((topic) =>
      ({ articles: Array.isArray(topic.articles) ? topic.articles : [] })), articles).length,
    topicCount: topics.length,
    digestStatus: String(report.digestStatus || report.digest?.status ||
      ((typeof report.digest === "string" ? report.digest.trim() : report.digest?.text) ? "done" : "idle")).slice(0, 64),
    cut: Boolean(report.cut),
  };
  if (typeof report.title === "string") meta.title = report.title.slice(0, 240);
  return meta;
}

export function createReportStorage(storage, accountKey, { now = Date.now } = {}) {
  if (!storage || typeof storage.apply !== "function" || typeof storage.keys !== "function" ||
      (typeof storage.get !== "function" && typeof storage.getMany !== "function")) throw badInput();
  if (typeof accountKey !== "string" || !accountKey || accountKey.length > 4096 || /[\u0000-\u001f\u007f]/.test(accountKey) ||
      typeof now !== "function") throw badInput();
  let accounts = coordinators.get(storage);
  if (!accounts) { accounts = new Map(); coordinators.set(storage, accounts); }
  let coordinator = accounts.get(accountKey);
  if (!coordinator) {
    coordinator = { tail: Promise.resolve(), retired: new Set(), needsSweep: true };
    accounts.set(accountKey, coordinator);
  }
  let prefix;
  let rootKey;
  let token;
  let sequence = 0;
  // Remember only the immutable state document this writer most recently staged.
  let lastStateDocument = null;
  const enqueue = (work) => {
    const operation = coordinator.tail.catch(() => {}).then(async () => {
      if (!prefix) {
        prefix = `${PREFIX}${await hash(JSON.stringify(accountKey))}.`;
        rootKey = `${prefix}root`;
        token = [...globalThis.crypto.getRandomValues(new Uint8Array(16))]
          .map((byte) => byte.toString(16).padStart(2, "0")).join("");
      }
      return work();
    }).catch((error) => { coordinator.needsSweep = true; throw error; });
    coordinator.tail = operation;
    // A caller may rely on flush() instead of awaiting each checkpoint.
    void operation.catch(() => {});
    return operation;
  };
  const validRef = (ref) => typeof ref === "string" && ref.startsWith(`${prefix}d.`) &&
    /^[a-f0-9]{32}\.[1-9]\d*$/.test(ref.slice(`${prefix}d.`.length));
  const clock = () => { const value = now(); if (!instant(value)) throw badInput(); return value; };

  async function readMany(keys) {
    try {
      const values = typeof storage.getMany === "function"
        ? await storage.getMany(keys) : await Promise.all(keys.map((key) => storage.get(key)));
      if (!Array.isArray(values) || values.length !== keys.length) throw invalid();
      return values;
    } catch (error) {
      if (error?.code !== "QUOTA_EXCEEDED" || keys.length < 2) throw error;
      const split = Math.ceil(keys.length / 2);
      return [...await readMany(keys.slice(0, split)), ...await readMany(keys.slice(split))];
    }
  }
  const read = async (key) => (await readMany([key]))[0];

  async function applyBatch(kind, entries) {
    if (!entries.length) return;
    try { await storage.apply({ [kind]: entries }); }
    catch (error) {
      if (error?.code !== "QUOTA_EXCEEDED" || entries.length < 2) throw error;
      const split = Math.ceil(entries.length / 2);
      await applyBatch(kind, entries.slice(0, split));
      await applyBatch(kind, entries.slice(split));
    }
  }
  async function writeBatches(kind, entries) {
    let batch = [];
    let size = 32;
    for (const entry of entries) {
      const added = bytes(entry) + 1;
      if (added + 32 > BATCH_BYTES) throw badInput();
      if (batch.length && (batch.length >= BATCH_KEYS || size + added > BATCH_BYTES)) {
        await applyBatch(kind, batch); batch = []; size = 32;
      }
      batch.push(entry); size += added;
    }
    await applyBatch(kind, batch);
  }

  function validateHeader(header, kind) {
    if (record(header) && typeof header.accountKey === "string" && header.accountKey !== accountKey) throw mismatch();
    if (!record(header) || header.accountKey !== accountKey || header.format !== 1 ||
        !["state", "index", "report"].includes(header.kind) || (kind && header.kind !== kind) || header.codec !== "json" ||
        !Number.isSafeInteger(header.parts) || header.parts < 1 || !Number.isSafeInteger(header.length) || header.length < 1 ||
        !Array.isArray(header.partBytes) || header.partBytes.length !== header.parts ||
        header.partBytes.some((size) => !Number.isSafeInteger(size) || size < 3 || size > PART_CHARS * 8 + 32) ||
        header.length > header.parts * (PART_CHARS + 1) || !/^[a-f0-9]{64}$/.test(header.sha256 || "") ||
        bytes(header) > BATCH_BYTES) throw invalid();
    return header;
  }

  async function readDocument(ref, kind) {
    if (!validRef(ref)) throw invalid();
    const header = validateHeader(await read(ref), kind);
    const chunks = [];
    let offset = 0;
    while (offset < header.parts) {
      const keys = [];
      let size = 32;
      do {
        keys.push(`${ref}.p${offset}`); size += header.partBytes[offset] + 1; offset += 1;
      } while (offset < header.parts && keys.length < BATCH_KEYS && size + header.partBytes[offset] + 1 <= BATCH_BYTES);
      const values = await readMany(keys);
      for (let index = 0; index < values.length; index += 1) {
        const value = values[index];
        if (typeof value !== "string" || !value.length || value.length > PART_CHARS + 1 ||
            bytes(value) !== header.partBytes[offset - values.length + index]) throw invalid();
        chunks.push(value);
      }
    }
    const text = chunks.join("");
    if (text.length !== header.length || await hash(text) !== header.sha256) throw invalid();
    try { return await decodeCacheValue(header.codec, text); }
    catch (cause) { throw invalid(cause); }
  }

  async function writeDocument(kind, text) {
    const ref = `${prefix}d.${token}.${++sequence}`;
    const writes = [];
    const partBytes = [];
    for (let offset = 0; offset < text.length;) {
      let end = Math.min(offset + PART_CHARS, text.length);
      // The native bridge stores UTF-8. Keep a valid surrogate pair in the
      // same chunk, as the article cache does, so it cannot become U+FFFD.
      if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1]) && /[\uDC00-\uDFFF]/.test(text[end])) end += 1;
      const value = text.slice(offset, end);
      writes.push({ key: `${ref}.p${partBytes.length}`, value }); partBytes.push(bytes(value)); offset = end;
    }
    const header = { format: 1, kind, accountKey, codec: "json", parts: partBytes.length,
      length: text.length, partBytes, sha256: await hash(text) };
    // Validate the bounded header before sending any of its chunks.
    if (bytes({ key: ref, value: header }) + 32 > BATCH_BYTES) throw badInput();
    try {
      await writeBatches("set", [...writes, { key: ref, value: header }]);
    } catch (error) { coordinator.needsSweep = true; throw error; }
    if (kind === "state") lastStateDocument = { ref, parts: header.parts };
    return ref;
  }

  async function readRoot() {
    const root = await read(rootKey);
    if (root === null) return null;
    if (record(root) && typeof root.accountKey === "string" && root.accountKey !== accountKey) throw mismatch();
    if (!record(root) || root.version !== 1 || root.accountKey !== accountKey || !Number.isSafeInteger(root.revision) ||
        root.revision < 1 || !/^[a-f0-9]{32}\.[1-9]\d*$/.test(root.transaction || "") ||
        (root.stateRef !== null && !validRef(root.stateRef)) || (root.indexRef !== null && !validRef(root.indexRef))) throw invalid();
    return root;
  }

  async function snapshot() {
    const root = await readRoot();
    const index = root?.indexRef ? await readDocument(root.indexRef, "index") : [];
    if (!Array.isArray(index)) throw invalid();
    const ids = new Set();
    const refs = new Set();
    for (const meta of index) {
      validateReport(meta, true);
      if (!validRef(meta.ref) || meta.expiresAt !== meta.completedAt + REPORT_RETENTION_MS ||
          ![meta.total, meta.includedCount, meta.topicCount].every(count) || typeof meta.digestStatus !== "string" ||
          meta.digestStatus.length > 64 || typeof meta.unseen !== "boolean" || typeof meta.cut !== "boolean" ||
          (meta.title !== undefined && (typeof meta.title !== "string" || meta.title.length > 240)) ||
          ids.has(meta.id) || refs.has(meta.ref)) throw invalid();
      ids.add(meta.id); refs.add(meta.ref);
    }
    return { root, index };
  }
  const liveRefs = ({ root, index }) => new Set([root?.stateRef, root?.indexRef, ...index.map((item) => item.ref)].filter(Boolean));

  async function publish(previous, { stateRef = previous.root?.stateRef || null, index = previous.index, indexRef } = {}) {
    if (indexRef === undefined) indexRef = previous.root?.indexRef || null;
    const revision = (previous.root?.revision || 0) + 1;
    if (!Number.isSafeInteger(revision)) throw invalid();
    const root = { version: 1, accountKey, revision, transaction: `${token}.${++sequence}`, stateRef, indexRef };
    if (!sameRoot(previous.root, await readRoot())) {
      coordinator.needsSweep = true;
      throw failure("REPORT_STORAGE_CONFLICT", "看板数据已被其他任务更新，请重试。");
    }
    try { await storage.apply({ set: [{ key: rootKey, value: root }] }); }
    catch (cause) {
      coordinator.needsSweep = true;
      let actual;
      try { actual = await readRoot(); }
      catch (readError) {
        // Never restore the old root: the failed reply may follow a durable
        // commit. A later call reads the native pointer again before writing.
        throw failure("REPORT_STORAGE_COMMIT_UNCERTAIN", "看板保存结果暂时无法确认，已保留数据，请重试。", readError);
      }
      if (!sameRoot(root, actual)) throw cause;
    }
    const next = { root, index };
    const live = liveRefs(next);
    for (const ref of liveRefs(previous)) if (!live.has(ref)) coordinator.retired.add(ref);
    return next;
  }

  async function collect(current, sweep = false, previousStateDocument = null) {
    const live = liveRefs(current);
    if (sweep || coordinator.needsSweep) {
      // A namespace scan is reserved for startup, explicit pruning and failed
      // writes. Checkpoints normally retire their known old documents directly.
      for (const ref of live) validateHeader(await read(ref));
      const keys = await storage.keys();
      if (!Array.isArray(keys) || keys.some((key) => typeof key !== "string")) throw invalid();
      const obsolete = keys.filter((key) => key.startsWith(prefix) && key !== rootKey &&
        !live.has(key.replace(/\.p\d+$/, "")));
      await writeBatches("remove", obsolete);
      coordinator.retired.clear();
      coordinator.needsSweep = false;
      return;
    }
    for (const ref of [...coordinator.retired]) {
      if (!live.has(ref)) {
        let parts;
        if (previousStateDocument?.ref === ref) {
          parts = previousStateDocument.parts;
        } else {
          const value = await read(ref);
          parts = value === null ? 0 : validateHeader(value).parts;
        }
        if (parts) {
          const keys = Array.from({ length: parts }, (_, part) => `${ref}.p${part}`);
          // If the batch splits, its recoverable header is still removed last.
          keys.push(ref);
          await writeBatches("remove", keys);
        }
      }
      coordinator.retired.delete(ref);
    }
  }
  const cleanup = async (current, previousStateDocument = null) => {
    try { await collect(current, false, previousStateDocument); }
    catch { coordinator.needsSweep = true; }
  };
  async function replaceIndex(previous, index) {
    const indexRef = index.length ? await writeDocument("index", serialize(index)) : null;
    return publish(previous, { index, indexRef });
  }
  async function expire(previous, at) {
    const index = previous.index.filter((meta) => meta.expiresAt > at);
    if (index.length === previous.index.length) return previous;
    const current = await replaceIndex(previous, index);
    await cleanup(current);
    return current;
  }
  const publicMeta = ({ ref: _ref, ...meta }) => meta;

  return {
    loadState: () => enqueue(async () => {
      const { root } = await snapshot();
      return root?.stateRef ? readDocument(root.stateRef, "state") : null;
    }),
    async saveState(state) {
      const text = serialize(state); // Freeze the input before joining the queue.
      return enqueue(async () => {
        const previous = await snapshot();
        const previousStateDocument = lastStateDocument;
        const stateRef = await writeDocument("state", text);
        const current = await publish(previous, { stateRef });
        await cleanup(current, previousStateDocument);
        return decodeCacheValue("json", text);
      });
    },
    async saveReport(value) {
      const report = JSON.parse(serialize(value));
      validateReport(report);
      return enqueue(async () => {
        const previous = await snapshot();
        const existing = previous.index.find((meta) => meta.id === report.id);
        report.completedAt = existing?.completedAt ?? report.completedAt;
        report.expiresAt = report.completedAt + REPORT_RETENTION_MS;
        report.unseen = report.unseen === undefined ? existing?.unseen ?? true : Boolean(report.unseen);
        const at = clock();
        const index = previous.index.filter((meta) => meta.id !== report.id && meta.expiresAt > at);
        if (report.expiresAt > at) {
          const meta = reportMeta(report);
          const ref = await writeDocument("report", serialize(report));
          index.push({ ...meta, ref });
        }
        if (index.length !== previous.index.length || report.expiresAt > at || existing) {
          const current = await replaceIndex(previous, index);
          await cleanup(current);
        }
        // A recovered completed job can already be older than 48 hours. It is
        // acknowledged without changing its timestamp or republishing it.
        return report;
      });
    },
    listReports: () => enqueue(async () => {
      const current = await expire(await snapshot(), clock());
      if (coordinator.needsSweep) await collect(current);
      return current.index.map(publicMeta).sort((a, b) => b.completedAt - a.completedAt || a.id.localeCompare(b.id));
    }),
    readReport: (id) => enqueue(async () => {
      if (!validId(id)) throw badInput();
      const current = await expire(await snapshot(), clock());
      const meta = current.index.find((entry) => entry.id === id);
      if (!meta) return null;
      const report = await readDocument(meta.ref, "report");
      validateReport(report, true);
      const expected = reportMeta(report);
      const actual = publicMeta(meta);
      if (report.expiresAt !== meta.expiresAt || report.unseen !== meta.unseen ||
          Object.keys(expected).length !== Object.keys(actual).length ||
          Object.keys(expected).some((key) => expected[key] !== actual[key])) throw invalid();
      return report;
    }),
    removeReport: (id) => enqueue(async () => {
      if (!validId(id)) throw badInput();
      const previous = await snapshot();
      const index = previous.index.filter((meta) => meta.id !== id);
      if (index.length === previous.index.length) return false;
      const current = await replaceIndex(previous, index);
      await cleanup(current);
      return true;
    }),
    prune: () => enqueue(async () => {
      const previous = await snapshot();
      const current = await expire(previous, clock());
      try { await collect(current, true); }
      catch (error) { coordinator.needsSweep = true; throw error; }
      return previous.index.length - current.index.length;
    }),
    clear: () => enqueue(async () => {
      const previous = await snapshot();
      const current = await publish(previous, { stateRef: null, indexRef: null, index: [] });
      try {
        await collect(current, true);
        await writeBatches("remove", [rootKey]);
      } catch (error) { coordinator.needsSweep = true; throw error; }
    }),
    flush: () => coordinator.tail,
  };
}
