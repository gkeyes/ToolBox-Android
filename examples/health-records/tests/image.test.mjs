import test from "node:test";
import assert from "node:assert/strict";
import { openFile, reportImage } from "../web/io.mjs";
import { makeAiRequest } from "../web/ai.mjs";
import { emptyArchive, byteSize } from "../web/model.mjs";
import { freshDom, MemoryNode } from "./memory-dom.mjs";

const sourceLimit = 5 * 1024 * 1024, uploadLimit = 700 * 1024;
const png = new Uint8Array(Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aPqEAAAAASUVORK5CYII=", "base64"));
function paddedImage(size) { const bytes = new Uint8Array(size); bytes.set(png); return bytes; }

test("image entry checks declared and actual 5 MiB limits without widening backup imports", async () => {
  let reads = 0, declared = sourceLimit, actual = sourceLimit, mime = "image/png";
  const api = { files: {
    open: async () => ({ token: "synthetic", name: mime === "image/png" ? "synthetic.png" : "synthetic.json", mimeType: mime, size: declared }),
    read: async () => { reads++; return new Uint8Array(actual); },
  } };
  assert.equal((await openFile(api, true)).bytes.length, sourceLimit);
  declared = sourceLimit + 1;
  await assert.rejects(openFile(api, true), /5 MiB/); assert.equal(reads, 1);
  declared = 1; actual = sourceLimit + 1;
  await assert.rejects(openFile(api, true), /5 MiB/); assert.equal(reads, 2);
  for (declared of [NaN, -1, Infinity]) await assert.rejects(openFile(api, true));
  mime = "application/json"; declared = uploadLimit + 1; actual = 1;
  await assert.rejects(openFile(api), /700 KiB/); assert.equal(reads, 2);
  declared = 1; actual = uploadLimit + 1; await assert.rejects(openFile(api), /700 KiB/);
});

test("local compression preserves budgeted originals and bounds encoder quality, resolution, resources and full AI requests", async () => {
  freshDom(); let closed = 0, attempts = [], canvas;
  globalThis.createImageBitmap = async () => ({ width: 4000, height: 3000, close() { closed++; } });
  const createElement = document.createElement;
  document.createElement = (tag) => {
    const node = createElement(tag);
    if (tag === "canvas") {
      canvas = node;
      node.toBlob = (callback, type, quality) => {
        attempts.push({ width: node.width, height: node.height, quality });
        callback(new Blob([new Uint8Array(attempts.length < 4 ? uploadLimit + 1 : uploadLimit)], { type }));
      };
    }
    return node;
  };
  const original = paddedImage(uploadLimit), untouched = original.slice();
  const small = await reportImage(original, "image/png");
  assert.equal(small.data, Buffer.from(original).toString("base64")); assert.equal(small.mimeType, "image/png"); assert.equal(attempts.length, 0);
  assert.equal(small.originalBytes, uploadLimit); assert.equal(small.outputBytes, uploadLimit); assert.equal(small.compressed, false);
  const source = paddedImage(sourceLimit), large = await reportImage(source, "image/png");
  assert.equal(large.mimeType, "image/jpeg"); assert.equal(large.originalBytes, sourceLimit); assert.equal(large.outputBytes, uploadLimit); assert.equal(large.compressed, true);
  assert.deepEqual(attempts, [{ width: 3200, height: 2400, quality: .92 }, { width: 3200, height: 2400, quality: .86 }, { width: 3200, height: 2400, quality: .78 }, { width: 2560, height: 1920, quality: .92 }]);
  assert.equal(large.width, 2560); assert.equal(large.height, 1920); assert.equal(closed, 2);
  assert.equal(canvas.width, 0); assert.equal(canvas.height, 0); assert.deepEqual(original, untouched);
  for (const aiProvider of ["gemini", "minimax"]) {
    const request = makeAiRequest({ ...emptyArchive().settings, model: "synthetic-model", aiProvider }, "SYNTHETIC_KEY_NOT_REAL", "OCR", {}, large);
    assert.ok(byteSize(request) <= 950 * 1024); assert.ok(byteSize(request.body) <= 1024 * 1024); assert.equal(typeof request.body, "string");
  }
  attempts = [];
  document.createElement = (tag) => {
    const node = new MemoryNode(tag);
    if (tag === "canvas") node.toBlob = (callback, type, quality) => { attempts.push({ width: node.width, quality }); callback(new Blob([new Uint8Array(uploadLimit + 1)], { type })); };
    return node;
  };
  await assert.rejects(reportImage(source, "image/png"), /裁切/);
  assert.ok(attempts.every((entry) => entry.width >= 1280)); assert.equal(attempts.at(-1).width, 1280); assert.equal(closed, 3);
  let decoded = false; globalThis.createImageBitmap = async () => { decoded = true; throw new Error("synthetic decode error"); };
  await assert.rejects(reportImage(new Uint8Array([1, 2, 3]), "image/png")); assert.equal(decoded, false);
  await assert.rejects(reportImage(png, "image/png"), /无法解码/);
});
