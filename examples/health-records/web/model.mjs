export const TYPES = Object.freeze({ blood: "血常规", blood_bio: "血生化", urine: "尿常规", urine_bio: "尿生化" });
export const MAX_ARCHIVE_BYTES = 600 * 1024;
export const MAX_FILE_BYTES = 700 * 1024;
export const MAX_RECORDS = 2000;
const encoder = new TextEncoder();
export const byteSize = (value) => encoder.encode(typeof value === "string" ? value : JSON.stringify(value)).length;
export const copy = (value) => structuredClone(value);
export const newId = () => crypto.randomUUID();
export const specimen = (type) => type.startsWith("urine") ? "尿样" : "血样";

export class HealthError extends Error {
  constructor(message, code = "INVALID_DATA") { super(message); this.name = "HealthError"; this.code = code; }
}

export function localDate(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

export function validDate(value) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  const d = new Date(Date.UTC(year, month - 1, day));
  return year >= 1900 && year <= 2200 && d.getUTCFullYear() === year && d.getUTCMonth() === month - 1 && d.getUTCDate() === day;
}

function text(value, max, field, required = false) {
  if (value == null) value = "";
  if (!["string", "number"].includes(typeof value) || (typeof value === "number" && !Number.isFinite(value))) throw new HealthError(`${field}格式不正确`);
  const result = String(value).trim();
  if (result.length > max || (required && !result) || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(result)) throw new HealthError(`${field}为空、过长或含无效字符`);
  return result;
}

export function normalizeItem(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new HealthError("指标格式不正确");
  return {
    name: text(value.name, 120, "指标名称", true), value: text(value.value, 120, "检验结果", true),
    unit: text(value.unit, 80, "单位"), normal: text(value.normal, 200, "参考范围"),
  };
}

export function normalizeRecord(value, makeId = newId) {
  if (!value || typeof value !== "object" || Array.isArray(value) || !validDate(value.date) || typeof value.type !== "string" || !Object.hasOwn(TYPES, value.type)) throw new HealthError("记录日期或检验类型不正确，请检查原文件");
  if (!Array.isArray(value.items) || !value.items.length || value.items.length > 120) throw new HealthError("每份记录需包含 1–120 项指标");
  const id = value.id == null ? makeId() : text(value.id, 100, "记录标识", true);
  return { id, date: value.date, type: value.type, items: value.items.map(normalizeItem) };
}

export function emptyArchive() {
  return {
    schemaVersion: 1, records: [], profile: { gender: "", age: "", height: "", weight: "", history: "" },
    lib: {}, aliasMap: {}, healthSummary: { text: "", time: "" },
    settings: { theme: "system", model: "", aiProvider: "gemini", minimaxModel: "MiniMax-M3" },
  };
}

export function normalizeArchive(value, makeId = newId) {
  if (!value || typeof value !== "object" || Array.isArray(value) || !Array.isArray(value.records)) throw new HealthError("不是有效的健康档案备份，请选择旧站 user_data.json 或健康档案导出的文件");
  if (value.schemaVersion !== undefined && value.schemaVersion !== 1) throw new HealthError("此备份版本暂不支持");
  if (value.records.length > MAX_RECORDS) throw new HealthError(`最多支持 ${MAX_RECORDS} 份记录，请拆分备份`);
  const archive = emptyArchive();
  const ids = new Set();
  archive.records = value.records.map((row) => {
    const record = normalizeRecord(row, makeId);
    if (ids.has(record.id)) record.id = makeId();
    ids.add(record.id);
    return record;
  });
  for (const key of ["gender", "age", "height", "weight", "history"]) archive.profile[key] = text(value.profile?.[key], key === "history" ? 10000 : 40, "个人档案");
  const lib = value.lib || {};
  if (typeof lib !== "object" || Array.isArray(lib) || Object.keys(lib).length > 3000) throw new HealthError("历史指标库格式不正确或过大");
  archive.lib = Object.fromEntries(Object.entries(lib).map(([name, item]) => [text(name, 120, "历史指标名称", true), {
    unit: text(item?.unit, 80, "历史指标单位"), normal: text(item?.normal, 200, "历史参考范围"),
  }]));
  const aliases = value.aliasMap || {};
  if (typeof aliases !== "object" || Array.isArray(aliases) || Object.keys(aliases).length > 3000) throw new HealthError("别名表格式不正确或过大");
  archive.aliasMap = Object.fromEntries(Object.entries(aliases).map(([from, to]) => [text(from, 400, "别名", true), text(to, 120, "标准名称", true)]));
  archive.healthSummary = { text: text(value.healthSummary?.text, 30000, "历史摘要"), time: text(value.healthSummary?.time, 100, "摘要时间") };
  archive.settings = {
    theme: ["light", "dark", "system"].includes(value.settings?.theme) ? value.settings.theme : "system",
    model: text(value.settings?.model ?? value.config?.model, 100, "模型名称"),
    aiProvider: value.settings?.aiProvider ?? "gemini",
    minimaxModel: text(value.settings?.minimaxModel ?? "MiniMax-M3", 100, "MiniMax 模型名称"),
  };
  if (!["gemini", "minimax"].includes(archive.settings.aiProvider)) throw new HealthError("AI 服务设置无效，请选择 Gemini 或 MiniMax");
  if (byteSize(archive) > MAX_ARCHIVE_BYTES) throw new HealthError("档案超过 600 KiB，请先分批导出记录。原数据未修改", "QUOTA_EXCEEDED");
  return archive;
}

export function canonicalUnit(unit) {
  const superscript = { "⁰": "0", "¹": "1", "²": "2", "³": "3", "⁴": "4", "⁵": "5", "⁶": "6", "⁷": "7", "⁸": "8", "⁹": "9", "⁻": "-", "⁺": "+" };
  let value = String(unit || "").replace(/[⁰¹²³⁴⁵⁶⁷⁸⁹⁻⁺]+/g, (s) => `^${Array.from(s, (c) => superscript[c]).join("")}`).normalize("NFKC").replace(/\s/g, "").replace(/µ/g, "μ");
  if (/^(?:null|undefined|n\/a)$/i.test(value)) return "";
  value = value.replace(/^[*x×](?=10\^\d+\/)/, "").replace(/^u(?=mol(?:\/|$)|g(?:\/|$)|L(?:\/|$)|IU(?:\/|$))/, "μ");
  value = value.replace(/\/([mμu]?)[lL](?=$|\/)/g, (_, prefix) => `/${prefix === "u" ? "μ" : prefix}L`).replace(/^([mμ]?)[lL](?=$|\/)/, "$1L");
  return /^(?:fl|FL)$/.test(value) ? "fL" : value;
}

export function metricKey(type, item) {
  return JSON.stringify([specimen(type), item.name.normalize("NFKC").trim().toLowerCase(), canonicalUnit(item.unit)]);
}

const NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:e[+-]?\\d+)?";
const numberPattern = new RegExp(`^${NUMBER}$`, "i");
const comparatorPattern = new RegExp(`^(<=|>=|<|>|≤|≥)\\s*(${NUMBER})$`, "i");
const rangePattern = new RegExp(`^(${NUMBER})\\s*[-~～–—至]\\s*(${NUMBER})$`, "i");

function normalizedResult(value) {
  return String(value ?? "").trim().replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/−/g, "-").replace(/（/g, "(").replace(/）/g, ")");
}

function qualitative(value) {
  if (/^(阴|阴性(?:\s*\(-\))?|未检出|negative|neg\.?|-)$/i.test(value)) return "negative";
  if (/^(阳性(?:\s*\(\++\))?|positive|pos\.?|\+{1,4})$/i.test(value)) return "positive";
  return null;
}

function interval(raw, reference = false) {
  const value = normalizedResult(raw);
  const q = qualitative(value);
  if (q) return { qualitative: q };
  if (numberPattern.test(value)) {
    const n = numericValue(value);
    if (n === null) return null;
    return { min: n, max: n, minClosed: true, maxClosed: true };
  }
  const c = comparatorPattern.exec(value);
  if (c) {
    const n = numericValue(c[2]);
    if (n === null) return null;
    return c[1].includes("<") || c[1] === "≤"
      ? { min: -Infinity, max: n, minClosed: false, maxClosed: ["<=", "≤"].includes(c[1]) }
      : { min: n, max: Infinity, minClosed: [">=", "≥"].includes(c[1]), maxClosed: false };
  }
  const r = reference && rangePattern.exec(value);
  if (r) {
    const min = numericValue(r[1]), max = numericValue(r[2]);
    if (min !== null && max !== null && min <= max) return { min, max, minClosed: true, maxClosed: true };
  }
  return null;
}

export function numericValue(raw) {
  const value = normalizedResult(raw);
  if (!numberPattern.test(value)) return null;
  const n = Number(value), digits = value.split(/e/i)[0].replace(/[-+.]/g, "").replace(/^0+|0+$/g, "");
  // Do not label rounded or underflowed measurements as a reliable comparison.
  if (!Number.isFinite(n) || Math.abs(n) > Number.MAX_SAFE_INTEGER || digits.length > 15 || (digits && Math.abs(n) < 2.2250738585072014e-308)) return null;
  return n;
}

export function referenceRange(raw) { return interval(raw, true); }

export function referenceDraft(raw = "") {
  const value = normalizedResult(raw);
  const draft = { mode: "range", min: "", max: "", operator: "≤", limit: "", qualitative: "阴性", raw: String(raw) };
  if (!value) return draft;
  const range = rangePattern.exec(value);
  if (range && Number(range[1]) <= Number(range[2])) return { ...draft, min: range[1], max: range[2] };
  const single = comparatorPattern.exec(value);
  if (single) return { ...draft, mode: "single", operator: ({ "<=": "≤", ">=": "≥" })[single[1]] || single[1], limit: single[2] };
  if (["阴性", "阳性", "未检出"].includes(value)) return { ...draft, mode: "qualitative", qualitative: value };
  return { ...draft, mode: "raw" };
}

export function formatReference(draft) {
  if (draft.mode === "range") {
    const min = draft.min.trim(), max = draft.max.trim();
    if (!min && !max) return "";
    if (numericValue(min) === null || numericValue(max) === null) throw new HealthError("请分别填写有效的下限和上限；只有一侧限值时请选择「单侧」");
    if (numericValue(min) > numericValue(max)) throw new HealthError("参考范围下限不能大于上限");
    return `${min}–${max}`;
  }
  if (draft.mode === "single") {
    if (numericValue(draft.limit) === null || !["<", "≤", ">", "≥"].includes(draft.operator)) throw new HealthError("请选择比较方式并填写有效的参考限值");
    return `${draft.operator}${draft.limit.trim()}`;
  }
  if (draft.mode === "qualitative") return text(draft.qualitative, 200, "定性参考", true);
  if (draft.mode === "raw") return text(draft.raw, 200, "参考范围");
  throw new HealthError("请选择参考范围类型");
}

export function compareReference(value, normal) {
  const observed = interval(value), range = interval(normal, true);
  if (!observed || !range) return "unknown";
  if (observed.qualitative || range.qualitative) {
    if (!observed.qualitative || !range.qualitative) return "unknown";
    return observed.qualitative === range.qualitative ? "within" : "outside";
  }
  if (observed.max < range.min || (observed.max === range.min && !(observed.maxClosed && range.minClosed))) return "low";
  if (observed.min > range.max || (observed.min === range.max && !(observed.minClosed && range.maxClosed))) return "high";
  const lowerInside = observed.min > range.min || (observed.min === range.min && (!observed.minClosed || range.minClosed));
  const upperInside = observed.max < range.max || (observed.max === range.max && (!observed.maxClosed || range.maxClosed));
  return lowerInside && upperInside ? "within" : "unknown";
}

export const STATUS_TEXT = { within: "范围内", low: "低于参考", high: "高于参考", outside: "超出参考", unknown: "未判定" };
export const outside = (item) => ["low", "high", "outside"].includes(compareReference(item.value, item.normal));

export function buildIndex(records) {
  const metrics = new Map(), metricGroups = new Map(), counts = { all: records.length, blood: 0, blood_bio: 0, urine: 0, urine_bio: 0 };
  const sorted = [...records].sort((a, b) => b.date.localeCompare(a.date) || b.id.localeCompare(a.id));
  for (const record of sorted) {
    counts[record.type]++;
    record.items.forEach((item, itemIndex) => {
      const key = metricKey(record.type, item), groupKey = JSON.stringify([specimen(record.type), item.name.normalize("NFKC").trim().toLowerCase()]);
      if (!metricGroups.has(groupKey)) metricGroups.set(groupKey, { key: groupKey, name: item.name, specimen: specimen(record.type), series: [], points: [] });
      const group = metricGroups.get(groupKey);
      if (!metrics.has(key)) {
        const metric = { key, groupKey, name: item.name, specimen: specimen(record.type), unit: canonicalUnit(item.unit), points: [] };
        metrics.set(key, metric); group.series.push(metric);
      }
      const point = { ...item, recordId: record.id, itemIndex, type: record.type, date: record.date, status: compareReference(item.value, item.normal) };
      metrics.get(key).points.push(point); group.points.push(point);
    });
  }
  return { sorted, counts, metrics, metricGroups, attention: [...metrics.values()].filter((m) => outside(m.points[0])) };
}

export function monthlyDays(records, year) {
  const months = Array.from({ length: 12 }, () => new Set());
  for (const record of records) if (record.date.startsWith(`${year}-`)) months[Number(record.date.slice(5, 7)) - 1].add(record.date);
  return months.map((days) => days.size);
}

function recordFingerprint(record) {
  return JSON.stringify([record.date, record.type, record.items.map((i) => JSON.stringify([i.name, i.value, i.unit, i.normal])).sort()]);
}

export function mergeArchive(current, incoming, includeProfile = false, makeId = newId, updateMatchingIds = false) {
  const next = copy(current), positions = new Map(current.records.map((record, index) => [record.id, index])), matchedIds = new Set();
  let added = 0, duplicates = 0, updated = 0;
  if (updateMatchingIds) {
    for (const original of incoming.records) {
      const position = positions.get(original.id);
      if (position === undefined) continue;
      const previous = next.records[position];
      if (previous.date !== original.date || previous.type !== original.type) throw new HealthError("相同记录 ID 的日期或类型不一致，不能按修订模式覆盖，请先核对备份");
      matchedIds.add(original.id);
      if (recordFingerprint(previous) === recordFingerprint(original)) duplicates++;
      else { next.records[position] = copy(original); updated++; }
    }
  }
  const fingerprints = new Set(next.records.map(recordFingerprint)), ids = new Set(next.records.map((r) => r.id));
  for (const original of incoming.records) {
    if (matchedIds.has(original.id)) continue;
    const fingerprint = recordFingerprint(original);
    if (fingerprints.has(fingerprint)) { duplicates++; continue; }
    const record = copy(original);
    if (ids.has(record.id)) record.id = makeId();
    ids.add(record.id); fingerprints.add(fingerprint); next.records.push(record); added++;
  }
  if (includeProfile) next.profile = copy(incoming.profile);
  next.lib = updateMatchingIds ? copy(incoming.lib) : { ...incoming.lib, ...next.lib };
  next.aliasMap = updateMatchingIds ? copy(incoming.aliasMap) : { ...incoming.aliasMap, ...next.aliasMap };
  if (!next.healthSummary.text && incoming.healthSummary.text) next.healthSummary = copy(incoming.healthSummary);
  if (!next.settings.model) next.settings.model = incoming.settings.model;
  return { archive: normalizeArchive(next, makeId), added, duplicates, updated };
}

export function assertNoNewDuplicateMetrics(before, after) {
  const count = (record) => {
    const counts = new Map();
    for (const item of record.items) { const key = metricKey(record.type, item); counts.set(key, (counts.get(key) || 0) + 1); }
    return counts;
  };
  const previous = count({ ...before, type: after.type });
  for (const [key, size] of count(after)) if (size > 1 && size > (previous.get(key) || 0)) throw new HealthError("修改后同一报告会新增重复指标，请核对名称和单位。原有重复项可保留并逐项修订");
}

export function renameMetric(archive, key, target) {
  const name = text(target, 120, "标准名称", true);
  let changed = 0;
  for (const record of archive.records) {
    for (const item of record.items) {
      if (metricKey(record.type, item) !== key || item.name === name) continue;
      const targetKey = metricKey(record.type, { ...item, name });
      if (record.items.some((other) => other !== item && metricKey(record.type, other) === targetKey)) throw new HealthError("同一报告中已存在目标指标，请先逐项核对，未进行合并");
      item.name = name; changed++;
    }
  }
  archive.aliasMap = { ...archive.aliasMap, [key]: name };
  return changed;
}

export function exportArchive(archive) {
  return normalizeArchive(archive);
}
