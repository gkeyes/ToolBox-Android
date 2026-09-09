import test from "node:test";
import assert from "node:assert/strict";
import { ROOT_KEY, CACHE_PREFIX, jsonBytes } from "../src/toolbox/cache.js";
import { LEGACY_PREFIX } from "../src/toolbox/cache-legacy.js";
import { fakeStorage, engine, seed, article, rootWrite, document, storedArticles } from "./cache-fixtures.mjs";

const checkpoint = "2026-09-08T12:30:00.000Z";
const legacyManifestKey = `${LEGACY_PREFIX}manifest`;

function legacySnapshot(store, version) {
  const rows = [article(1, { title: "旧标题 &amp; 标记", content: "<p>旧正文 😀 与 Unicode</p>" })];
  const values = {
    articles: rows,
    feeds: [{ id: 1, title: "Legacy feed", category: { id: 4 } }],
    categories: [{ id: 4, title: "Legacy category" }],
    feedIcons: [{ feedId: 1, data: "data:image/png;base64,aGVsbG8=" }],
    meta: { lastSyncTime: checkpoint },
  };
  const manifest = { generation: 7, tables: {} };
  if (version === 2) manifest.version = 2;
  for (const [table, value] of Object.entries(values)) {
    const text = JSON.stringify(value);
    const keys = [];
    for (let offset = 0; offset < text.length; offset += 23) {
      const key = `${LEGACY_PREFIX}${table}.7.${keys.length}`;
      keys.push(key);
      store.data.set(key, text.slice(offset, offset + 23));
    }
    manifest.tables[table] = version === 2 ? { codec: "json", keys } : keys;
  }
  store.data.set(legacyManifestKey, manifest);
  return { rows, values };
}

for (const version of [1, 2]) {
  test(`legacy v${version} manifest and fragments migrate with the correct checkpoint policy`, async () => {
    const store = fakeStorage();
    const legacy = legacySnapshot(store, version);
    const cache = engine(store);
    const meta = await cache.initialize();
    assert.equal(meta.version, 3);
    assert.equal(meta.lastSyncTime, version === 1 ? null : checkpoint);
    assert.deepEqual(await cache.selectIds(), [1]);
    const restored = await cache.readArticle(1);
    assert.equal(restored.content, legacy.rows[0].content);
    assert.equal(restored.titleText, "旧标题 & 标记");
    for (const table of ["feeds", "categories", "feedIcons"]) {
      assert.deepEqual(await cache.getCatalog(table), legacy.values[table]);
    }
    const root = store.data.get(ROOT_KEY);
    assert.equal(typeof root.tables.articles.key, "string");
    assert.ok(Number.isInteger(root.tables.articles.level));
    assert.equal(store.data.has(legacyManifestKey), false);
    const reopened = engine(store);
    assert.equal((await reopened.meta()).lastSyncTime, version === 1 ? null : checkpoint);
    assert.equal((await reopened.readArticle(1)).content, legacy.rows[0].content);
  });
}

test("failed migration leaves legacy manifest and fragments byte-identical and a fresh engine can retry", async () => {
  for (const version of [1, 2]) {
    const store = fakeStorage();
    const legacy = legacySnapshot(store, version);
    const before = new Map([...store.data].map(([key, value]) => [key, JSON.stringify(value)]));
    store.failWhen(rootWrite);
    const failed = engine(store);
    await assert.rejects(failed.initialize(), { code: "INTERNAL_ERROR" });
    assert.equal(store.data.has(ROOT_KEY), false);
    await failed.collectGarbage();
    for (const [key, bytes] of before) assert.equal(JSON.stringify(store.data.get(key)), bytes, key);

    store.failWhen(() => false);
    const recovered = engine(store);
    assert.equal((await recovered.initialize()).lastSyncTime, version === 1 ? null : checkpoint);
    assert.equal((await recovered.readArticle(1)).content, legacy.rows[0].content);
    assert.equal(store.data.has(legacyManifestKey), false);
    await recovered.collectGarbage();
    assert.equal([...store.data.keys()].some((key) => key.startsWith(LEGACY_PREFIX)), false);
    assert.equal((await engine(store).readArticle(1)).content, legacy.rows[0].content);
  }
});

test("a lost root write reply succeeds when reading the exact committed root confirms it", async () => {
  const store = fakeStorage();
  const cache = engine(store);
  await seed(cache, [article(1)]);
  const beforeRevision = store.data.get(ROOT_KEY).revision;
  store.resetCalls();
  store.afterApply((change) => {
    if (rootWrite(change)) throw new Error("root committed but reply was lost");
  });
  const result = await cache.patchState([{ id: 1, status: "read" }]);
  assert.equal(result.revision, beforeRevision + 1);
  assert.equal(store.writes.filter(rootWrite).length, 1);
  assert.ok(store.reads.some((keys) => keys.length === 1 && keys[0] === ROOT_KEY));
  assert.equal((await cache.readMetadata(1)).status, "read");
  assert.equal((await engine(store).readArticle(1)).status, "read");
});

test("uncertain commit protects new root data from GC and the next patch reconciles committed state", async () => {
  const store = fakeStorage();
  const cache = engine(store);
  await seed(cache, [article(1, { content: "old body" })]);
  const oldRoot = structuredClone(store.data.get(ROOT_KEY));
  let replyLost = false;
  store.afterApply((change) => {
    if (!rootWrite(change)) return;
    replyLost = true;
    throw new Error("root committed but reply was lost");
  });
  store.beforeGet((keys) => {
    if (replyLost && keys.includes(ROOT_KEY)) throw new Error("root verification unavailable");
  });
  await assert.rejects(seed(cache, [
    article(1, { content: "new committed body 😀", status: "read" }),
    article(2, { content: "new second article", starred: 1 }),
  ], { syncedAt: "2026-09-09T01:00:00.000Z" }), { code: "CACHE_COMMIT_UNCERTAIN" });
  const committedRoot = structuredClone(store.data.get(ROOT_KEY));
  assert.equal(committedRoot.revision, oldRoot.revision + 1);
  assert.notEqual(committedRoot.transaction, oldRoot.transaction);
  const protectedData = new Map([...store.data].filter(([key]) => key.startsWith(CACHE_PREFIX)));
  await cache.collectGarbage();
  assert.deepEqual(new Map([...store.data].filter(([key]) => key.startsWith(CACHE_PREFIX))), protectedData);

  store.beforeGet(undefined);
  store.afterApply(undefined);
  const result = await cache.patchState([{ id: 1, starred: 1 }, { id: 2, status: "read" }]);
  assert.equal(result.revision, committedRoot.revision + 1);
  assert.equal((await cache.meta()).lastSyncTime, "2026-09-09T01:00:00.000Z");
  const first = await cache.readArticle(1);
  assert.equal(first.content, "new committed body 😀");
  assert.equal(first.status, "read");
  assert.equal(first.starred, 1);
  const second = await cache.readArticle(2);
  assert.equal(second.content, "new second article");
  assert.equal(second.status, "read");
  assert.equal(second.starred, 1);
  await cache.collectGarbage();
  const reopened = engine(store);
  assert.equal((await reopened.readArticle(1)).content, first.content);
  assert.equal((await reopened.readArticle(2)).content, second.content);
});

test("JSON fallback preserves a large Unicode body across 48 KiB fragment boundaries", async () => {
  const originals = new Map(["CompressionStream", "DecompressionStream"].map((name) =>
    [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  try {
    for (const name of originals.keys()) Object.defineProperty(globalThis, name, { configurable: true, writable: true, value: undefined });
    const partChars = 48 * 1024;
    const content = "a".repeat(partChars - '{"content":"'.length - 1) + "😀" + "中".repeat(partChars) + "e\u0301 👩‍💻 \uD800 结束";
    const enclosures = [{ mime_type: "image/png", url: "/proxy/signature/image", title: "图片 😀" }];
    const store = fakeStorage();
    const cache = engine(store);
    await seed(cache, [article(3, { content, enclosures })]);
    const [row] = await storedArticles(store);
    const header = store.data.get(row.bodyRef);
    assert.equal(header.codec, "json");
    assert.ok(header.parts > 1);
    const parts = Array.from({ length: header.parts }, (_, index) => store.data.get(`${row.bodyRef}.p${index}`));
    assert.equal(parts[0].length, partChars + 1);
    assert.ok(parts[0].endsWith("😀"));
    for (const part of parts) assert.equal(new TextDecoder().decode(new TextEncoder().encode(part)), part);
    assert.equal(parts.join(""), JSON.stringify({ content, enclosures }));
    assert.deepEqual(await document(store, row.bodyRef), { content, enclosures });
    const restored = await engine(store).readArticle(3);
    assert.equal(restored.content, content);
    assert.deepEqual(restored.enclosures, enclosures);
  } finally {
    for (const [name, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    }
  }
});

test("native rate limits retry one full window for reads, writes and keys without duplicate state", async () => {
  const nowDescriptor = Object.getOwnPropertyDescriptor(Date, "now");
  const timeoutDescriptor = Object.getOwnPropertyDescriptor(globalThis, "setTimeout");
  let virtualNow = 0;
  let timerId = 0;
  const waits = [];
  try {
    Object.defineProperty(Date, "now", { configurable: true, writable: true, value: () => virtualNow });
    Object.defineProperty(globalThis, "setTimeout", {
      configurable: true, writable: true,
      value(callback, delay = 0, ...args) {
        const elapsed = Math.max(0, Number(delay) || 0);
        waits.push(elapsed);
        virtualNow += elapsed;
        queueMicrotask(() => callback(...args));
        return ++timerId;
      },
    });
    const store = fakeStorage();
    const attempts = { getMany: [], apply: [], keys: [] };
    const admit = (method) => {
      attempts[method].push(virtualNow);
      if (attempts[method].length === 1) throw Object.assign(new Error("admission window full"), { code: "RATE_LIMITED" });
    };
    store.beforeGet(() => admit("getMany"));
    store.beforeApply(() => admit("apply"));
    store.beforeKeys(() => admit("keys"));
    const cache = engine(store);
    const source = article(1);
    await seed(cache, [source]);
    assert.equal((await cache.readArticle(1)).content, source.content);
    assert.deepEqual(await cache.selectIds(), [1]);
    assert.equal(store.writes.filter(rootWrite).length, 1);
    const orphanKey = `${CACHE_PREFIX}d.orphan`;
    store.data.set(orphanKey, "uncommitted fragment");
    await cache.collectGarbage();
    assert.equal(store.data.has(orphanKey), false);
    assert.equal((await cache.readArticle(1)).content, source.content);
    await cache.clear();
    assert.deepEqual(await cache.selectIds(), []);
    assert.equal(await cache.readArticle(1), null);
    assert.equal(store.writes.filter(rootWrite).length, 2);
    for (const times of Object.values(attempts)) {
      assert.ok(times.length >= 2);
      assert.equal(times[1] - times[0], 60000);
    }
    assert.ok(waits.filter((delay) => delay === 1000).length >= 180);
  } finally {
    Object.defineProperty(Date, "now", nowDescriptor);
    if (timeoutDescriptor) Object.defineProperty(globalThis, "setTimeout", timeoutDescriptor);
    else delete globalThis.setTimeout;
  }
});

test("corrupt committed roots, catalogs and lazy bodies reject without deleting stored data", async () => {
  for (const corruption of ["root reference", "catalog directory", "gzip body", "missing body fragment"]) {
    const store = fakeStorage();
    const source = article(1, { content: "Committed body 😀" });
    await seed(engine(store), [source]);
    const root = structuredClone(store.data.get(ROOT_KEY));
    const [row] = await storedArticles(store);
    const bodyOnly = corruption === "gzip body" || corruption === "missing body fragment";
    if (corruption === "root reference") {
      root.tables.articles.key = "outside.cache.directory";
      store.data.set(ROOT_KEY, root);
    } else if (corruption === "catalog directory") {
      store.data.set(root.tables.feeds.key, { format: 1, codec: "json", text: JSON.stringify({ kind: "invalid-directory" }) });
    } else if (corruption === "gzip body") {
      store.data.set(row.bodyRef, { format: 1, codec: "gzip-base64", text: "YWJj" });
    } else {
      const text = JSON.stringify({ content: source.content, enclosures: source.enclosures });
      store.data.set(row.bodyRef, { format: 1, codec: "json", parts: 1, length: text.length, partBytes: [jsonBytes(text)] });
      store.data.delete(`${row.bodyRef}.p0`);
    }
    const committedData = structuredClone(store.data);
    store.resetCalls();
    const reopened = engine(store);
    if (bodyOnly) {
      await reopened.initialize();
      assert.equal((await reopened.readMetadata(1)).title, source.title);
      assert.equal((await reopened.getCatalog("feeds"))[0].id, 1);
      await assert.rejects(reopened.readArticle(1), { code: "CACHE_INVALID" }, corruption);
    } else {
      await assert.rejects(reopened.initialize(), { code: "CACHE_INVALID" }, corruption);
    }
    assert.deepEqual(store.data, committedData, corruption);
    assert.deepEqual(store.writes, [], corruption);
  }
});
