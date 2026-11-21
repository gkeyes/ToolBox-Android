import { FILTER_FIELD, RULE_PREFIX, filterError, inspectTitleRules } from "./title-filter-rules.mjs";

const otherRuleFields = [
  ["blocklist_rules", "旧式屏蔽规则"],
  ["keeplist_rules", "旧式保留规则"],
  ["keep_filter_entry_rules", "条目保留规则"],
];
const otherRuleTypes = new Map([
  ["EntryContent", "正文屏蔽规则"],
  ["EntryURL", "链接屏蔽规则"],
  ["EntryCommentsURL", "评论链接屏蔽规则"],
  ["EntryAuthor", "作者屏蔽规则"],
  ["EntryTag", "标签屏蔽规则"],
  ["EntryDate", "日期屏蔽规则"],
]);
const maxPatternLength = 20_000; // The editor allows 8192 characters before escaping.
const maxProgramSize = 50_000;

// Miniflux parses rules with Go's strings.TrimSpace, which includes NEL but
// preserves U+FEFF as a literal. JavaScript trim() behaves differently.
function trimRule(value) {
  return value.replace(/^[\t\n\v\f\r \x85\xa0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+|[\t\n\v\f\r \x85\xa0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$/gu, "");
}

function feedRuleSnapshot(feed, feedId) {
  if (!Number.isSafeInteger(feedId) || feedId < 1 || feed?.id !== feedId) {
    throw filterError("INVALID_RESPONSE", "服务器返回的订阅不匹配，请重新打开。");
  }
  if (typeof feed[FILTER_FIELD] !== "string") {
    throw filterError("UNSUPPORTED", "服务器尚不支持条目标题规则，请将 Miniflux 更新至 2.2.10 或更新版本。");
  }
  return JSON.stringify([feed[FILTER_FIELD], ...otherRuleFields.map(([field]) => feed[field] ?? "")]);
}

async function compileRules(feed) {
  const skipped = new Set();
  for (const [field, label] of otherRuleFields) {
    if (typeof feed[field] === "string" && trimRule(feed[field])) skipped.add(label);
  }

  let managedLine;
  try { managedLine = inspectTitleRules(feed[FILTER_FIELD]).line; }
  catch { /* A manually edited marker can still be scanned as a server title rule. */ }

  let engine;
  const tests = [];
  const lines = feed[FILTER_FIELD].split("\n");
  for (const [index, rawLine] of lines.entries()) {
    const line = trimRule(rawLine);
    if (!line) continue;
    const equals = line.indexOf("=");
    const type = equals < 0 ? "" : trimRule(line.slice(0, equals));
    if (type !== "EntryTitle") {
      skipped.add(otherRuleTypes.get(type) ?? "无法识别的条目屏蔽规则");
      continue;
    }
    const pattern = trimRule(line.slice(equals + 1));
    const label = `标题规则第 ${index + 1} 行`;
    if (!pattern || pattern.length > maxPatternLength) {
      skipped.add(`${label}（规则为空或过长）`);
      continue;
    }
    // Loading stays off the reader and app start paths. Do not enable the
    // engine's lookbehind extension or Unicode properties with version-specific
    // tables and aliases that can differ from a deployed Go regexp engine.
    engine ??= (await import("re2js")).RE2JS;
    try {
      const re = engine.compile(pattern, engine.DISABLE_UNICODE_GROUPS);
      if (re.programSize() > maxProgramSize) {
        skipped.add(`${label}（规则过于复杂）`);
        continue;
      }
      tests.push({ re, label, pattern, managed: line === managedLine && line.startsWith(RULE_PREFIX) });
    } catch {
      skipped.add(`${label}（正则无效或不支持）`);
    }
  }

  function reasonFor(title) {
    for (const { re, label, pattern, managed } of tests) {
      if (!re.test(title)) continue;
      if (managed) {
        const match = re.matcher(title);
        if (match.find()) return `标题关键词「${match.group(1)}」`;
      }
      return `${label}：${pattern.length > 160 ? `${pattern.slice(0, 160)}…` : pattern}`;
    }
    return null;
  }

  return { reasonFor, hasRules: tests.length > 0, skippedRules: [...skipped] };
}

function enforceContext(check) {
  return async () => {
    if ((await check()) === false) throw filterError("CANCELLED", "页面或账号已改变，操作已停止。");
  };
}

function validateEntry(entry, feedId) {
  if (entry?.feed_id !== feedId || !Number.isSafeInteger(entry.id) || entry.id <= 0 ||
      typeof entry.title !== "string" || typeof entry.starred !== "boolean") {
    throw filterError("INVALID_RESPONSE", "服务器返回的文章不属于当前订阅或数据无效，请重新扫描。");
  }
}

export async function previewUnreadTitleFilter({ feedId, readFeed, fetchPages, check = () => true, onProgress }) {
  const current = enforceContext(check);
  await current();
  const feed = await readFeed();
  await current();
  const signature = feedRuleSnapshot(feed, feedId);
  const rules = await compileRules(feed);
  const matches = [];
  let scanned = 0;
  let skippedStarred = 0;
  let beforeEntryId;
  if (rules.hasRules) {
    await fetchPages({ feedId, check: current, onPage: async entries => {
      await current();
      for (const entry of entries) {
        validateEntry(entry, feedId);
        // A new entry with a greater ID cannot join a descending cursor walk.
        // Persist the first page's fence for the confirmation read as well.
        if (!scanned && entry.id < Number.MAX_SAFE_INTEGER) beforeEntryId = entry.id + 1;
        scanned++;
        if (entry.status !== "unread") continue;
        const reason = rules.reasonFor(entry.title);
        if (!reason) continue;
        if (entry.starred) skippedStarred++;
        else matches.push({ id: entry.id, title: entry.title, reason });
      }
      onProgress?.({ scanned, skippedStarred, matchedCount: matches.length });
    } });
  }
  await current();
  return { feedId, signature, beforeEntryId, scanned, skippedStarred, matches, hasRules: rules.hasRules, skippedRules: rules.skippedRules };
}

export async function applyUnreadTitleFilter({ preview, readFeed, fetchPages, markAsRead, check = () => true, onProgress }) {
  const { feedId, signature, beforeEntryId, matches } = preview;
  const candidateIds = [...new Set(matches.map(entry => entry.id))];
  if (!candidateIds.length) return { markedCount: 0, skippedCount: 0 };

  const checkContext = enforceContext(check);
  await checkContext();
  const markedCount = await markAsRead(candidateIds, async accountCheck => {
    const current = async () => { await accountCheck(); await checkContext(); };
    const readCurrentRules = async () => {
      await current();
      const feed = await readFeed();
      await current();
      if (feedRuleSnapshot(feed, feedId) !== signature) {
        throw filterError("BACKFILL_RULES_CHANGED", "订阅规则已变化，请重新扫描后再确认。");
      }
      return feed;
    };
    const rules = await compileRules(await readCurrentRules());
    const pending = new Set(candidateIds);
    const confirmed = [];
    let scanned = 0;
    let skippedStarred = 0;
    await fetchPages({ feedId, beforeEntryId, check: current, onPage: async entries => {
      await current();
      for (const entry of entries) {
        validateEntry(entry, feedId);
        scanned++;
        if (!pending.delete(entry.id) || entry.status !== "unread" || !rules.reasonFor(entry.title)) continue;
        if (entry.starred) skippedStarred++;
        else confirmed.push(entry.id);
      }
      onProgress?.({ scanned, skippedStarred, matchedCount: confirmed.length });
    } });
    await readCurrentRules();
    return confirmed;
  }, check);
  return { markedCount, skippedCount: candidateIds.length - markedCount };
}
