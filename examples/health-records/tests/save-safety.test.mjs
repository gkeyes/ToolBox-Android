import test from "node:test";
import assert from "node:assert/strict";
import { emptyArchive, normalizeArchive } from "../web/model.mjs";
import { createStore } from "../web/store.mjs";
import { freshDom, deferred, settle } from "./memory-dom.mjs";

const nativeTimeout = globalThis.setTimeout;
globalThis.setTimeout = (...args) => { const timer = nativeTimeout(...args); timer.unref?.(); return timer; };
const HEAD = "health.v1.head";
const fixture = () => ({ ...emptyArchive(), records: [{ id: "synthetic-save", date: "2026-01-01", type: "blood", items: [
  { name: "合成甲", value: "1", unit: "U/L", normal: "0–10" },
  { name: "合成乙", value: "10", unit: "mg/L", normal: "0–20" },
] }] });
const button = (root, label) => {
  const node = root.querySelectorAll("button").find((n) => n.textContent === label || n.getAttribute("aria-label") === label);
  assert.ok(node, `Missing button: ${label}`); return node;
};
const starts = (root, text) => {
  const node = root.querySelectorAll("button").find((n) => n.textContent.startsWith(text));
  assert.ok(node, `Missing button prefix: ${text}`); return node;
};
function memoryStorage() {
  const values = new Map();
  return { get: async (key) => structuredClone(values.get(key) ?? null), set: async (key, value) => values.set(key, structuredClone(value)), remove: async (key) => values.delete(key), keys: async () => [...values.keys()] };
}
function delayHead(storage) {
  const gate = deferred(), original = storage.set; let pending = false;
  storage.set = async (key, value) => {
    if (key === HEAD && !pending) { pending = true; await gate.promise; }
    return original(key, value);
  };
  return { ...gate, isPending: () => pending };
}
let appId = 0;
async function openApp(data = fixture(), overrides = {}) {
  const storage = memoryStorage(), store = createStore(storage); await store.load(); await store.update(() => data);
  const screen = freshDom({ ready: async () => ({}), storage, ...overrides });
  await import(`../web/app.mjs?save-safety=${++appId}`); await settle();
  return { ...screen, storage };
}
async function correctionImport() {
  const incoming = fixture(); incoming.records[0].items.forEach((item) => { item.value = String(Number(item.value) * 2); });
  const bytes = new TextEncoder().encode(JSON.stringify(incoming));
  globalThis.Worker = class {
    postMessage(data) { queueMicrotask(() => this.onmessage({ data: { result: normalizeArchive(JSON.parse(new TextDecoder().decode(data.bytes))) } })); }
    terminate() {}
  };
  const screen = await openApp(fixture(), { files: { open: async () => ({ token: "synthetic-import", name: "synthetic.json", mimeType: "application/json", size: bytes.length }), read: async () => bytes } });
  await button(screen.navigation, "我的").fire("click"); await starts(screen.main, "导入旧数据").fire("click");
  const revise = screen.dialog.querySelectorAll("input").find((node) => node.parentNode.textContent.includes("使用备份修订"));
  revise.checked = true; await revise.fire("change");
  return screen;
}

test("store checks the expected revision inside its queue before invoking a stale mutator", async () => {
  const storage = memoryStorage(), store = createStore(storage); await store.load(); await store.update(() => fixture());
  const openedRevision = store.revision, gate = delayHead(storage);
  const importing = store.update((draft) => { draft.records[0].items[0].value = "2"; draft.records[0].items[1].value = "20"; });
  await settle(); assert.equal(gate.isPending(), true);
  let invoked = false;
  const editing = store.update((draft) => { invoked = true; draft.records[0].items = fixture().records[0].items; draft.records[0].items[0].value = "3"; }, { expectedRevision: openedRevision });
  const rejected = assert.rejects(editing, { code: "STALE_EDIT" });
  gate.resolve(); await importing; await rejected;
  assert.equal(invoked, false);
  assert.deepEqual((await createStore(storage).load()).records[0].items.map((item) => item.value), ["2", "20"]);
  await store.update((draft) => { draft.profile.age = "33"; });
  assert.equal(store.value.profile.age, "33", "a rejected stale edit must not poison later saves");
});

test("profile freezes inputs and back while saving, retains a rejected draft, then saves on retry", async () => {
  const screen = await openApp();
  await button(screen.navigation, "我的").fire("click"); await starts(screen.main, "个人健康档案").fire("click");
  const history = screen.main.querySelector("textarea"), form = screen.main.querySelector("form");
  await history.enter("SYNTHETIC_DRAFT");
  const gate = delayHead(screen.storage), saving = form.fire("submit"); await settle();
  try {
    assert.equal(gate.isPending(), true); assert.equal(history.isDisabled(), true);
    assert.ok(form.querySelectorAll("button").every((node) => node.isDisabled()));
    await history.enter("SYNTHETIC_LATE_INPUT"); assert.equal(history.value, "SYNTHETIC_DRAFT");
  } finally { gate.reject(new Error("synthetic save failure")); await saving; }
  assert.equal(history.isDisabled(), false); assert.equal(history.value, "SYNTHETIC_DRAFT");
  assert.equal(screen.main.querySelector("h1").textContent, "个人健康档案");
  assert.equal((await createStore(screen.storage).load()).profile.history, "");
  await form.fire("submit");
  assert.equal((await createStore(screen.storage).load()).profile.history, "SYNTHETIC_DRAFT");
});

test("import commit locks close, cancel, options and Escape; failed commit remains retryable", async () => {
  const screen = await correctionImport(), gate = delayHead(screen.storage);
  const importing = button(screen.dialog, "确认导入").fire("click"); await settle();
  try {
    assert.equal(gate.isPending(), true);
    assert.ok(screen.dialog.querySelectorAll("button").every((node) => node.isDisabled()));
    assert.ok(screen.dialog.querySelectorAll("input").every((node) => node.isDisabled()));
    await button(screen.dialog, "关闭弹窗").fire("click"); assert.equal(screen.dialog.open, true);
    let prevented = false;
    await screen.dialog.fire("cancel", { preventDefault() { prevented = true; } });
    assert.equal(prevented, true);
  } finally { gate.reject(new Error("synthetic import failure")); await importing; }
  assert.equal(screen.dialog.open, true); assert.equal(button(screen.dialog, "关闭弹窗").isDisabled(), false);
  assert.deepEqual((await createStore(screen.storage).load()).records, fixture().records);
  await button(screen.dialog, "确认导入").fire("click");
  assert.equal(screen.dialog.open, false);
  assert.deepEqual((await createStore(screen.storage).load()).records[0].items.map((item) => item.value), ["2", "20"]);
});

test("a displaced pending import cannot overwrite the newer editor, and its queued stale save retains input", async () => {
  const screen = await correctionImport(), gate = delayHead(screen.storage);
  const importing = button(screen.dialog, "确认导入").fire("click"); await settle();
  // Simulate external dismissal, bypassing the user-facing lock, to exercise the commit boundary too.
  screen.dialog.close();
  await button(screen.navigation, "记录").fire("click");
  await screen.main.querySelectorAll("button").find((node) => node.textContent.includes("2026.01.01")).fire("click");
  await button(screen.main, "编辑记录").fire("click");
  const values = screen.main.querySelectorAll("input").filter((node) => node.getAttribute("placeholder") === "数值或文字结果");
  await values[0].enter("3");
  const editing = screen.main.querySelector("form").fire("submit"); await settle();
  gate.resolve(); await Promise.all([importing, editing]);
  assert.deepEqual((await createStore(screen.storage).load()).records[0].items.map((item) => item.value), ["2", "20"]);
  assert.equal(screen.main.querySelector("h1").textContent, "编辑记录");
  assert.equal(values[0].value, "3"); assert.equal(values[0].isDisabled(), false);
  assert.ok(screen.main.textContent.includes("已被其他操作更新"));
});

for (const format of ["json", "xlsx"]) test(`${format} export fixes its year before generation and invalidates prepared data on range changes`, async () => {
  const data = fixture(); data.records.push({ ...structuredClone(data.records[0]), id: "synthetic-2025", date: "2025-01-01" });
  let request, releaseWorker, saved;
  globalThis.Worker = class {
    postMessage(value) { request = value; releaseWorker = () => this.onmessage({ data: { result: format === "json" ? JSON.stringify(value.archive) : new Uint8Array([1, 2, 3]) } }); }
    terminate() {}
  };
  const screen = await openApp(data, { files: { save: async (name, mime, content) => { saved = { name, mime, content }; return { token: "synthetic-save" }; } } });
  await button(screen.navigation, "我的").fire("click"); await starts(screen.main, "导出备份").fire("click");
  await button(screen.dialog, "导出年份").fire("click"); await button(screen.dialog, "2025 年").fire("click");
  const generating = button(screen.dialog, format === "json" ? "生成 JSON" : "生成 Excel").fire("click"); await settle();
  try {
    assert.equal(button(screen.dialog, "导出年份").isDisabled(), true);
    assert.deepEqual(request.archive.records.map((row) => row.date), ["2025-01-01"]);
  } finally { releaseWorker(); await generating; }
  assert.equal(button(screen.dialog, "导出年份").isDisabled(), false);
  await button(screen.dialog, "选择位置并保存").fire("click");
  assert.ok(saved.name.endsWith(`_2025.${format}`));
  if (format === "json") assert.deepEqual(JSON.parse(saved.content).records.map((row) => row.date), ["2025-01-01"]);

  await starts(screen.main, "导出备份").fire("click");
  const again = button(screen.dialog, format === "json" ? "生成 JSON" : "生成 Excel").fire("click"); await settle(); releaseWorker(); await again;
  await button(screen.dialog, "导出年份").fire("click"); await button(screen.dialog, "2025 年").fire("click");
  assert.equal(screen.dialog.querySelectorAll("button").some((node) => node.textContent === "选择位置并保存"), false);
});
