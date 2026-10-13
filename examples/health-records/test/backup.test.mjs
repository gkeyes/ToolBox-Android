import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { deflateRawSync } from "node:zlib";
import { HealthError, normalizeArchive, emptyArchive, exportArchive } from "../web/model.mjs";
import { archiveToWorkbook, encodeWorkbook, workbookToArchive, checkZipBudget, checkWorkbookBudget } from "../web/backup.mjs";
import { checkInflatedZipBudget } from "../web/zip-budget.mjs";

const XLSX = createRequire(import.meta.url)("../web/vendor/xlsx.full.min.js");

const CRC_TABLE = Uint32Array.from({ length: 256 }, (_, byte) => {
  for (let bit = 0; bit < 8; bit++) byte = byte & 1 ? 0xedb88320 ^ (byte >>> 1) : byte >>> 1;
  return byte >>> 0;
});
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = CRC_TABLE[(crc ^ byte) & 255] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

/**
 * Builds a single-disk ZIP. Each entry may override what the directory claims
 * (size, crc, compressed bytes, flags, method, extra) to model a hostile file.
 */
function zip(entries, { prefix = new Uint8Array(0), gap = new Uint8Array(0) } = {}) {
  const parts = [prefix], central = [];
  let offset = prefix.length;
  for (const entry of entries) {
    const name = new TextEncoder().encode(entry.name);
    const data = entry.data instanceof Uint8Array ? entry.data : new TextEncoder().encode(entry.data);
    const method = entry.method ?? 8;
    const body = entry.compressed ?? (method === 8 ? new Uint8Array(deflateRawSync(data)) : data);
    const extra = entry.extra ?? new Uint8Array(0);
    const flags = entry.flags ?? 0, crc = entry.crc ?? crc32(data), size = entry.size ?? data.length;
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true); local.setUint16(4, 20, true); local.setUint16(6, flags, true); local.setUint16(8, method, true);
    local.setUint32(14, crc, true); local.setUint32(18, body.length, true); local.setUint32(22, size, true);
    local.setUint16(26, name.length, true); local.setUint16(28, extra.length, true);
    const header = new DataView(new ArrayBuffer(46));
    header.setUint32(0, 0x02014b50, true); header.setUint16(4, 20, true); header.setUint16(6, 20, true); header.setUint16(8, flags, true); header.setUint16(10, method, true);
    header.setUint32(16, crc, true); header.setUint32(20, body.length, true); header.setUint32(24, size, true);
    header.setUint16(28, name.length, true); header.setUint16(30, extra.length, true); header.setUint32(42, offset, true);
    parts.push(new Uint8Array(local.buffer), name, extra, body);
    central.push(new Uint8Array(header.buffer), name, extra);
    offset += 30 + name.length + extra.length + body.length;
  }
  parts.push(gap); offset += gap.length; // Unlisted bytes between the last entry and the directory.
  const directorySize = central.reduce((sum, part) => sum + part.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, entries.length, true); end.setUint16(10, entries.length, true);
  end.setUint32(12, directorySize, true); end.setUint32(16, offset, true);
  const all = [...parts, ...central, new Uint8Array(end.buffer)];
  const out = new Uint8Array(all.reduce((sum, part) => sum + part.length, 0));
  let cursor = 0;
  for (const part of all) { out.set(part, cursor); cursor += part.length; }
  return out;
}

const rejectsWith = (promise, pattern) => assert.rejects(promise, (error) => error instanceof HealthError && pattern.test(error.message));

test("non-ZIP bytes skip the ZIP checks (legacy .xls is parsed by SheetJS)", async () => {
  assert.equal(checkZipBudget(new Uint8Array([0xd0, 0xcf, 0x11, 0xe0])), undefined);
  await checkWorkbookBudget(new Uint8Array([0xd0, 0xcf, 0x11, 0xe0]));
});

test("an honest ZIP with deflated and stored entries passes and reports each entry", async () => {
  const text = "健康档案 ".repeat(500);
  const bytes = zip([{ name: "a.xml", data: text }, { name: "b.bin", data: new Uint8Array([1, 2, 3]), method: 0 }]);
  const files = checkZipBudget(bytes);
  assert.deepEqual(files.map((f) => [f.method, f.size]), [[8, new TextEncoder().encode(text).length], [0, 3]]);
  await checkWorkbookBudget(bytes);
});

test("a ZIP bomb that inflates past its declared size is rejected before it is fully inflated", async () => {
  const real = new Uint8Array(64 * 1024 * 1024); // 64 MiB of zeros deflates to about 64 KiB.
  const bytes = zip([{ name: "xl/worksheets/sheet1.xml", data: real, size: 1024 }]);
  assert.ok(bytes.length < 512 * 1024);
  await rejectsWith(checkWorkbookBudget(bytes), /实际解压大小与目录不一致/);
});

test("a deflated entry smaller than its declared size, or with a wrong CRC, is rejected", async () => {
  const data = new TextEncoder().encode("x".repeat(4096));
  await rejectsWith(checkWorkbookBudget(zip([{ name: "a", data, size: data.length + 1 }])), /实际大小|校验/);
  await rejectsWith(checkWorkbookBudget(zip([{ name: "a", data, crc: crc32(data) ^ 1 }])), /实际大小|校验/);
});

test("bytes hidden after the end of a deflate stream inside an entry are rejected", async () => {
  const data = new TextEncoder().encode("hello workbook");
  const compressed = new Uint8Array([...deflateRawSync(data), 0xde, 0xad, 0xbe, 0xef, 0xde, 0xad, 0xbe, 0xef]);
  await rejectsWith(checkWorkbookBudget(zip([{ name: "a", data, compressed }])), /压缩数据损坏|实际大小/);
});

test("a stored entry whose content does not match its CRC is rejected", async () => {
  await rejectsWith(checkWorkbookBudget(zip([{ name: "a", data: "abc", method: 0, crc: 0 }])), /实际大小或内容校验不一致/);
});

test("checkInflatedZipBudget validates every entry the directory lists", async () => {
  const good = new TextEncoder().encode("first");
  const bytes = zip([{ name: "a", data: good }, { name: "b", data: new Uint8Array(1 << 20), size: 10 }]);
  const files = checkZipBudget(bytes);
  await rejectsWith(checkInflatedZipBudget(bytes, files), /实际解压大小与目录不一致/);
  await checkInflatedZipBudget(bytes, files.slice(0, 1));
});

test("ZIP structure checks reject damaged, split, encrypted, ZIP64 and padded archives", () => {
  const entry = { name: "a", data: "abc" };
  const ok = zip([entry]);
  const throwsWith = (bytes, pattern) => assert.throws(() => checkZipBudget(bytes), (error) => error instanceof HealthError && pattern.test(error.message));
  throwsWith(ok.slice(0, ok.length - 1), /目录损坏/);
  const split = ok.slice(); new DataView(split.buffer).setUint16(split.length - 18, 1, true);
  throwsWith(split, /分卷或损坏/);
  throwsWith(zip([{ ...entry, flags: 1 }]), /加密或压缩格式/);
  throwsWith(zip([{ ...entry, method: 12 }]), /加密或压缩格式/);
  throwsWith(zip([{ ...entry, extra: new Uint8Array([1, 0, 0, 0]) }]), /ZIP64/);
  throwsWith(zip([{ ...entry, extra: new Uint8Array([9, 0, 8, 0]) }]), /扩展字段损坏/);
  throwsWith(zip([{ ...entry, method: 0, size: 2 }]), /目录与条目不一致/);
  throwsWith(zip([entry], { gap: new Uint8Array(16) }), /目录外数据/);
  throwsWith(zip([entry], { prefix: new TextEncoder().encode("PK__junk") }), /目录外数据|重叠/);
});

test("a ZIP bomb hidden behind leading junk is not unzipped and cannot import", async () => {
  // Without a leading "PK" the ZIP checks do not apply; SheetJS then reads the bytes as text instead of inflating them.
  const bytes = zip([{ name: "xl/worksheets/sheet1.xml", data: new Uint8Array(64 * 1024 * 1024), size: 1024 }], { prefix: new Uint8Array(16) });
  assert.equal(checkZipBudget(bytes), undefined);
  await checkWorkbookBudget(bytes);
  const workbook = XLSX.read(bytes, { type: "array", cellDates: false, cellFormula: false, cellHTML: false, cellStyles: false, bookVBA: false });
  assert.throws(() => workbookToArchive(workbook, XLSX, noNewIds), (error) => error instanceof HealthError && /缺少/.test(error.message));
});

const sample = () => normalizeArchive({
  ...emptyArchive(),
  records: [
    { id: "r1", date: "2024-01-05", type: "blood", items: [
      { name: "WBC", value: "5.20", unit: "10^9/L", normal: "3.5-9.5" },
      { name: "_x0041_ 转义样例", value: "阴性", unit: "", normal: "阴性" },
    ] },
    { id: "r2", date: "2024-03-01", type: "urine", items: [{ name: "蛋白", value: "+", unit: "", normal: "阴性" }] },
    { id: "r3", date: "2024-03-01", type: "blood", items: [{ name: "WBC", value: "0.4", unit: "10^9/L", normal: "<0.5" }] },
  ],
  profile: { gender: "女", age: "34", height: "165", weight: "55.5", history: "无\n第二行" },
  aliasMap: { "白细胞": "WBC" },
  lib: { WBC: { unit: "10^9/L", normal: "3.5-9.5" } },
  healthSummary: { text: "总体稳定", time: "2024-03-02 10:00" },
  settings: { theme: "dark", model: "gemini-test", aiProvider: "minimax", minimaxModel: "MiniMax-M3" },
});
const noNewIds = () => { throw new Error("records with ids must keep them"); };

test("Excel backup round-trips through export, the ZIP checks and import", async () => {
  const archive = sample();
  const bytes = encodeWorkbook(archiveToWorkbook(archive, XLSX), XLSX);
  await checkWorkbookBudget(bytes); // Our own exports must pass the import-side ZIP checks.
  const workbook = XLSX.read(bytes, { type: "array", cellDates: false, cellFormula: false, cellHTML: false, cellStyles: false, bookVBA: false });
  assert.deepEqual(workbookToArchive(workbook, XLSX, noNewIds), archive);
});

test("JSON backup round-trips through export and import", () => {
  const archive = sample();
  const restored = normalizeArchive(JSON.parse(JSON.stringify(exportArchive(archive))), noNewIds);
  assert.deepEqual(restored, archive);
});
