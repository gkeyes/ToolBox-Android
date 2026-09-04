import test from "node:test";
import assert from "node:assert/strict";
import { freshDom, deferred, settle } from "./memory-dom.mjs";
import { emptyArchive, normalizeArchive, metricKey } from "../web/model.mjs";
import { createStore } from "../web/store.mjs";
import { reportImage } from "../web/io.mjs";

const originalTimeout = globalThis.setTimeout;
globalThis.setTimeout = (...args) => { const timer = originalTimeout(...args); timer.unref?.(); return timer; };
freshDom();
const { createRecordEditor, createBatchEditor } = await import("../web/editor.mjs");
const { trendChart } = await import("../web/charts.mjs");
const record = { id: "synthetic", date: "2026-01-01", type: "blood", items: [{ name: "合成指标", value: "1", unit: "", normal: "" }] };
const button = (root, label) => { const node = root.querySelectorAll("button").find((n) => n.textContent === label || n.getAttribute("aria-label") === label); assert.ok(node, `Missing button: ${label}`); return node; };
const input = (root, placeholder) => { const node = root.querySelectorAll("input").find((n) => n.getAttribute("placeholder") === placeholder); assert.ok(node, `Missing input: ${placeholder}`); return node; };

for (const batch of [false, true]) test(`${batch ? "batch" : "record"} editor freezes all controls while saving and retains failed drafts`, async () => {
  freshDom();
  const gate = deferred(); let saved;
  const options = { editing: true, cancel() {}, history() {}, save: async (value) => { saved = value; await gate.promise; } };
  const metric = { name: record.items[0].name, specimen: "血样", unit: "", points: [{ ...record.items[0], recordId: record.id, itemIndex: 0, date: record.date, type: record.type }] };
  const edit = batch ? createBatchEditor(metric, options) : createRecordEditor(record, options);
  const control = input(edit.element, "数值或文字结果"); await control.enter("2");
  const completion = edit.element.fire("submit"); await settle();
  assert.equal(control.isDisabled(), true);
  assert.ok(edit.element.querySelectorAll("button").every((n) => n.isDisabled()));
  await control.enter("3"); assert.equal(control.value, "2");
  gate.reject(new Error("synthetic write failure")); await completion;
  assert.equal(control.isDisabled(), false); assert.equal(control.value, "2"); assert.equal(edit.isDirty(), true);
  assert.equal(record.items[0].value, "1");
  assert.equal(batch ? saved[0].item.value : saved.items[0].value, "2");
});

test("trend ticks distinguish fractional and closely spaced large values", () => {
  freshDom();
  for (const values of [["0.015", "0.011"], ["10012", "10010"], ["0.000000015", "0.000000011"], ["5", "5"]]) {
    const chart = trendChart({ name: "合成", specimen: "血样", unit: "", points: values.map((value, i) => ({ value, date: `2026-01-0${2 - i}`, normal: "", unit: "", status: "unknown" })) });
    const labels = chart.querySelectorAll("text").slice(0, 4).map((n) => n.textContent);
    assert.equal(labels.length, 4); assert.equal(new Set(labels).size, 4, JSON.stringify(labels));
    assert.ok(labels.every((value) => Number.isFinite(Number(value))));
  }
});

let appId = 0;
test("validated report images within the upload budget retain their original pixels and encoding", async () => {
  freshDom(); let closed = false;
  const png = new Uint8Array(Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aPqEAAAAASUVORK5CYII=", "base64"));
  globalThis.createImageBitmap = async () => ({ width: 1, height: 1, close() { closed = true; } });
  const image = await reportImage(png, "image/png");
  assert.equal(image.mimeType, "image/png");
  assert.equal(image.data, Buffer.from(png).toString("base64"));
  assert.equal(closed, true);
});

async function openApp(fixture, overrides = {}) {
  const values = new Map();
  const storage = { get: async (key) => structuredClone(values.get(key) ?? null), set: async (key, value) => values.set(key, structuredClone(value)), remove: async (key) => values.delete(key), keys: async () => [...values.keys()], secure: { get: async () => "SYNTHETIC_KEY_NOT_REAL" } };
  const store = createStore(storage); await store.load(); await store.update(() => fixture);
  const api = { ready: async () => ({}), storage, ...overrides }, screen = freshDom(api);
  await import(`../web/app.mjs?ui-test=${++appId}`); await settle();
  return { ...screen, storage };
}

test("trend charts group by name and specimen without dropping unequal or missing units", async () => {
  const fixture = emptyArchive();
  fixture.records = [
    ["合成指标", "blood", "mIU/mL", "1"],
    ["合成指标", "blood", "", "2"],
    ["合成指标", "blood", "U/mL", "3000"],
    ["合成指标", "urine", "U/mL", "4"],
    ["另一项目", "blood", "U/mL", "5"],
  ].map(([name, type, unit, value], i) => ({ id: `name-group-${i}`, date: `2026-01-0${i + 1}`, type, items: [{ name, unit, value, normal: "0–9" }] }));
  const before = structuredClone(fixture.records), screen = await openApp(fixture);
  assert.equal(screen.main.querySelectorAll("strong").filter((node) => node.className === "summary-number")[1].textContent, "3");
  await button(screen.navigation, "趋势").fire("click");
  await button(screen.main, "选择趋势指标").fire("click");
  const options = screen.main.querySelector('[aria-label="趋势指标候选"]').querySelectorAll('[role="option"]');
  assert.deepEqual(options.map((node) => node.querySelector("span").textContent).sort(), ["另一项目 · 血样", "合成指标 · 尿样", "合成指标 · 血样"]);
  const grouped = options.find((node) => node.textContent.startsWith("合成指标 · 血样"));
  assert.ok(grouped.textContent.includes("3 次记录")); await grouped.fire("click");
  assert.equal(screen.main.querySelector("tbody").children.length, 3);
  assert.deepEqual(screen.main.querySelector("tbody").children.map((row) => row.children[0].childNodes[0].textContent), ["2026-01-03", "2026-01-02", "2026-01-01"]);
  assert.equal(screen.main.querySelectorAll("circle").length, 3);
  assert.equal(screen.main.querySelector('[aria-label="选择曲线数据"]'), null);
  assert.ok(screen.main.querySelector('[role="img"]').getAttribute("aria-label").includes("血样，多种单位（未换算），3 次"));
  assert.equal(screen.main.querySelectorAll("rect").filter((node) => node.className === "reference-band").length, 0);
  assert.ok(screen.main.textContent.includes("单位不一致"));
  assert.deepEqual((await createStore(screen.storage).load()).records, before);
  await button(screen.main, "批量编辑").fire("click");
  assert.ok(screen.dialog.textContent.includes("选择要批量编辑的记录"));
  await button(screen.dialog, "血样 · 未注明单位 · 1 次记录").fire("click");
  assert.equal(screen.main.querySelectorAll("input").filter((node) => node.getAttribute("placeholder") === "数值或文字结果").length, 1);
  await input(screen.main, "数值或文字结果").enter("2.5");
  await screen.main.querySelector("form").fire("submit");
  assert.equal(screen.main.querySelector("tbody").children.length, 3);
  const expected = structuredClone(before); expected[1].items[0].value = "2.5";
  assert.deepEqual((await createStore(screen.storage).load()).records, expected);
  await button(screen.main, "编辑2026-01-03的合成指标").fire("click");
  await input(screen.main, "数值或文字结果").enter("3001"); await screen.main.querySelector("form").fire("submit");
  expected[2].items[0].value = "3001";
  assert.deepEqual((await createStore(screen.storage).load()).records, expected);
  assert.equal(screen.main.querySelector("tbody").children.length, 3);
  await button(screen.main, "返回").fire("click");
  assert.equal(screen.main.querySelector("h1").textContent, "健康档案");
});

test("trend back restores the opening tab or report without creating a return loop", async () => {
  const fixture = emptyArchive(); fixture.records = [structuredClone(record)];
  const screen = await openApp(fixture);
  await button(screen.navigation, "我的").fire("click");
  await button(screen.navigation, "趋势").fire("click");
  await button(screen.main, "返回").fire("click");
  assert.equal(screen.main.querySelector("h1").textContent, "我的档案");
  await button(screen.navigation, "记录").fire("click");
  await screen.main.querySelectorAll("button").find((node) => node.textContent.includes("2026.01.01")).fire("click");
  await button(screen.main, "查看合成指标趋势").fire("click");
  assert.equal(screen.main.querySelector("h1").textContent, "指标趋势");
  await button(screen.main, "返回").fire("click");
  assert.ok(button(screen.main, "编辑记录"));
  assert.ok(screen.main.textContent.includes("2026-01-01"));
  await button(screen.main, "返回").fire("click");
  assert.equal(screen.main.querySelector("h1").textContent, "检验记录");
});

test("AI host failures show actionable codes without exposing raw errors or pretending to return no suggestions", async () => {
  for (const [code, expected] of [["NETWORK_TIMEOUT", "超时"], ["NETWORK_UNAVAILABLE", "连接或读取响应失败"], ["INTERNAL_ERROR", "宿主内部处理失败"]]) {
    const fixture = emptyArchive(); fixture.settings.aiProvider = "minimax"; fixture.records = [structuredClone(record)];
    const screen = await openApp(fixture, { network: { request: async () => { throw Object.assign(new Error("SYNTHETIC_PRIVATE_NOT_DISPLAY"), { code }); } } });
    await button(screen.navigation, "我的").fire("click");
    await screen.main.querySelectorAll("button").find((node) => node.textContent.startsWith("AI 资料助手")).fire("click");
    await screen.main.querySelectorAll("button").find((node) => node.textContent.startsWith("最新资料摘要")).fire("click");
    await button(screen.dialog, "同意发送并整理").fire("click"); await settle();
    assert.ok(screen.dialog.textContent.includes("未能完成整理"));
    assert.ok(screen.dialog.textContent.includes(expected), screen.dialog.textContent);
    assert.ok(screen.dialog.textContent.includes(code));
    assert.ok(screen.dialog.textContent.includes("等待服务返回"));
    assert.ok(screen.dialog.textContent.includes("MiniMax-M3"));
    assert.equal(screen.dialog.textContent.includes("SYNTHETIC_PRIVATE_NOT_DISPLAY"), false);
    assert.equal(screen.dialog.textContent.includes("暂无修改建议"), false);
    assert.deepEqual((await createStore(screen.storage).load()).records, fixture.records);
  }
  const fixture = emptyArchive(); fixture.settings.aiProvider = "minimax"; fixture.records = [structuredClone(record)];
  const screen = await openApp(fixture, { network: { request: async () => ({ status: 200, bodyEncoding: "text", body: JSON.stringify({ base_resp: { status_code: 0 }, choices: [{ finish_reason: "stop", message: { content: JSON.stringify({ suggestions: [] }) } }] }) }) } });
  await button(screen.navigation, "我的").fire("click");
  await screen.main.querySelectorAll("button").find((node) => node.textContent.startsWith("AI 资料助手")).fire("click");
  await screen.main.querySelectorAll("button").find((node) => node.textContent.startsWith("指标名称整理")).fire("click");
  await button(screen.dialog, "同意发送并整理").fire("click"); await settle();
  assert.ok(screen.dialog.textContent.includes("整理完成，暂无修改建议"));
  assert.ok(screen.dialog.textContent.includes("返回 0 条"));
  assert.ok(screen.dialog.textContent.includes("同标本")); assert.ok(screen.dialog.textContent.includes("单位仅作辅助"));
  assert.equal(screen.dialog.textContent.includes("错误码"), false);
  assert.deepEqual((await createStore(screen.storage).load()).records, fixture.records);
});

for (const dismissed of [true, false]) test(`OCR completion ${dismissed ? "cannot replace a newer editor or dialog" : "opens a draft only while its confirmation is active"}`, async () => {
  const network = deferred(), fixture = emptyArchive(); fixture.settings.model = "synthetic-model";
  const png = new Uint8Array(Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aPqEAAAAASUVORK5CYII=", "base64"));
  globalThis.createImageBitmap = async () => ({ width: 1, height: 1, close() {} });
  const screen = await openApp(fixture, { files: { open: async () => ({ token: "synthetic", name: "synthetic.png", mimeType: "image/png", size: png.length }), read: async () => png }, network: { request: () => network.promise } });
  await button(screen.main, "识别报告").fire("click");
  const completion = button(screen.dialog, "同意发送这张图片").fire("click"); await settle();
  if (dismissed) {
    await button(screen.dialog, "关闭弹窗").fire("click"); await button(screen.main, "新增记录").fire("click");
    await input(screen.main, "如：白细胞").enter("SYNTHETIC_UNSAVED_DRAFT");
    await input(screen.main, "数值或文字结果").enter("5");
    await button(screen.main, "返回").fire("click");
    assert.equal(screen.dialog.open, true); assert.ok(screen.dialog.textContent.includes("放弃未保存的修改"));
  }
  network.resolve({ status: 200, bodyEncoding: "text", body: JSON.stringify({ candidates: [{ finishReason: "STOP", content: { parts: [{ text: JSON.stringify({ ...record, items: [{ ...record.items[0], name: "SYNTHETIC_OCR_RESULT" }] }) }] } }] }) });
  await completion;
  assert.equal(input(screen.main, "如：白细胞").value, dismissed ? "SYNTHETIC_UNSAVED_DRAFT" : "SYNTHETIC_OCR_RESULT");
  assert.equal(screen.dialog.open, dismissed);
  assert.equal((await createStore(screen.storage).load()).records.length, 0);
  if (!dismissed) {
    await button(screen.main, "检验日期").fire("click");
    assert.equal(screen.main.querySelector('[aria-label="日期选择"]').hidden, false);
    await button(screen.main, "2026-01-15").fire("click");
    await button(screen.main, "确认日期").fire("click");
    await screen.main.querySelector("form").fire("submit");
    const saved = await createStore(screen.storage).load();
    assert.equal(saved.records[0].date, "2026-01-15");
    assert.equal(saved.records[0].items[0].name, "SYNTHETIC_OCR_RESULT");
  }
});

test("in-page report calendar cancels without dirtying, clamps month ends, validates leap years and retains failed saves", async () => {
  freshDom(); let saved;
  const source = { ...record, date: "2026-01-31" };
  const edit = createRecordEditor(source, { history() {}, cancel() {}, save: async (next) => { saved = next; throw new Error("synthetic date save failure"); } });
  const date = button(edit.element, "检验日期");
  await date.fire("click"); await button(edit.element, "下个月").fire("click");
  assert.ok(button(edit.element, "2026-02-28"));
  assert.equal(edit.element.querySelector('[aria-label="2026-02-29"]'), null);
  await button(edit.element, "取消日期选择").fire("click");
  assert.equal(date.textContent, "2026-01-31"); assert.equal(edit.isDirty(), false);
  await date.fire("click");
  await button(edit.element, "年份").fire("click"); await button(edit.element, "2024 年").fire("click");
  await button(edit.element, "月份").fire("click"); await button(edit.element, "2 月").fire("click");
  await button(edit.element, "2024-02-29").fire("click"); await button(edit.element, "确认日期").fire("click");
  assert.equal(date.textContent, "2024-02-29"); assert.equal(edit.isDirty(), true);
  await edit.element.fire("submit");
  assert.equal(saved.date, "2024-02-29"); assert.equal(source.date, "2026-01-31");
  assert.equal(date.textContent, "2024-02-29"); assert.equal(edit.isDirty(), true);
  for (const [value, blocked, allowed] of [["1900-01-01", "上个月", "下个月"], ["2200-12-31", "下个月", "上个月"]]) {
    const bounded = createRecordEditor({ ...record, date: value }, { history() {}, cancel() {}, save() {} });
    await button(bounded.element, "检验日期").fire("click");
    assert.equal(button(bounded.element, blocked).disabled, true); assert.equal(button(bounded.element, allowed).disabled, false);
  }
  for (const year of [1900, 2000, 2100]) {
    const century = createRecordEditor({ ...record, date: `${year}-02-28` }, { history() {}, cancel() {}, save() {} });
    await button(century.element, "检验日期").fire("click");
    assert.equal(Boolean(century.element.querySelector(`[aria-label="${year}-02-29"]`)), year === 2000);
  }
  const rollover = createRecordEditor({ ...record, date: "1999-12-31" }, { history() {}, cancel() {}, save() {} });
  await button(rollover.element, "检验日期").fire("click"); await button(rollover.element, "下个月").fire("click"); await button(rollover.element, "确认日期").fire("click");
  assert.equal(button(rollover.element, "检验日期").textContent, "2000-01-31");
});

const syntheticPng = () => new Uint8Array(Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aPqEAAAAASUVORK5CYII=", "base64"));
const miniResponse = (value) => ({ status: 200, bodyEncoding: "text", body: JSON.stringify({ base_resp: { status_code: 0 }, choices: [{ finish_reason: "stop", message: { content: JSON.stringify(value) } }] }) });
function matchingFixture() {
  const fixture = emptyArchive(); fixture.settings.aiProvider = "minimax";
  fixture.records = [{ ...record, id: "history", date: "2025-01-01", items: [{ name: "标准指标", value: "9", unit: "U/L", normal: "1–10" }] }];
  return fixture;
}
const ocrResponse = (unit = "U/L") => miniResponse({ date: "2026-01-31", type: "blood", items: [{ name: "识别异名", value: "5", unit, normal: "1–10" }] });
function matchedResponse(body) {
  const data = JSON.parse(body.messages[1].content[0].text.split("\n以下为资料数据：\n")[1]);
  assert.equal(data.groups.length, 1); assert.equal(data.groups[0].candidates.length, 1);
  return miniResponse({ matches: [{ sourceId: data.groups[0].items[0].id, targetId: data.groups[0].candidates[0].id }] });
}
const syntheticFiles = () => ({ open: async () => ({ token: "synthetic", name: "synthetic.png", mimeType: "image/png", size: syntheticPng().length }), read: async () => syntheticPng() });

for (const rememberChoice of [false, true]) test(`OCR name review supports restore/manual choice and ${rememberChoice ? "atomically remembers explicit aliases, retaining failed drafts" : "never remembers aliases by default"}`, async () => {
  const fixture = matchingFixture(), original = structuredClone(fixture.records); let calls = 0;
  const recognizedUnit = rememberChoice ? "" : "mg/L";
  globalThis.createImageBitmap = async () => ({ width: 1, height: 1, close() {} });
  const screen = await openApp(fixture, { files: syntheticFiles(), network: { request: async (options) => {
    calls++; const body = JSON.parse(options.body); assert.equal(body.model, "MiniMax-M3"); assert.ok(options.url.startsWith("https://api.minimax.cn/"));
    assert.equal(options.timeoutMs, 300000);
    return body.messages[1].content.some((part) => part.type === "image_url") ? ocrResponse(recognizedUnit) : matchedResponse(body);
  } } });
  await button(screen.main, "识别报告").fire("click");
  assert.ok(screen.dialog.textContent.includes("最多额外调用 4 次"));
  await button(screen.dialog, "同意发送这张图片").fire("click");
  assert.equal(calls, 2); assert.equal(input(screen.main, "如：白细胞").value, "标准指标");
  assert.ok(screen.main.textContent.includes("识别异名 → 标准指标"));
  assert.ok(screen.main.textContent.includes("AI 名称匹配尝试 1 次，对齐 1 项"));
  const remember = screen.main.querySelectorAll("input").find((node) => node.getAttribute("type") === "checkbox");
  assert.equal(remember.checked, false);
  await button(screen.main, "恢复原名").fire("click"); assert.equal(input(screen.main, "如：白细胞").value, "识别异名");
  await button(screen.main, "选择标准名称").fire("click");
  await screen.main.querySelectorAll('[role="option"]').find((node) => node.textContent.startsWith("标准指标")).fire("click");
  assert.equal(input(screen.main, "如：白细胞").value, "标准指标");
  await input(screen.main, "按报告填写").enter("g/L");
  assert.equal(input(screen.main, "如：白细胞").value, "标准指标"); assert.ok(screen.main.textContent.includes("单位已改变"));
  await input(screen.main, "按报告填写").enter(recognizedUnit); await button(screen.main, "选择标准名称").fire("click");
  await screen.main.querySelectorAll('[role="option"]').find((node) => node.textContent.startsWith("标准指标")).fire("click");
  await button(screen.main, "检验日期").fire("click"); await button(screen.main, "2026-01-15").fire("click"); await button(screen.main, "确认日期").fire("click");
  if (rememberChoice) {
    remember.checked = true; await remember.fire("change", { bubbles: true });
    const write = screen.storage.set; let fail = true;
    screen.storage.set = async (key, value) => { if (fail && key === "health.v1.head") { fail = false; throw { code: "QUOTA_EXCEEDED" }; } return write(key, value); };
    await screen.main.querySelector("form").fire("submit");
    const afterFailure = await createStore(screen.storage).load();
    assert.deepEqual(afterFailure.records, original); assert.deepEqual(afterFailure.aliasMap, {});
    assert.equal(input(screen.main, "如：白细胞").value, "标准指标"); assert.equal(button(screen.main, "检验日期").textContent, "2026-01-15"); assert.equal(remember.checked, true);
  }
  await screen.main.querySelector("form").fire("submit");
  const saved = await createStore(screen.storage).load();
  assert.equal(saved.records.length, 2); assert.deepEqual(saved.records.find((row) => row.id === "history"), original[0]);
  const created = saved.records.find((row) => row.id !== "history"); assert.equal(created.date, "2026-01-15"); assert.equal(created.items[0].name, "标准指标"); assert.equal(created.items[0].value, "5"); assert.equal(created.items[0].unit, recognizedUnit);
  assert.deepEqual(Object.keys(created.items[0]).sort(), ["name", "normal", "unit", "value"]);
  assert.deepEqual(saved.aliasMap, rememberChoice ? { [metricKey("blood", { name: "识别异名", unit: recognizedUnit })]: "标准指标" } : {});
  assert.equal(calls, 2);
});

test("a late second-stage name response cannot replace a newer editor or its confirmation dialog", async () => {
  const match = deferred(); let calls = 0, matchingBody;
  globalThis.createImageBitmap = async () => ({ width: 1, height: 1, close() {} });
  const screen = await openApp(matchingFixture(), { files: syntheticFiles(), network: { request: (options) => {
    calls++; const body = JSON.parse(options.body);
    if (calls === 1) return ocrResponse(); matchingBody = body; return match.promise;
  } } });
  await button(screen.main, "识别报告").fire("click");
  const completion = button(screen.dialog, "同意发送这张图片").fire("click"); await settle();
  assert.equal(calls, 2); assert.ok(screen.dialog.textContent.includes("正在对齐本地名称"));
  await button(screen.dialog, "关闭弹窗").fire("click"); await button(screen.main, "新增记录").fire("click");
  await input(screen.main, "如：白细胞").enter("NEW_DRAFT"); await input(screen.main, "数值或文字结果").enter("8");
  await button(screen.main, "返回").fire("click");
  match.resolve(matchedResponse(matchingBody)); await completion;
  assert.equal(input(screen.main, "如：白细胞").value, "NEW_DRAFT"); assert.equal(screen.dialog.open, true); assert.ok(screen.dialog.textContent.includes("放弃未保存的修改"));
  assert.equal((await createStore(screen.storage).load()).records.length, 1);
});

test("leaving during local image decoding does not open an old send dialog over a new draft", async () => {
  const decoding = deferred(); globalThis.createImageBitmap = () => decoding.promise;
  const screen = await openApp(matchingFixture(), { files: syntheticFiles() });
  const completion = button(screen.main, "识别报告").fire("click"); await settle();
  await button(screen.main, "新增记录").fire("click"); await input(screen.main, "如：白细胞").enter("NEW_DRAFT");
  decoding.resolve({ width: 1, height: 1, close() {} }); await completion;
  assert.equal(input(screen.main, "如：白细胞").value, "NEW_DRAFT"); assert.equal(screen.dialog.open, false);
});

test("AI settings isolate delayed key status and freeze the selected provider during secure saves", async () => {
  const fixture = emptyArchive(); fixture.settings.model = "synthetic-gemini";
  const screen = await openApp(fixture), oldLookup = deferred(), newLookup = deferred(), saving = deferred();
  const writes = [], keys = new Map(); let reads = 0;
  screen.storage.secure = {
    get: async (name) => { reads++; return reads === 1 ? oldLookup.promise : reads === 2 ? newLookup.promise : keys.get(name); },
    set: async (name, value) => { writes.push({ name, value }); await saving.promise; keys.set(name, value); },
  };
  await button(screen.navigation, "我的").fire("click");
  await button(screen.main, "AI 设置MiniMax / Gemini；密钥单独安全保存").fire("click");
  const keyInput = input(screen.main, "留空保留已保存的密钥");
  await keyInput.enter("UNSAVED_GEMINI_KEY_NOT_REAL");
  await button(screen.main, "AI 服务").fire("click"); await button(screen.main, "MiniMax").fire("click");
  assert.equal(keyInput.value, "");
  newLookup.resolve(null); await settle(); oldLookup.resolve("SAVED_GEMINI_KEY_NOT_REAL"); await settle();
  const status = screen.main.querySelector('[role="status"]');
  assert.ok(status.textContent.includes("尚未保存 MiniMax"));
  assert.equal(status.textContent.includes("Google Gemini"), false);
  await keyInput.enter("SYNTHETIC_MINIMAX_KEY_NOT_REAL");
  const completion = screen.main.querySelector("form").fire("submit"); await settle();
  assert.equal(keyInput.isDisabled(), true);
  assert.ok(screen.main.querySelectorAll("button").every((node) => node.isDisabled()));
  assert.deepEqual(writes, [{ name: "health.minimax.key", value: "SYNTHETIC_MINIMAX_KEY_NOT_REAL" }]);
  await keyInput.enter("NEW_UNSAVED_INPUT"); assert.equal(keyInput.value, "SYNTHETIC_MINIMAX_KEY_NOT_REAL");
  saving.resolve(); await completion;
  const saved = await createStore(screen.storage).load();
  assert.equal(saved.settings.aiProvider, "minimax"); assert.equal(saved.settings.minimaxModel, "MiniMax-M3");
  assert.equal(saved.settings.model, "synthetic-gemini"); assert.equal(saved.records.length, 0);
  assert.equal(JSON.stringify(saved).includes("KEY_NOT_REAL"), false); assert.equal(keyInput.value, "");
});

test("oversized append preview still exposes an explicit correction import", async () => {
  const fixture = emptyArchive();
  fixture.records = Array.from({ length: 1100 }, (_, i) => ({ ...record, id: `bulk-${i}`, items: [{ ...record.items[0], name: `合成指标 ${i}` }] }));
  const incoming = structuredClone(fixture); incoming.records.forEach((r) => { r.items[0].value = "2"; });
  const bytes = new TextEncoder().encode(JSON.stringify(incoming));
  globalThis.Worker = class {
    postMessage(data) { queueMicrotask(() => { try { this.onmessage({ data: { result: normalizeArchive(JSON.parse(new TextDecoder().decode(data.bytes))) } }); } catch (error) { this.onmessage({ data: { error: { message: error.message, code: error.code } } }); } }); }
    terminate() {}
  };
  const screen = await openApp(fixture, { files: { open: async () => ({ token: "synthetic", name: "correction.json", mimeType: "application/json", size: bytes.length }), read: async () => bytes } });
  await button(screen.navigation, "我的").fire("click");
  await button(screen.main, "导入旧数据旧站 JSON、Excel；先预览，再合并").fire("click");
  assert.equal(screen.dialog.open, true);
  const confirm = button(screen.dialog, "确认导入"); assert.equal(confirm.disabled, true);
  const revise = screen.dialog.querySelectorAll("input").find((n) => n.parentNode.textContent.includes("使用备份修订"));
  assert.ok(revise); revise.checked = true; await revise.fire("change");
  assert.equal(confirm.disabled, false); assert.ok(screen.dialog.textContent.includes("修订 1100 份"));
  await confirm.fire("click");
  const saved = await createStore(screen.storage).load();
  assert.equal(saved.records.length, 1100); assert.ok(saved.records.every((r) => r.items[0].value === "2"));
});
