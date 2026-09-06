import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { crc32, deflateRawSync } from "node:zlib";
import { archiveToWorkbook, encodeWorkbook, checkWorkbookBudget } from "../web/backup.mjs";
import { emptyArchive, MAX_FILE_BYTES } from "../web/model.mjs";

const XLSX = createRequire(import.meta.url)("../web/vendor/xlsx.full.min.js");
const MiB = 1024 * 1024;

function zip(parts) {
  const locals = [], directory = [];
  let offset = 0;
  for (const part of parts) {
    const name = Buffer.from(part.name), method = part.method ?? 8;
    const data = part.compressed ?? (method === 8 ? deflateRawSync(part.bytes) : part.bytes);
    const size = part.declaredSize ?? part.bytes.length, crc = part.crc ?? crc32(part.bytes);
    const local = Buffer.alloc(30), central = Buffer.alloc(46), descriptor = Buffer.alloc(part.descriptor ? 16 : 0);
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(method, 8);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(size, 22); local.writeUInt16LE(name.length, 26);
    central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 6); central.writeUInt16LE(method, 10);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(size, 24); central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    if (part.descriptor) {
      local.writeUInt16LE(8, 6); local.fill(0, 14, 26); central.writeUInt16LE(8, 8);
      descriptor.writeUInt32LE(0x08074b50); descriptor.writeUInt32LE(crc, 4); descriptor.writeUInt32LE(data.length, 8); descriptor.writeUInt32LE(size, 12);
    }
    locals.push(local, name, data, descriptor); directory.push(central, name);
    offset += local.length + name.length + data.length + descriptor.length;
  }
  const dir = Buffer.concat(directory), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(parts.length, 8); end.writeUInt16LE(parts.length, 10);
  end.writeUInt32LE(dir.length, 12); end.writeUInt32LE(offset, 16);
  return new Uint8Array(Buffer.concat([...locals, dir, end]));
}

const workbook = archiveToWorkbook(emptyArchive(), XLSX);
const base = XLSX.CFB.read(encodeWorkbook(workbook, XLSX), { type: "array" });
const workbookParts = base.FileIndex.flatMap((file, i) => file.type === 2 && file.name !== "\u0001Sh33tJ5"
  ? [{ name: base.FullPaths[i].slice(base.FullPaths[0].length), bytes: Buffer.from(file.content) }] : []);
let workerSequence = 0, workerHandler;

async function readWorker(bytes, { name = "synthetic.xlsx", onDecode = () => {} } = {}) {
  const previous = globalThis.self;
  let reply;
  globalThis.self = {
    XLSX: { ...XLSX, read(...args) { onDecode(); return XLSX.read(...args); } },
    addEventListener(_type, listener) { workerHandler = listener; },
    postMessage(message) { reply = message; },
  };
  try {
    if (!workerHandler) await import("../web/io-worker.js");
    await workerHandler({ data: { id: ++workerSequence, operation: "read", name, bytes } });
    return reply;
  } finally { globalThis.self = previous; }
}

test("Excel worker rejects forged uncompressed sizes before invoking SheetJS", async () => {
  const forged = zip([...workbookParts, { name: "customXml/padding.xml", bytes: Buffer.alloc(16 * MiB, 32), declaredSize: 0 }]);
  assert.ok(forged.length < MAX_FILE_BYTES);
  let decodes = 0;
  const reply = await readWorker(forged, { onDecode: () => decodes++ });
  assert.ok(reply.error, "the worker accepted a ZIP declaring zero bytes but inflating to 16 MiB");
  assert.equal(decodes, 0, "untrusted compressed data reached SheetJS before actual length validation");
});

test("Excel inflation stops at the actual entry budget without draining an oversized stream", async () => {
  const NativeDecompressor = globalThis.DecompressionStream;
  let outputBytes = 0, largestInput = 0;
  globalThis.DecompressionStream = class {
    constructor(format) {
      const stream = new NativeDecompressor(format), writer = stream.writable.getWriter();
      this.writable = new WritableStream({
        write(chunk) { largestInput = Math.max(largestInput, chunk.length); return writer.write(chunk); },
        close() { return writer.close(); }, abort(reason) { return writer.abort(reason); },
      });
      this.readable = stream.readable.pipeThrough(new TransformStream({
        transform(chunk, controller) { outputBytes += chunk.length; controller.enqueue(chunk); },
      }));
    }
  };
  try {
    const forged = zip([{ name: "padding.xml", bytes: Buffer.alloc(16 * MiB, 32), declaredSize: 8 * MiB }]);
    await assert.rejects(checkWorkbookBudget(forged), /解压后过大/);
    assert.equal(largestInput, 128);
    assert.ok(outputBytes > 8 * MiB && outputBytes < 9 * MiB, `inflater produced ${outputBytes} bytes instead of stopping near 8 MiB`);
  } finally { globalThis.DecompressionStream = NativeDecompressor; }
});

test("Excel inflation accepts exact 8 MiB entry and 12 MiB total budgets and rejects the next byte", async () => {
  const first = { name: "first.xml", bytes: Buffer.alloc(8 * MiB, 32) };
  const second = { name: "second.xml", bytes: Buffer.alloc(4 * MiB, 32) };
  await checkWorkbookBudget(zip([first, second]));
  await assert.rejects(checkWorkbookBudget(zip([{ ...first, bytes: Buffer.alloc(8 * MiB + 1, 32) }])), /解压后过大/);
  await assert.rejects(checkWorkbookBudget(zip([first, { ...second, bytes: Buffer.alloc(4 * MiB + 1, 32) }])), /解压后过大/);
  await assert.rejects(checkWorkbookBudget(zip([{ name: "wrong-short.xml", bytes: Buffer.from("value"), declaredSize: 6 }])), /实际大小/);
});

test("Excel worker imports ordinary XLSX and legacy XLS while refusing corrupt compressed content", async () => {
  const archive = emptyArchive();
  archive.records.push({ id: "zip-roundtrip", date: "2026-09-06", type: "blood_bio", items: [{ name: "合成项目", value: "0", unit: "U/L", normal: "0–1" }] });
  const source = archiveToWorkbook(archive, XLSX);
  const normal = await readWorker(encodeWorkbook(source, XLSX));
  assert.equal(normal.error, undefined); assert.deepEqual(normal.result, archive);
  const legacy = await readWorker(new Uint8Array(XLSX.write(source, { type: "array", bookType: "biff8" })), { name: "synthetic.xls" });
  assert.equal(legacy.error, undefined); assert.deepEqual(legacy.result, archive);
  for (const part of [
    { name: "corrupt.xml", bytes: Buffer.from("valid"), compressed: Buffer.from([255, 255, 255]) },
    { name: "crc.xml", bytes: Buffer.from("valid"), crc: 0 },
    { name: "stored.xml", bytes: Buffer.from("valid"), method: 0, crc: 0 },
    { name: "trailing.xml", bytes: Buffer.from("valid"), compressed: Buffer.concat([deflateRawSync(Buffer.from("valid")), Buffer.from([0])]) },
  ]) {
    let decodes = 0;
    const reply = await readWorker(zip([...workbookParts, part]), { onDecode: () => decodes++ });
    assert.ok(reply.error, part.name); assert.equal(decodes, 0, part.name);
  }
});

test("Excel ZIP validation supports descriptors and blocks unlisted local entries", async () => {
  await checkWorkbookBudget(zip([{ name: "descriptor.xml", bytes: Buffer.from("value"), descriptor: true }]));
  const descriptorWorkbook = await readWorker(zip(workbookParts.map((part) => ({ ...part, descriptor: true }))));
  assert.equal(descriptorWorkbook.error, undefined); assert.deepEqual(descriptorWorkbook.result, emptyArchive());
  const bytes = Buffer.from(zip([{ name: "first.xml", bytes: Buffer.from("one") }, { name: "hidden.xml", bytes: Buffer.from("two") }]));
  const end = bytes.length - 22, central = bytes.readUInt32LE(end + 16);
  const firstDirectoryBytes = 46 + bytes.readUInt16LE(central + 28);
  const hiddenDirectoryBytes = bytes.readUInt32LE(end + 12) - firstDirectoryBytes;
  const unlisted = Buffer.concat([bytes.subarray(0, central + firstDirectoryBytes), bytes.subarray(end)]);
  const newEnd = unlisted.length - 22;
  unlisted.writeUInt16LE(1, newEnd + 8); unlisted.writeUInt16LE(1, newEnd + 10);
  unlisted.writeUInt32LE(bytes.readUInt32LE(end + 12) - hiddenDirectoryBytes, newEnd + 12);
  await assert.rejects(checkWorkbookBudget(unlisted), /目录外数据/);
});

test("unsupported native inflation fails closed with a usable import alternative", async () => {
  const NativeDecompressor = globalThis.DecompressionStream;
  globalThis.DecompressionStream = undefined;
  try {
    let decodes = 0;
    const reply = await readWorker(encodeWorkbook(workbook, XLSX), { onDecode: () => decodes++ });
    assert.equal(reply.error.code, "UNSUPPORTED"); assert.match(reply.error.message, /WebView.*JSON/);
    assert.equal(decodes, 0);
    await checkWorkbookBudget(zip([{ name: "stored.xml", bytes: Buffer.from("value"), method: 0 }]));
    const legacy = await readWorker(new Uint8Array(XLSX.write(workbook, { type: "array", bookType: "biff8" })), { name: "synthetic.xls" });
    assert.equal(legacy.error, undefined);
  } finally { globalThis.DecompressionStream = NativeDecompressor; }
});
