import assert from "node:assert/strict";
import test from "node:test";
import { createNativeHlsLoader } from "../src/toolbox/nativeHlsLoader.mjs";

const encode = value => new TextEncoder().encode(value);
const config = { timeout: 1000, loadPolicy: { maxLoadTimeMs: 1000, maxTimeToFirstByteMs: 500 } };
const context = overrides => ({ url: "https://media.example/master.m3u8", responseType: "text", type: "manifest", ...overrides });
const turn = () => new Promise(resolve => setImmediate(resolve));
function fakeNetwork({ bytes = encode("#EXTM3U\n片段.ts\n"), chunkSize = 3, headers = {}, status = 200 } = {}) {
  let offset = 0;
  const state = { opens: [], reads: 0, cancels: [] };
  const api = {
    async openStream(request, options) { state.opens.push({ request, options }); return { streamId: "resource", status, headers }; },
    async readStream() {
      state.reads += 1;
      const data = bytes.subarray(offset, offset + chunkSize);
      offset += data.byteLength;
      return { data, done: data.byteLength === 0 };
    },
    async cancelStream(id) { state.cancels.push(id); },
  };
  return { api, state };
}
function start(api, request = context(), options = {}, loadingConfig = config) {
  const Loader = createNativeHlsLoader({ network: () => api, ...options });
  const loader = new Loader();
  const events = [];
  let resolve;
  const finished = new Promise(done => { resolve = done; });
  const callbacks = {
    onSuccess: (...args) => { events.push({ type: "success", args }); resolve(events.at(-1)); },
    onError: (...args) => { events.push({ type: "error", args }); resolve(events.at(-1)); },
    onTimeout: (...args) => { events.push({ type: "timeout", args }); resolve(events.at(-1)); },
    onAbort: (...args) => { events.push({ type: "abort", args }); resolve(events.at(-1)); },
    onProgress: (...args) => events.push({ type: "progress", args }),
  };
  loader.load(request, loadingConfig, callbacks);
  return { loader, events, finished, callbacks };
}

test("manifest chunks preserve UTF-8, final URL, stats and readable cache headers", async () => {
  const bytes = encode("#EXTM3U\n片段.ts\n");
  const fixture = fakeNetwork({ bytes, headers: { "Content-Length": String(bytes.length), "X-ToolBox-Final-URL": "https://cdn.example/incorrect/", "x-toolbox-final-url": "https://cdn.example/path/master.m3u8", Age: "12", "Cache-Control": "max-age=20" } });
  let time = 5;
  const run = start(fixture.api, context(), { now: () => time++ });
  const result = await run.finished;
  assert.equal(result.type, "success");
  const [response, stats, request] = result.args;
  assert.equal(response.data, "#EXTM3U\n片段.ts\n");
  assert.equal(response.url, "https://cdn.example/path/master.m3u8");
  assert.equal(new URL("片段.ts", response.url).origin, "https://cdn.example");
  assert.equal(request, run.loader.context);
  assert.equal(stats, run.loader.stats);
  assert.equal(stats.loaded, bytes.length); assert.equal(stats.total, bytes.length);
  assert.ok(stats.loading.start < stats.loading.first && stats.loading.first < stats.loading.end);
  assert.equal(run.loader.getCacheAge(), 12);
  assert.equal(run.loader.getResponseHeader("CACHE-CONTROL"), "max-age=20");
  assert.equal(fixture.state.opens[0].request.maxResponseBytes, 1024 * 1024);
  assert.equal(fixture.state.opens[0].request.timeoutMs, 1000);
  assert.equal(fixture.state.cancels.length, 0);
});

test("end-exclusive ranges and binary views preserve exactly the requested bytes", async () => {
  const backing = new Uint8Array([99, 1, 2, 3, 4, 88]);
  const fixture = fakeNetwork({ bytes: backing.subarray(1, 5), status: 206, headers: { "Content-Range": "bytes 10-13/100", "Content-Length": "4" } });
  const run = start(fixture.api, context({ responseType: "arraybuffer", type: "media-fragment", rangeStart: 10, rangeEnd: 14, headers: { Authorization: "test-only-secret", Cookie: "test-only-cookie", "X-Auth-Token": "test-only-token", Referer: "https://account.example/", Accept: "video/mp2t", "If-Range": "fixture-etag", Range: "bytes=0-999" } }));
  const result = await run.finished;
  assert.equal(result.type, "success");
  assert.deepEqual([...new Uint8Array(result.args[0].data)], [1, 2, 3, 4]);
  assert.deepEqual(fixture.state.opens[0].request.headers, { Accept: "video/mp2t", "If-Range": "fixture-etag", Range: "bytes=10-13" });
  assert.equal(fixture.state.opens[0].request.maxResponseBytes, 32 * 1024 * 1024);
});

test("an explicit zero-start open range is retained; absent and zero sentinel ranges are omitted", async () => {
  const bytes = new Uint8Array([1, 2, 3, 4]);
  const fixture = fakeNetwork({ bytes, status: 206, headers: { "Content-Range": "bytes 0-3/4" } });
  const result = await start(fixture.api, context({ responseType: "arraybuffer", rangeStart: 0 })).finished;
  assert.equal(result.type, "success");
  assert.equal(fixture.state.opens[0].request.headers.Range, "bytes=0-");
  for (const range of [{}, { rangeStart: 0, rangeEnd: 0 }]) {
    const plain = fakeNetwork();
    assert.equal((await start(plain.api, context(range)).finished).type, "success");
    assert.equal(plain.state.opens[0].request.headers.Range, undefined);
  }
  for (const range of [{ rangeStart: -1 }, { rangeStart: 2, rangeEnd: 2 }, { rangeStart: 2, rangeEnd: 1 }, { rangeStart: 0.5 }]) {
    const invalid = fakeNetwork();
    const failure = await start(invalid.api, context(range)).finished;
    assert.equal(failure.type, "error");
    assert.equal(failure.args[2].nativeCode, "INVALID_RANGE");
    assert.equal(invalid.state.opens.length, 0);
  }
});

test("binary key requests receive binary data and a finite small resource budget", async () => {
  const fixture = fakeNetwork({ bytes: new Uint8Array(16).fill(7) });
  const result = await start(fixture.api, context({ responseType: "arraybuffer", type: "key" })).finished;
  assert.equal(result.type, "success"); assert.equal(result.args[0].data.byteLength, 16);
  assert.equal(fixture.state.opens[0].request.maxResponseBytes, 1024 * 1024);
});

test("native permission errors remain identifiable without copying native error messages", async () => {
  for (const code of ["PERMISSION_DENIED", "NOT_DECLARED"]) {
    const api = { ...fakeNetwork().api, async openStream() { throw Object.assign(new Error("private diagnostic must not escape"), { code }); } };
    const result = await start(api).finished;
    assert.equal(result.type, "error"); assert.equal(result.args[0].code, 0);
    assert.equal(result.args[2].nativeCode, code);
    assert.doesNotMatch(result.args[0].text, /private diagnostic/);
  }
});

test("unsafe initial or final URLs never succeed or follow another request", async () => {
  for (const url of ["http://media.example/a", "https://user@media.example/a", "data:text/plain,x", "/a"]) {
    const fixture = fakeNetwork();
    const result = await start(fixture.api, context({ url })).finished;
    assert.equal(result.type, "error"); assert.equal(result.args[2].nativeCode, "INVALID_URL");
    assert.equal(fixture.state.opens.length, 0);
  }
  const fixture = fakeNetwork({ headers: { "x-toolbox-final-url": "http://media.example/redirect" } });
  const result = await start(fixture.api).finished;
  await turn();
  assert.equal(result.type, "error"); assert.equal(result.args[2].nativeCode, "INVALID_URL");
  assert.equal(fixture.state.reads, 0); assert.deepEqual(fixture.state.cancels, ["resource"]);
});

test("HTTP failures and ignored or mismatched ranges cancel the stream", async () => {
  const fixtures = [
    { status: 403, headers: {}, request: context(), code: 403, native: "NETWORK_UNAVAILABLE" },
    { status: 200, headers: {}, request: context({ rangeStart: 2, rangeEnd: 5 }), code: 0, native: "INVALID_RANGE" },
    { status: 206, headers: { "Content-Range": "bytes 3-5/100" }, request: context({ rangeStart: 2, rangeEnd: 5 }), code: 0, native: "INVALID_RANGE" },
  ];
  for (const row of fixtures) {
    const fixture = fakeNetwork(row), result = await start(fixture.api, row.request).finished;
    await turn();
    assert.equal(result.type, "error"); assert.equal(result.args[0].code, row.code);
    assert.equal(result.args[2].nativeCode, row.native);
    assert.equal(fixture.state.reads, 0); assert.deepEqual(fixture.state.cancels, ["resource"]);
  }
});

test("declared and cumulative response limits stop reads and release the stream", async () => {
  for (const headers of [{ "Content-Length": "40" }, {}]) {
    const fixture = fakeNetwork({ bytes: new Uint8Array(40), headers, chunkSize: 5 });
    const result = await start(fixture.api, context(), { manifestLimit: 12 }).finished;
    await turn();
    assert.equal(result.type, "error"); assert.equal(result.args[2].nativeCode, "QUOTA_EXCEEDED");
    assert.ok(fixture.state.reads <= 3); assert.deepEqual(fixture.state.cancels, ["resource"]);
  }
});

test("truncated responses and malformed or nonadvancing chunks fail without looping", async () => {
  const truncated = fakeNetwork({ bytes: encode("#EXTM3U"), headers: { "Content-Length": "20" } });
  const result = await start(truncated.api).finished;
  assert.equal(result.type, "error"); assert.equal(result.args[2].nativeCode, "INCOMPLETE_RESPONSE");
  for (const chunk of [{ data: [], done: false }, { data: new Uint8Array(), done: false }, { data: encode("abc") }]) {
    const fixture = fakeNetwork(); fixture.api.readStream = async () => chunk;
    const result = await start(fixture.api).finished;
    await turn();
    assert.equal(result.type, "error"); assert.equal(result.args[2].nativeCode, "INVALID_RESPONSE");
    assert.deepEqual(fixture.state.cancels, ["resource"]);
  }
});

test("abort while headers are pending cancels the signal and any late stream without reading", async () => {
  let finishOpen, signal;
  const fixture = fakeNetwork();
  fixture.api.openStream = (_request, options) => { signal = options.signal; return new Promise(resolve => { finishOpen = resolve; }); };
  const run = start(fixture.api);
  run.loader.abort();
  assert.equal(signal.aborted, true); assert.equal(run.loader.stats.aborted, true);
  assert.deepEqual(run.events.map(event => event.type), ["abort"]);
  finishOpen({ streamId: "late", status: 200, headers: {} });
  await turn();
  assert.equal(fixture.state.reads, 0); assert.deepEqual(fixture.state.cancels, ["late"]);
  assert.deepEqual(run.events.map(event => event.type), ["abort"]);
  assert.throws(() => run.loader.load(context(), config, run.callbacks), /once/);
});

test("abort from progress stops later reads and success; destroy suppresses all callbacks", async () => {
  const fixture = fakeNetwork({ bytes: new Uint8Array(12).fill(3), chunkSize: 4 });
  const run = start(fixture.api, context({ responseType: "arraybuffer", type: "media-fragment" }), {}, { ...config, highWaterMark: 4 });
  run.callbacks.onProgress = (...args) => { run.events.push({ type: "progress", args }); run.loader.abort(); };
  await run.finished; await turn();
  assert.equal(fixture.state.reads, 1); assert.deepEqual(fixture.state.cancels, ["resource"]);
  assert.deepEqual(run.events.map(event => event.type), ["progress", "abort"]);
  let finishOpen;
  const pending = fakeNetwork(); pending.api.openStream = () => new Promise(resolve => { finishOpen = resolve; });
  const destroyed = start(pending.api); destroyed.loader.destroy();
  finishOpen({ streamId: "destroyed", status: 200, headers: {} });
  await turn();
  assert.equal(pending.state.reads, 0); assert.deepEqual(pending.state.cancels, ["destroyed"]);
  assert.deepEqual(destroyed.events, []); assert.equal(destroyed.loader.context, null);
});

test("progressive binary loading emits each byte once and leaves success payload empty", async () => {
  const bytes = new Uint8Array([1, 2, 3, 4, 5, 6, 7]);
  const run = start(fakeNetwork({ bytes, chunkSize: 2 }).api, context({ responseType: "arraybuffer", type: "media-fragment", progressData: true }), {}, { ...config, highWaterMark: 3 });
  const result = await run.finished;
  assert.equal(result.type, "success"); assert.equal(result.args[0].data.byteLength, 0);
  const emitted = run.events.filter(event => event.type === "progress").flatMap(event => [...new Uint8Array(event.args[2])]);
  assert.deepEqual(emitted, [...bytes]); assert.equal(result.args[1].loaded, bytes.length);
});

test("native and local timeouts issue one timeout event and cancel pending or active streams", async () => {
  const fixture = fakeNetwork(); fixture.api.readStream = async () => { throw Object.assign(new Error("native timeout"), { code: "NETWORK_TIMEOUT" }); };
  const native = await start(fixture.api).finished;
  await turn();
  assert.equal(native.type, "timeout"); assert.equal(native.args[2].nativeCode, "NETWORK_TIMEOUT");
  assert.deepEqual(fixture.state.cancels, ["resource"]);
  let finishRead;
  const pending = fakeNetwork(); pending.api.readStream = () => new Promise(resolve => { finishRead = resolve; });
  const timers = new Map(); let next = 1;
  const run = start(pending.api, context(), { setTimer: callback => { const id = next++; timers.set(id, callback); return id; }, clearTimer: id => timers.delete(id) });
  await turn();
  assert.equal(timers.size, 1); [...timers.values()][0]();
  await run.finished; await turn();
  assert.equal(timers.size, 0); assert.deepEqual(pending.state.cancels, ["resource"]);
  finishRead({ data: encode("#EXTM3U"), done: true }); await turn();
  assert.deepEqual(run.events.map(event => event.type), ["timeout"]);
});
