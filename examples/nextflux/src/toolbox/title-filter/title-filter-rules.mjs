/** Miniflux >= 2.2.10 EntryTitle rules. Keyword UI never accepts executable regex.
 * https://miniflux.app/docs/rules.html#entry-filtering-rules
 * The named RE2 group identifies only this editor's line, without side metadata.
 */
export const FILTER_FIELD = "block_filter_entry_rules";
export const RULE_PREFIX = "EntryTitle=(?i)(?P<nextflux_title_keywords>";
const MARKER = "(?P<nextflux_title_keywords>";
const META = new Set('\\.^$*+?()[]{}|');

export function filterError(code, message) {
  return Object.assign(new Error(message), { code });
}

export function keywordsFromText(text) {
  if (typeof text !== "string") throw filterError("INVALID_INPUT", "请输入关键词。");
  if (text.length > 8192) throw filterError("INVALID_INPUT", "关键词过长，请分批精简后保存。");
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(text)) {
    throw filterError("INVALID_INPUT", "关键词包含不可见控制字符，请删除后重试。");
  }
  // Empty alternatives would match every title: discard them before building RE2.
  return [...new Set(text.split(/[|｜\r\n]+/u).map(word => word.trim()).filter(Boolean))];
}

export function createTitleRule(words) {
  if (!words.length) return "";
  const escape = word => Array.from(word, char => META.has(char) ? `\\${char}` : char).join("");
  return RULE_PREFIX + words.map(escape).join("|") + ")";
}

function decodeManagedRule(line) {
  if (!line.startsWith(RULE_PREFIX) || !line.endsWith(")")) return null;
  const expression = line.slice(RULE_PREFIX.length, -1);
  const words = []; let word = "";
  for (let i = 0; i < expression.length; i++) {
    const char = expression[i];
    if (char === "\\") {
      const next = expression[++i];
      if (!META.has(next)) return null;
      word += next;
    } else if (char === "|") {
      if (!word) return null;
      words.push(word); word = "";
    } else {
      if (META.has(char)) return null;
      word += char;
    }
  }
  if (!word) return null;
  words.push(word);
  // Do not reinterpret hand-edited regex or an expression this UI cannot round-trip.
  try {
    const normalized = keywordsFromText(words.join("|"));
    if (createTitleRule(normalized) !== line) return null;
    return normalized;
  } catch { return null; }
}

export function inspectTitleRules(raw) {
  if (typeof raw !== "string") throw filterError("UNSUPPORTED", "服务器尚不支持仅标题过滤，请将 Miniflux 更新至 2.2.10 或更新版本。原有规则未改动。");
  const lines = raw.split("\n");
  const marked = lines.map((line, index) => ({ line, index })).filter(item => item.line.includes(MARKER));
  if (marked.length > 1) throw filterError("CUSTOM_RULE", "检测到多条已修改的标题关键词规则，请先在 Miniflux 中整理；不会覆盖现有规则。");
  if (!marked.length) return { raw, words: [], line: null, index: -1, otherCount: lines.filter(line => line.trim()).length };
  const entry = marked[0];
  const words = decodeManagedRule(entry.line.trim());
  if (!words) throw filterError("CUSTOM_RULE", "这条关键词规则已被改成复杂正则，无法安全转换回关键词；请在 Miniflux 中编辑，原规则会保留。");
  return { raw, words, line: entry.line.trim(), index: entry.index, otherCount: lines.filter((line, index) => line.trim() && index !== entry.index).length };
}

export function inspectFeed(feed, expectedId) {
  if (!feed || Number(feed.id) !== Number(expectedId)) throw filterError("INVALID_RESPONSE", "服务器返回的订阅不匹配，请重新打开。");
  return inspectTitleRules(feed[FILTER_FIELD]);
}

// Remove a whole record, including the preceding CRLF at EOF. A dangling CR
// must not become part of the previous independently authored regex.
function removeRuleLine(raw, index) {
  let start = 0;
  for (let i = 0; i < index; i++) start = raw.indexOf("\n", start) + 1;
  const next = raw.indexOf("\n", start);
  if (next >= 0) return raw.slice(0, start) + raw.slice(next + 1);
  let end = start;
  if (end > 0 && raw[end - 1] === "\n") {
    end--;
    if (end > 0 && raw[end - 1] === "\r") end--;
  }
  return raw.slice(0, end);
}

export function replaceTitleKeywords(raw, text) {
  const snapshot = inspectTitleRules(raw);
  const next = createTitleRule(keywordsFromText(text));
  if (next === snapshot.line || (!next && snapshot.line === null)) return raw;
  if (snapshot.index >= 0) {
    const lines = raw.split("\n");
    if (next) lines[snapshot.index] = next + (lines[snapshot.index].endsWith("\r") ? "\r" : "");
    else return removeRuleLine(raw, snapshot.index);
    return lines.join("\n");
  }
  if (!next) return raw;
  const separator = raw.includes("\r\n") ? "\r\n" : "\n";
  return raw + (raw.length && !raw.endsWith("\n") ? separator : "") + next;
}

/** Re-read before saving and update ONLY this feed's block-filter field.
 * Independent edits to other rule lines are preserved; changes to our line conflict.
 * Miniflux has no conditional write/ETag for this endpoint: this is best-effort,
 * not a claim of server-side atomic compare-and-swap across concurrent clients.
 */
export async function saveTitleKeywords({ feedId, text, baselineLine, api, check = () => true }) {
  const ensureCurrent = () => { if (!check()) throw filterError("CANCELLED", "页面或账号已改变，操作已停止。"); };
  const requested = createTitleRule(keywordsFromText(text));
  ensureCurrent();
  const latest = await api.read();
  ensureCurrent();
  const current = inspectFeed(latest, feedId);
  if (current.line !== baselineLine && current.line !== (requested || null)) {
    throw filterError("CONFLICT", "关键词已在其他页面修改。输入仍保留，请重新载入最新规则后再编辑。");
  }
  const next = replaceTitleKeywords(current.raw, text);
  if (next === current.raw) return { feed: latest, words: inspectTitleRules(next).words, changed: false };
  ensureCurrent();
  const saved = await api.write({ [FILTER_FIELD]: next });
  ensureCurrent();
  if (!saved || Number(saved.id) !== Number(feedId) || saved[FILTER_FIELD] !== next) {
    throw filterError("UNCONFIRMED", "服务器未确认保存结果，请重新载入核对；不会把未确认的规则显示为已保存。");
  }
  return { feed: saved, words: inspectTitleRules(next).words, changed: true };
}


const SERVER_RULE_FIELDS = [
  ["blocklist_rules", "原屏蔽规则"],
  ["keeplist_rules", "原保留规则"],
  [FILTER_FIELD, "其他条目屏蔽规则"],
  ["keep_filter_entry_rules", "条目保留规则"],
];

/** Read-only server text. Never reinterpret arbitrary regex as literal keywords.
 * Only the exact managed line is already represented by the editable input.
 */
export function existingServerRules(feed) {
  if (!feed || typeof feed !== "object") return [];
  const result = [];
  for (const [field, label] of SERVER_RULE_FIELDS) {
    let raw = feed[field];
    if (typeof raw !== "string" || !raw.trim()) continue;
    if (field === FILTER_FIELD) {
      try {
        const managed = inspectTitleRules(raw);
        if (managed.index >= 0) raw = removeRuleLine(raw, managed.index);
      } catch { /* Complex or ambiguous rules stay visible, verbatim and read-only. */ }
    }
    if (raw.trim()) result.push({ field, label, raw });
  }
  return result;
}

export function hasFeedRules(feed) {
  return SERVER_RULE_FIELDS.some(([field]) => typeof feed?.[field] === "string" && Boolean(feed[field].trim()));
}
