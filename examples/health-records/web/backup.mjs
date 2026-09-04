import { TYPES, emptyArchive, normalizeArchive, HealthError, localDate } from "./model.mjs";

function dateText(value, XLSX, date1904) {
  if (typeof value === "number") {
    const d = XLSX.SSF.parse_date_code(value, { date1904 });
    if (!d) throw new HealthError("Excel 日期无效");
    return `${d.y}-${String(d.m).padStart(2, "0")}-${String(d.d).padStart(2, "0")}`;
  }
  return String(value ?? "").trim().replace(/^(\d{4})[/.](\d{1,2})[/.](\d{1,2})$/, (_, y, m, d) => `${y}-${m.padStart(2, "0")}-${d.padStart(2, "0")}`);
}

export function workbookToArchive(workbook, XLSX, makeId) {
  if (!Array.isArray(workbook.SheetNames) || workbook.SheetNames.length > 16) throw new HealthError("Excel 工作表数量过多");
  for (const name of workbook.SheetNames) {
    const ref = workbook.Sheets[name]?.["!ref"];
    if (!ref) continue;
    const range = XLSX.utils.decode_range(ref);
    if (range.e.r > 20000 || range.e.c > 63 || (range.e.r + 1) * (range.e.c + 1) > 200000) throw new HealthError("Excel 表格过大，请只保留健康档案相关工作表");
  }
  const dataName = ["HealthData", "Data"].find((n) => workbook.SheetNames.includes(n)) || workbook.SheetNames[0];
  if (!dataName) throw new HealthError("Excel 中没有数据工作表");
  const headers = XLSX.utils.sheet_to_json(workbook.Sheets[dataName], { header: 1, raw: true })[0] || [];
  if (!["日期", "类型", "指标", "数值"].every((key) => headers.includes(key))) throw new HealthError("Excel 缺少「日期、类型、指标、数值」列，请使用旧站或本工具导出的格式");
  const rows = XLSX.utils.sheet_to_json(workbook.Sheets[dataName], { defval: "", raw: true });
  const archive = emptyArchive(), grouped = new Map();
  for (const row of rows) {
    if (!["日期", "类型", "指标", "数值"].every((key) => Object.hasOwn(row, key))) throw new HealthError("Excel 缺少「日期、类型、指标、数值」列，请使用旧站或本工具导出的格式");
    const date = dateText(row["日期"], XLSX, workbook.Workbook?.WBProps?.date1904);
    const rawType = String(row["类型"]), type = Object.hasOwn(TYPES, rawType) ? rawType : Object.entries(TYPES).find(([, name]) => name === rawType)?.[0];
    const recordId = row["记录ID"] === "" || row["记录ID"] == null ? null : String(row["记录ID"]);
    const key = JSON.stringify([date, type, recordId]);
    if (!grouped.has(key)) grouped.set(key, { id: recordId || makeId(), date, type, items: [] });
    grouped.get(key).items.push({ name: row["指标"], value: row["数值"], unit: row["单位"] ?? "", normal: row["参考"] ?? row["参考范围"] ?? "" });
  }
  archive.records = [...grouped.values()];
  const sheetRows = (name) => workbook.Sheets[name] ? XLSX.utils.sheet_to_json(workbook.Sheets[name], { defval: "", raw: true }) : [];
  const fields = { "性别": "gender", "年龄": "age", "身高": "height", "体重": "weight", "病史": "history" };
  for (const row of sheetRows("UserProfile")) if (Object.hasOwn(fields, row.Item)) archive.profile[fields[row.Item]] = String(row.Value);
  archive.aliasMap = Object.fromEntries(sheetRows("CleanRules").filter((r) => r["原名"] && r["标准名"]).map((r) => [String(r["原名"]), String(r["标准名"])]));
  archive.lib = Object.fromEntries(sheetRows("IndicatorLibrary").filter((r) => r["指标"]).map((r) => [String(r["指标"]), { unit: r["单位"], normal: r["参考"] }]));
  for (const row of sheetRows("Settings")) {
    if (row.Key === "Model Name") archive.settings.model = String(row.Value);
    if (row.Key === "Theme") archive.settings.theme = String(row.Value);
    if (row.Key === "AI Provider") archive.settings.aiProvider = String(row.Value);
    if (row.Key === "MiniMax Model") archive.settings.minimaxModel = String(row.Value);
  }
  const summary = sheetRows("Summary")[0];
  if (summary) archive.healthSummary = { text: summary["摘要"], time: summary["时间"] };
  return normalizeArchive(archive, makeId);
}

export function archiveToWorkbook(archive, XLSX) {
  const clean = normalizeArchive(archive), workbook = XLSX.utils.book_new();
  const records = clean.records.flatMap((record) => record.items.map((item) => ({
    "记录ID": record.id, "日期": record.date, "类型": record.type, "指标": item.name, "数值": item.value, "单位": item.unit, "参考": item.normal,
  })));
  const append = (name, rows, header) => XLSX.utils.book_append_sheet(workbook, XLSX.utils.json_to_sheet(rows, header ? { header } : undefined), name);
  append("HealthData", records, ["记录ID", "日期", "类型", "指标", "数值", "单位", "参考"]);
  append("UserProfile", Object.entries({ "性别": "gender", "年龄": "age", "身高": "height", "体重": "weight", "病史": "history" }).map(([Item, key]) => ({ Item, Value: clean.profile[key] })));
  append("CleanRules", Object.entries(clean.aliasMap).map(([from, to]) => ({ "原名": from, "标准名": to })), ["原名", "标准名"]);
  append("IndicatorLibrary", Object.entries(clean.lib).map(([name, item]) => ({ "指标": name, "单位": item.unit, "参考": item.normal })), ["指标", "单位", "参考"]);
  append("Summary", [{ "摘要": clean.healthSummary.text, "时间": clean.healthSummary.time }]);
  append("Settings", [{ Key: "Model Name", Value: clean.settings.model }, { Key: "Theme", Value: clean.settings.theme }, { Key: "AI Provider", Value: clean.settings.aiProvider }, { Key: "MiniMax Model", Value: clean.settings.minimaxModel }]);
  return workbook;
}

export function backupName(extension) { return `HealthArchive_${localDate().replaceAll("-", "")}.${extension}`; }

export function encodeWorkbook(workbook, XLSX) {
  const encoded = structuredClone(workbook);
  for (const sheet of Object.values(encoded.Sheets)) for (const cell of Object.values(sheet)) {
    // Escape leading underscores, including overlapping Excel escape sequences.
    if (cell?.t === "s" && typeof cell.v === "string") cell.v = cell.v.replace(/_(?=x[0-9a-f]{4}_)/gi, "_x005F_");
  }
  return new Uint8Array(XLSX.write(encoded, { bookType: "xlsx", type: "array", compression: true, bookSST: true }));
}

export function checkZipBudget(bytes) {
  if (bytes[0] !== 0x50 || bytes[1] !== 0x4b) return;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let end = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (view.getUint32(i, true) === 0x06054b50 && i + 22 + view.getUint16(i + 20, true) === bytes.length) { end = i; break; }
  }
  if (end < 0) throw new HealthError("Excel ZIP 目录损坏");
  const entries = view.getUint16(end + 10, true), directorySize = view.getUint32(end + 12, true), directoryStart = view.getUint32(end + 16, true);
  if (view.getUint16(end + 4, true) || view.getUint16(end + 6, true) || view.getUint16(end + 8, true) !== entries || entries > 256 || directoryStart + directorySize !== end) throw new HealthError("Excel ZIP 目录超限、分卷或损坏，请另存为普通小文件");
  function checkExtra(start, length) {
    const stop = start + length;
    for (let cursor = start; cursor < stop;) {
      if (cursor + 4 > stop) throw new HealthError("Excel ZIP 扩展字段损坏");
      const kind = view.getUint16(cursor, true), size = view.getUint16(cursor + 2, true);
      if (kind === 1) throw new HealthError("不支持 ZIP64 扩展大小，请另存为普通小型 Excel 文件");
      cursor += 4 + size;
      if (cursor > stop) throw new HealthError("Excel ZIP 扩展字段损坏");
    }
  }
  const spans = [];
  let offset = directoryStart, total = 0;
  for (let i = 0; i < entries; i++) {
    if (offset + 46 > end || view.getUint32(offset, true) !== 0x02014b50) throw new HealthError("Excel ZIP 条目损坏");
    const flags = view.getUint16(offset + 8, true), method = view.getUint16(offset + 10, true);
    const size = view.getUint32(offset + 24, true), compressed = view.getUint32(offset + 20, true);
    const nameLength = view.getUint16(offset + 28, true), extraLength = view.getUint16(offset + 30, true);
    const nextOffset = offset + 46 + nameLength + extraLength + view.getUint16(offset + 32, true);
    if (nextOffset > end || view.getUint16(offset + 34, true)) throw new HealthError("Excel ZIP 条目损坏或不支持分卷");
    checkExtra(offset + 46 + nameLength, extraLength);
    total += size;
    if (flags & 65 || ![0, 8].includes(method) || size > 8 * 1024 * 1024 || total > 12 * 1024 * 1024) throw new HealthError("Excel 加密或解压后过大，请先另存为普通小文件");
    const local = view.getUint32(offset + 42, true);
    if (local + 30 > directoryStart || view.getUint32(local, true) !== 0x04034b50) throw new HealthError("Excel ZIP 本地条目损坏");
    const localName = view.getUint16(local + 26, true), localExtra = view.getUint16(local + 28, true);
    const dataStart = local + 30 + localName + localExtra, dataEnd = dataStart + compressed;
    if (dataEnd > directoryStart || localName !== nameLength || view.getUint16(local + 6, true) !== flags || view.getUint16(local + 8, true) !== method || (method === 0 && size !== compressed)) throw new HealthError("Excel ZIP 目录与条目不一致");
    checkExtra(local + 30 + localName, localExtra);
    for (let n = 0; n < nameLength; n++) if (bytes[local + 30 + n] !== bytes[offset + 46 + n]) throw new HealthError("Excel ZIP 文件名不一致");
    for (const [position, expected] of [[14, view.getUint32(offset + 16, true)], [18, compressed], [22, size]]) {
      const actual = view.getUint32(local + position, true);
      if (actual !== expected && !(flags & 8 && actual === 0)) throw new HealthError("Excel ZIP 大小或校验信息不一致");
    }
    spans.push([local, dataEnd]); offset = nextOffset;
  }
  if (offset !== directoryStart + directorySize) throw new HealthError("Excel ZIP 目录长度不匹配");
  spans.sort((a, b) => a[0] - b[0]);
  for (let i = 1; i < spans.length; i++) if (spans[i][0] < spans[i - 1][1]) throw new HealthError("Excel ZIP 条目重叠");
}
