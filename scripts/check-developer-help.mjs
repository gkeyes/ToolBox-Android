import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = name => readFile(path.join(root, name), "utf8");
const manual = await read("sdk/help/manual.md");
const sdk = await read("sdk/toolbox-api.d.ts");
const contract = JSON.parse(await read("tool-api/src/main/resources/toolbox-api-v1.json"));
const blocks = [];
const chapters = new Set();
const articles = new Set();
let chapter = "";
let article = "";
let current = null;
for (const line of manual.split("\n")) {
  if (current) {
    if (line === "```") {
      blocks.push({ ...current, text: current.lines.join("\n") });
      current = null;
    } else current.lines.push(line);
  } else if (line.startsWith("```")) {
    assert(article, "Every code sample needs a topic");
    current = { label: line.slice(3).trim(), article, lines: [] };
  } else if (line.startsWith("## ")) {
    chapter = line.slice(3);
    assert(!chapters.has(chapter), "Duplicate chapter: " + chapter);
    chapters.add(chapter);
    article = "";
  } else if (line.startsWith("### ")) {
    assert(chapter, "Topic without chapter");
    article = chapter + "/" + line.slice(4);
    assert(!articles.has(article), "Duplicate topic: " + article);
    articles.add(article);
  }
}
assert.equal(current, null, "Unclosed code block");
assert(chapters.size > 0 && articles.size > 0);

const publicMethods = new Set();
const stack = [];
const interfaceText = sdk.split("export interface ToolBoxApi {")[1].split("declare global")[0];
for (const line of interfaceText.split("\n")) {
  const indent = line.length - line.trimStart().length;
  if (!line.trim()) continue;
  while (stack.length && stack.at(-1).indent >= indent) stack.pop();
  const namespace = line.match(/^\s*(\w+): \{$/);
  if (namespace) stack.push({ name: namespace[1], indent });
  const method = line.match(/^\s*(\w+)\(/);
  if (method) publicMethods.add([...stack.map(item => item.name), method[1]].join("."));
}
for (const method of contract.methods) {
  assert(publicMethods.has(method.name), "Missing public signature: " + method.name);
}
const embedded = new Set();
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
for (const block of blocks) {
  const [language, source] = block.label.split(/\s+/, 2);
  if (source?.includes("/")) {
    assert(/^(sdk\/|scripts\/)/.test(source) && !source.includes(".."));
    assert.equal(block.text.trimEnd(), (await read(source)).trimEnd(), "Stale embedded source: " + source);
    embedded.add(source);
  }
  if (language === "json") JSON.parse(block.text);
  if (language !== "js") continue;
  new AsyncFunction(block.text);
  for (const match of block.text.matchAll(/\b(?:ToolBox|api)\.([a-zA-Z.]+)\(/g)) {
    assert(publicMethods.has(match[1]), "Undocumented or nonexistent API in sample: " + match[1]);
  }
}
for (const source of ["sdk/toolbox-api.d.ts", "scripts/package-tool.py",
  "sdk/templates/minimal/manifest.json", "sdk/templates/minimal/index.html",
  "sdk/templates/minimal/style.css", "sdk/templates/minimal/app.js"]) {
  assert(embedded.has(source), "Missing copyable source: " + source);
}

const manifest = JSON.parse(await read("sdk/templates/minimal/manifest.json"));
const declarations = new Set(manifest.permissions.map(item => item.name));
const minimalJs = await read("sdk/templates/minimal/app.js");
for (const match of minimalJs.matchAll(/\bapi\.([a-zA-Z.]+)\(/g)) {
  const capability = contract.methods.find(method => method.name === match[1])?.capability;
  if (capability) assert(declarations.has(capability), "Starter lacks permission: " + capability);
}
const html = await read("sdk/templates/minimal/index.html");
assert(!/<script(?![^>]*\bsrc=)[^>]*>[\s\S]*?\S[\s\S]*?<\/script>/i.test(html), "Inline script in strict starter");
assert(!/\son\w+\s*=/i.test(html), "Inline event handler in strict starter");

function dom(ids) {
  return Object.fromEntries(ids.map(id => [id, {
    value: "", textContent: "", disabled: false,
    handlers: new Map(),
    addEventListener(type, callback) { this.handlers.set(type, callback); },
  }]));
}
const saved = new Map();
let copied = "";
const starterApi = {
  ready: async () => ({ apiVersion: "1.0" }),
  storage: {
    get: async key => saved.get(key) ?? null,
    set: async (key, value) => { saved.set(key, value); },
  },
  clipboard: { writeText: async value => { copied = value; } },
};
const nodes = dom(["draft", "status", "save", "copy"]);
await vm.runInNewContext(minimalJs, { window: { ToolBox: starterApi }, document: { getElementById: id => nodes[id] } });
assert.equal(nodes.save.disabled, false);
nodes.draft.value = "帮助页模板验证";
await nodes.save.handlers.get("click")();
await nodes.copy.handlers.get("click")();
assert.equal(copied, "帮助页模板验证");
const reopened = dom(["draft", "status", "save", "copy"]);
await vm.runInNewContext(minimalJs, { window: { ToolBox: starterApi }, document: { getElementById: id => reopened[id] } });
assert.equal(reopened.draft.value, "帮助页模板验证");

const runtimeSample = blocks.find(block => block.label.startsWith("js") && block.text.includes('const timerKey = "demo-tick"'));
assert(runtimeSample, "Missing full background lifecycle sample");
const events = {};
const timers = new Map();
const live = new Map();
let active = null;
let sequence = 0;
const api = {
  ready: async () => ({ apiVersion: "1.0" }),
  background: {
    start: async () => { active ||= "session-" + (++sequence); return { sessionId: active }; },
    stop: async id => { assert.equal(id, active); active = null; timers.clear(); live.delete(id); },
    setTimer: async (key, interval) => { assert(active); timers.set(key, interval); },
    cancelTimer: async key => { if (!timers.delete(key)) throw { code: "NOT_FOUND" }; },
    onTimer: listener => { events.timer = listener; return () => { events.timer = null; }; },
    onRestore: listener => { events.restore = listener; return () => { events.restore = null; }; },
  },
  notifications: { live: {
    start: async request => { assert.equal(request.sessionId, active); live.set(active, request); },
    update: async request => { assert.equal(request.sessionId, active); live.set(active, request); },
    end: async id => { live.delete(id); },
  } },
};
const backgroundNodes = dom(["start-background", "stop-background", "background-status"]);
vm.runInNewContext(runtimeSample.text, {
  window: { ToolBox: api }, document: { getElementById: id => backgroundNodes[id] },
});
const settle = () => new Promise(resolve => setImmediate(resolve));
await settle();
assert.equal(active, null, "Loading a tool must not start background work");
backgroundNodes["start-background"].handlers.get("click")();
await settle();
const firstSession = active;
assert(firstSession && live.has(firstSession));
assert.equal(timers.get("demo-tick"), 10000);
events.timer({ key: "demo-tick", firedAt: Date.now() });
await settle();
assert(live.get(firstSession).primaryText.includes("1"));
backgroundNodes["stop-background"].handlers.get("click")();
await settle();
assert.equal(active, null);
assert.equal(live.size, 0);
assert.equal(timers.size, 0);
events.timer({ key: "demo-tick", firedAt: Date.now() });
await settle();
assert.equal(live.size, 0);
events.restore({ reason: "process", restoredAt: Date.now() });
await settle();
assert(active && active !== firstSession && live.has(active));
backgroundNodes["stop-background"].handlers.get("click")();
await settle();
assert.equal(active, null);

console.log("Developer help verified: " + chapters.size + " chapters, " + articles.size +
  " topics, " + blocks.length + " code blocks; embedded sources and SDK agree.");
console.log("Mock-bridge examples passed: save/reopen/copy; explicit background start/timer/restore/stop.");
console.log("Android compilation, Compose rendering and device verification were not run.");
