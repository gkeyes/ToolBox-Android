import test from "node:test";
import assert from "node:assert/strict";
import { createReadingParser } from "../src/lib/reading-parser.js";
import { startProgressiveReading, createHighlightQueue, observeCodeVisibility } from "../src/lib/reading-client.js";

function scheduler() {
  let next = 0;
  const callbacks = new Map();
  return {
    schedule(callback) { callbacks.set(++next, callback); return next; },
    unschedule(id) { callbacks.delete(id); },
    frame() { const entry = callbacks.entries().next().value; if (!entry) return false; callbacks.delete(entry[0]); entry[1](); return true; },
  };
}
class WorkerDouble {
  listeners = new Map();
  messages = [];
  terminated = false;
  addEventListener(name, fn) { this.listeners.set(name, fn); }
  removeEventListener(name) { this.listeners.delete(name); }
  postMessage(message) { this.messages.push(message); }
  receive(data) { this.listeners.get("message")?.({ data }); }
  terminate() { this.terminated = true; }
}

for (const [name, html, expectedText] of [
  ["one large text node", "A".repeat(700_000), "A".repeat(700_000)],
  ["one nested container", '<div>'.repeat(120) + Array.from({ length: 1200 }, (_, index) => `<p>Paragraph ${index};</p>`).join("") + '</div>'.repeat(120), Array.from({ length: 1200 }, (_, index) => `Paragraph ${index};`).join("")],
  ["one large table", '<table><tbody>' + Array.from({ length: 1500 }, (_, index) => `<tr><td>${index};</td><td>value;</td></tr>`).join("") + '</tbody></table>', Array.from({ length: 1500 }, (_, index) => `${index};value;`).join("")],
]) test(`${name} produces first visible text before completion and retains every character across bounded batches`, () => {
  const parser = createReadingParser(html, "https://example.org/article");
  let batch, text = "", batches = 0, earlyText = false;
  const nodes = new Map([[0, { tag: "root" }]]);
  do {
    batch = parser.next();
    assert.ok(batch.operations.length <= 96);
    for (const operation of batch.operations) {
      if (operation.type === "element") {
        assert.ok(nodes.has(operation.parent));
        if (operation.tag === "td") assert.equal(nodes.get(operation.parent).tag, "tr");
        nodes.set(operation.id, operation);
      }
      if (operation.type === "text") { assert.ok(operation.text.length <= 2048); text += operation.text; }
    }
    if (!batch.done && text.length) earlyText = true;
    batches += 1;
  } while (!batch.done);
  assert.ok(earlyText);
  assert.ok(batches > 1);
  assert.equal(text, expectedText);
  if (name === "one large table") assert.equal([...nodes.values()].filter((node) => node.tag === "table").length, 1);
});

test("giant code is streamed as complete plaintext lines before its controls operation, including blank and trailing lines", () => {
  const code = "  const value = '完整';\n\n".repeat(3000);
  const parser = createReadingParser(`<pre><code class="language-js">${code}</code></pre>`);
  let batch, text = "", control, lines = 0, partial = false;
  do {
    batch = parser.next();
    for (const operation of batch.operations) {
      if (operation.type === "text") text += operation.text;
      if (operation.codeLine) lines += 1;
      if (operation.type === "code") control = operation;
    }
    if (text.length && !control) partial = true;
  } while (!batch.done);
  assert.ok(partial);
  assert.equal(text, code);
  assert.equal(control.code, code);
  assert.equal(lines, 6001);
});

test("main-thread mounting yields at its 8 ms budget and does not pull ahead of mounted batches", () => {
  const frames = scheduler(), worker = new WorkerDouble();
  let clock = 0, finished = false;
  const visible = [];
  const request = startProgressiveReading({ html: "text", apply(node) { visible.push(node); clock += 3; }, onDone() { finished = true; }, onError(error) { throw error; } }, { ...frames, createWorker: () => worker, now: () => clock });
  worker.receive({ type: "reading:batch", id: request.id, operations: [1, 2, 3, 4, 5, 6, 7], done: false });
  frames.frame();
  assert.deepEqual(visible, [1, 2, 3]);
  assert.equal(worker.messages.length, 1);
  frames.frame();
  assert.deepEqual(visible, [1, 2, 3, 4, 5, 6]);
  frames.frame();
  assert.equal(worker.messages.at(-1).type, "reading:next");
  worker.receive({ type: "reading:batch", id: request.id, operations: [8], done: true });
  frames.frame();
  assert.equal(finished, true);
  assert.equal(worker.terminated, true);
});

test("switching articles cancels queued frames and rejects late worker batches and failures", () => {
  const frames = scheduler(), worker = new WorkerDouble();
  const visible = [], errors = [];
  const request = startProgressiveReading({ html: "old article", apply: (node) => visible.push(node), onError: (error) => errors.push(error) }, { ...frames, createWorker: () => worker });
  const oldListener = worker.listeners.get("message");
  worker.receive({ type: "reading:batch", id: request.id, operations: ["old"], done: false });
  request.cancel();
  frames.frame();
  oldListener({ data: { type: "reading:batch", id: request.id, operations: ["late"], done: true } });
  oldListener({ data: { type: "reading:error", id: request.id, message: "late error" } });
  assert.deepEqual(visible, []);
  assert.deepEqual(errors, []);
  assert.equal(worker.terminated, true);
});

test("worker errors surface actionable reading failure and stop further preparation", () => {
  const frames = scheduler(), worker = new WorkerDouble();
  let error;
  startProgressiveReading({ html: "article", apply() {}, onError(value) { error = value; } }, { ...frames, createWorker: () => worker });
  worker.listeners.get("error")({ preventDefault() {} });
  assert.match(error.message, /重试/);
  assert.equal(worker.terminated, true);
});

test("offscreen code does not create a highlighter or compute, cancelled queued code never runs, and old results are ignored", () => {
  const tasks = scheduler(), workers = [], highlighted = [];
  const highlights = createHighlightQueue({ ...tasks, createWorker() { const worker = new WorkerDouble(); workers.push(worker); return worker; } });
  let visibility;
  class Observer {
    constructor(callback) { visibility = callback; }
    observe() {}
    disconnect() {}
  }
  const element = { closest: () => null };
  const stop = observeCodeVisibility(element, (completed) => highlights.enqueue("const a = 1", "js", (html) => { highlighted.push(html); completed(); }), Observer);
  visibility([{ target: element, isIntersecting: false }]);
  assert.equal(tasks.frame(), false);
  assert.equal(workers.length, 0);
  visibility([{ target: element, isIntersecting: true }]);
  visibility([{ target: element, isIntersecting: false }]);
  tasks.frame();
  assert.equal(workers.length, 0);
  visibility([{ target: element, isIntersecting: true }]);
  tasks.frame();
  assert.equal(workers.length, 1);
  const worker = workers[0];
  const second = highlights.enqueue("const b = 2", "js", () => highlighted.push("cancelled"));
  second();
  stop();
  worker.receive({ type: "highlight:result", id: worker.messages[0].id, html: "old" });
  tasks.frame();
  assert.equal(worker.messages.length, 1);
  assert.deepEqual(highlighted, []);
  highlights.enqueue("current", "js", (html) => highlighted.push(html));
  tasks.frame();
  const late = worker.listeners.get("message");
  highlights.dispose();
  late({ data: { type: "highlight:result", id: worker.messages.at(-1).id, html: "late" } });
  assert.deepEqual(highlighted, []);
  assert.equal(worker.terminated, true);
});
