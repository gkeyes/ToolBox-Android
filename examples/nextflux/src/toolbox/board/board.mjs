// 今日看板: today's unread articles, grouped by the configured AI into a few
// ranked topics. Each topic names the articles it is built from, so the board
// can open them. "读成文章" asks the AI for a short written digest of those
// topics that cites its articles as [n].

import { dedupeArticles } from "../dedupe.mjs";

// One request carries every chosen article. The whole prompt (instructions,
// titles, excerpts and numbering) stays within this many characters, so a
// busy day still fits a common context window.
export const PROMPT_BUDGET = 24000;
// Per article: "[n] ", the line break before the excerpt and the blank line after.
const ARTICLE_OVERHEAD = 10;
export const MAX_ARTICLES = 150;
const EXCERPT_MAX = 280;
const TITLE_MAX = 120;
export const MAX_TOPICS = 7;
// Titles that name the rest of the board.
const REST_TITLE = /^(其[他它]|其余)(新闻|消息|资讯)?$|^others?$/i;

export function startOfDay(now = new Date()) {
  const day = new Date(now);
  day.setHours(0, 0, 0, 0);
  return day;
}

const time = (value) => {
  const parsed = Date.parse(value || "");
  return Number.isFinite(parsed) ? parsed : 0;
};

// When an article reached the reader: the later of published and fetched,
// ignoring a timestamp in the future (a feed with a wrong clock or time zone).
export function arrivedAt(row, now = Date.now()) {
  const times = [time(row?.published_at), time(row?.created_at)].filter((at) => at && at <= now);
  return times.length ? Math.max(...times) : 0;
}

// Unread articles that arrived since local midnight, newest first, from feeds
// the list would show. With `hideDuplicates`, later copies of a title or
// original URL are left out as in the article lists (the newest copy stays).
export function todaysUnread(rows, { now = new Date(), isVisibleFeed = () => true, hideDuplicates = false, duplicateMatch = "either" } = {}) {
  const since = startOfDay(now).getTime();
  const at = now.getTime();
  const today = rows
    .filter((row) => row?.status === "unread" && isVisibleFeed(row.feedId) && arrivedAt(row, at) >= since)
    .sort((a, b) => arrivedAt(b, at) - arrivedAt(a, at) || b.id - a.id);
  return dedupeArticles(today, { enabled: hideDuplicates, match: duplicateMatch });
}

// Preview text is already plain text; only tidy its whitespace.
const tidy = (value, max) => String(value || "").replace(/\s+/g, " ").trim().slice(0, max);

// Picks the newest articles that fit the prompt budget. Every chosen article
// keeps its title; the excerpts share what is left. Returns the articles that
// go into the prompt (oldest are dropped first) and how many were left out.
export function fitArticles(articles, budget = PROMPT_BUDGET - PROMPT_OVERHEAD) {
  const chosen = [];
  let used = 0;
  for (const article of articles.slice(0, MAX_ARTICLES)) {
    const head = `${tidy(article.feedTitle, 40)} · ${tidy(article.title, TITLE_MAX) || "（无标题）"}`;
    // Keep room for at least a short excerpt per article.
    if (used + head.length + ARTICLE_OVERHEAD + 40 > budget) break;
    used += head.length + ARTICLE_OVERHEAD;
    chosen.push({ ...article, head });
  }
  const room = Math.max(0, budget - used);
  const each = chosen.length ? Math.min(EXCERPT_MAX, Math.floor(room / chosen.length)) : 0;
  return {
    articles: chosen.map(({ head, ...article }) => ({ ...article, head, excerpt: tidy(article.text, each) })),
    skipped: articles.length - chosen.length,
  };
}

const articleList = (articles) => articles
  .map((article, index) => `[${index + 1}] ${article.head}${article.excerpt ? `\n${article.excerpt}` : ""}`)
  .join("\n\n");

const TOPICS_PROMPT = [
  "你是一位新闻编辑，为读者整理“今日看板”。",
  "下面是读者今天还没读的文章，每篇前面有编号 [n]。",
  "把讲同一件事的文章归成一个主题，选出 3 到 7 个最值得看的主题，按重要程度从高到低排列；零散、不重要的文章可以不归入任何主题。",
  "只输出一个 JSON 对象，不要输出其他文字，格式：",
  "{\"topics\":[{\"title\":\"主题名，不超过 12 个字\",\"gist\":\"一句话重点，不超过 24 个字\",\"summary\":\"两三句话的摘要\",\"articles\":[1,3]}]}",
  "articles 填这个主题包含的文章编号。全部用简体中文，只根据给出的内容写，不要编造。",
  "文字里需要引号时用「」，不要用英文双引号。",
].join("\n");

// The instructions and the user message's opening line, outside the articles.
const PROMPT_OVERHEAD = Math.max(TOPICS_PROMPT.length, 200) + 40;

// Asked again after a reply ran into the AI's length limit before one topic
// was whole: a shorter answer fits.
const BRIEF = "上一次的回答太长被截断了。这次回答要短：最多 5 个主题，summary 只写一句话，不超过 40 个字，直接输出 JSON。";
export function topicsMessages(articles, { brief = false } = {}) {
  return [
    { role: "system", content: brief ? `${TOPICS_PROMPT}\n${BRIEF}` : TOPICS_PROMPT },
    { role: "user", content: `今天的未读文章共 ${articles.length} 篇：\n\n${articleList(articles)}` },
  ];
}

const clean = (value, max) => String(value ?? "").replace(/\*\*/g, "").replace(/\s+/g, " ").trim().slice(0, max);

// JSON as models write it: also accepts a trailing comma before } or ].
// Drops trailing commas before } or ], leaving text inside strings alone.
const CLOSER = /\s*[}\]]/y;
function dropTrailingCommas(text) {
  let out = "";
  let inString = false;
  for (let i = 0; i < text.length; i += 1) {
    const char = text[i];
    if (inString) {
      out += char;
      if (char === "\\") { out += text[i + 1] ?? ""; i += 1; } else if (char === '"') inString = false;
    } else if (char === '"') { inString = true; out += char; }
    else if (char === ",") {
      CLOSER.lastIndex = i + 1;
      if (!CLOSER.test(text)) out += char;
    } else out += char;
  }
  return out;
}
function looseJson(text) {
  try { return JSON.parse(text); } catch { /* try the repaired text */ }
  if (!/,\s*[}\]]/.test(text)) return null;
  try { return JSON.parse(dropTrailingCommas(text)); } catch { return null; }
}

// An article reference as the AI wrote it: 3, "3", "[3]", "2.0", "第3篇",
// full-width "３", a list "1,2" / "1、2" / "1 2" / "1,2-3", or a range "1-3"
// (also reversed "3-1", "1〜3", "1−3"). A range running a little past the
// last article is trimmed to it; one longer than the board has articles, by
// more than one ("1-50" of 12), or than MAX_RANGE ("1-99999") is junk and
// names no article, nor does anything else. Numbers a range names are also
// added to `ranged`, when given, so a topic can tell how much its ranges
// cover (see catchAll).
const MAX_RANGE = 100;
const ITEM = /^\[?第?(\d+)(?:\.0+)?篇?\]?$/;
const RANGE = /^\[?第?(\d+)篇?\s*[-–—~〜−‐‑‒―至到]\s*第?(\d+)篇?\]?$/;
export function articleNumbers(value, count, ranged = null) {
  if (typeof value === "number") return Number.isInteger(value) ? [value] : [];
  // NFKC turns full-width digits and ～ into their ASCII forms. A space
  // between two numbers separates them; one around a range dash does not.
  const text = String(value ?? "").normalize("NFKC").trim().replace(/([\d篇\]])\s+(?=[[第\d])/g, "$1,");
  const parts = text.split(/\s*[,，、;；]\s*/);
  return parts.flatMap((part) => {
    const range = part.match(RANGE);
    if (range) {
      const [a, b] = [Number(range[1]), Number(range[2])];
      if (Math.abs(a - b) + 1 > (Number.isFinite(count) ? Math.min(MAX_RANGE, count + 1) : MAX_RANGE)) return [];
      const from = Math.max(1, Math.min(a, b));
      const to = Math.min(Math.max(a, b), Number.isFinite(count) ? count : Infinity);
      const numbers = to < from ? [] : Array.from({ length: to - from + 1 }, (_, index) => from + index);
      ranged?.push(...numbers);
      return numbers;
    }
    const item = part.match(ITEM);
    return item ? [Number(item[1])] : [];
  });
}

// When the reply is not valid JSON at all (an unescaped " inside a summary,
// a reply cut off mid-way), read each topic's fields on their own. A string
// value ends at a quote followed by the next key or the object's end, so a
// stray quote inside it stays part of the text; a topic whose article list
// never closed is left out.
const unescape = (value) => value.replace(/\\n/g, " ").replace(/\\(["\\/])/g, "$1");
export function salvageTopics(raw, count = Infinity) {
  raw = String(raw || "");
  // Each topic is read from its own object, so a model that writes
  // "articles" before "title" keeps its own list; without objects, from
  // each "title" to the next. An object starts after [ or , ; a piece with
  // no title (a {"…"} written inside a summary) belongs to the one before.
  const objects = [...raw.matchAll(/[[,]\s*(\{\s*"(?:title|gist|summary|articles)"\s*:)/g)].map((match) => match.index + match[0].indexOf("{"));
  const titles = [...raw.matchAll(/"title"\s*:/g)].map((match) => match.index);
  const bounds = objects.length >= titles.length ? objects : titles;
  const parts = [];
  bounds.forEach((start, index) => {
    const part = raw.slice(start, bounds[index + 1] ?? raw.length);
    if (parts.length && !/"title"\s*:/.test(part)) parts[parts.length - 1] += part;
    else parts.push(part);
  });
  return parts.map((part, index) => {
    const field = (key) => {
      const open = part.match(new RegExp(`"${key}"\\s*:\\s*"`));
      if (!open) return "";
      const rest = part.slice(open.index + open[0].length);
      const end = rest.search(/"\s*(?:,\s*"[A-Za-z_]+"\s*:|\})/);
      return end >= 0 ? unescape(rest.slice(0, end)) : "";
    };
    // The list may hold bracketed items ("[3]" or [3]), so an inner ] does not end it.
    const numbers = part.match(/"articles"\s*:\s*\[((?:"[^"]*"|\[(?:"[^"]*"|[^\]"])*\]|[^\]"[])*)\]/);
    const ranged = [];
    const topic = { title: field("title"), gist: field("gist"), summary: field("summary"), articles: numbers ? (numbers[1].match(/"[^"]*"|[^,\s"\[\]]+/g) || []).flatMap((item) => articleNumbers(item.replace(/^"|"$/g, ""), count, ranged)) : [], ranged };
    // The last topic of a reply cut off mid-way, before its summary arrived
    // or its object closed, is not whole.
    const last = index === parts.length - 1;
    return last && !topic.summary && !/\}/.test(part) ? null : topic;
  }).filter((topic) => topic?.title && topic.articles.length);
}

// A topic whose ranges help it reach (nearly) every article of a board over
// 3 — "1-12", "1-11", "1-10,11,12" of 12 — lumps the board into one topic.
// When another topic claims some of them, it gives up only those; the rest
// stay with it, so no article drops off the board. A reply whose only topic
// is the whole board keeps it whole.
const coverage = (numbers, ranged, count) => ranged.length && count > 3 ? new Set(numbers.filter((n) => Number.isInteger(n) && n >= 1 && n <= count)).size : 0;
const catchAll = (covered, count) => covered > 0 && covered >= count - Math.floor(count / 10);

// Reads the AI's topics. Article numbers must name a real article; each
// article belongs to at most one topic (the higher ranked); a topic left
// without articles is dropped. Throws when nothing usable came back.
export function parseTopics(text, count) {
  return readTopics(text, count).topics;
}

// The same, with `whole`: false when the topics were salvaged from a reply
// that was not valid JSON, so a reply that ended at the length limit after
// its JSON closed is not cut.
export function readTopics(text, count) {
  const raw = String(text || "");
  // The first JSON object with topics, or array of topic objects, in the
  // reply; text around it (a fence, a note, a remark with its own brackets)
  // is ignored.
  const usable = (value) => Array.isArray(value?.topics) || (Array.isArray(value) && value.some((item) => item && typeof item === "object" && !Array.isArray(item)));
  let data = null;
  search: for (const { index: start } of raw.matchAll(/[[{]/g)) {
    const close = raw[start] === "{" ? "}" : "]";
    for (let end = raw.lastIndexOf(close); end > start; end = raw.lastIndexOf(close, end - 1)) {
      const value = looseJson(raw.slice(start, end + 1));
      if (usable(value)) { data = value; break search; }
    }
  }
  const salvaged = data ? null : salvageTopics(raw, count);
  const list = Array.isArray(data?.topics) ? data.topics : Array.isArray(data) ? data : salvaged?.length ? salvaged : null;
  if (!list) throw new Error("AI 返回的内容无法识别，请重新生成。");
  // Each topic's numbers, and the numbers its ranges named.
  const read = list.map((item) => {
    const listed = Array.isArray(item?.articles) ? item.articles : [];
    // Salvaged topics come with their numbers already read.
    const ranged = salvaged ? item.ranged : [];
    const numbers = listed.flatMap((n) => articleNumbers(n, count, salvaged ? null : ranged));
    const covered = coverage(numbers, ranged, count);
    return { item, numbers, lumps: catchAll(covered, count) };
  });
  const real = (n) => Number.isInteger(n) && n >= 1 && n <= count;
  // A catch-all gives way only to a topic that groups real articles itself,
  // and only the articles that topic claims: "1-12" beside [4,5] keeps the
  // other ten, and "1-11" beside [12] splits the board in two. A rival that
  // the MAX_TOPICS cap then leaves out claims nothing, so its articles stay
  // with the catch-all; the board is picked again without it.
  const pick = (rivals) => {
    const claimed = new Set(rivals.flatMap((rival) => rival.numbers));
    const taken = new Set();
    const picked = [];
    for (const entry of read) {
      const { item, lumps, numbers: all } = entry;
      const title = clean(item?.title, 40);
      const numbers = (lumps ? all.filter((n) => !claimed.has(n)) : all).filter((n) => real(n) && !taken.has(n));
      if (!title || !numbers.length) continue;
      const unique = [...new Set(numbers)];
      // A catch-all left with under half of what it named is the rest of the
      // board, not all of it: its title and summary described every article.
      // A topic the AI itself calls 其他, 其它, 其他新闻 and the like is the
      // rest too.
      // All of the rest shares one place under the MAX_TOPICS cap and is
      // never cut; the other topics stop where the cap is reached, except a
      // catch-all, whose articles then become the rest.
      const shared = picked.some((pick) => pick.rest) ? 1 : 0;
      const full = picked.filter((pick) => !pick.rest).length + shared >= MAX_TOPICS;
      const leftover = lumps && (full || unique.length * 2 < new Set(all.filter(real)).size);
      const rest = leftover || REST_TITLE.test(title);
      if (!rest && full) continue;
      unique.forEach((n) => taken.add(n));
      picked.push({ entry, rest, topic: leftover
        ? { title: "其他", gist: "", summary: "", articles: unique }
        : { title, gist: clean(item?.gist, 80), summary: clean(item?.summary, 400), articles: unique } });
    }
    return picked;
  };
  const choose = () => {
    let rivals = read.filter(({ item, numbers, lumps }) => !lumps && clean(item?.title, 40) && numbers.some(real));
    let picked = pick(rivals);
    for (;;) {
      const kept = new Set(picked.map(({ entry }) => entry));
      const shown = rivals.filter((rival) => kept.has(rival));
      if (shown.length === rivals.length) return picked;
      rivals = shown;
      picked = pick(rivals);
    }
  };
  const picked = choose();
  // The rest of the board is the least important topic, whatever rank the AI
  // gave its whole-board range, and there is only one of it.
  // A lone rest keeps what the AI wrote for it; several become one 其他.
  // Either way its articles are in article order.
  const topics = picked.filter(({ rest }) => !rest).map(({ topic }) => topic);
  const rest = picked.filter(({ rest }) => rest).map(({ topic }) => topic);
  // A rest the AI put after the cap was reached takes the last place. The
  // smaller of the last topics joins it, so a big story keeps its title, and
  // the rest keeps what the AI wrote for it.
  const absorbed = [];
  for (let excess = rest.length ? topics.length - MAX_TOPICS + 1 : 0; excess > 0; excess--) {
    let smallest = MAX_TOPICS - 2;
    for (let i = smallest + 1; i < topics.length; i++) if (topics[i].articles.length <= topics[smallest].articles.length) smallest = i;
    absorbed.push(...topics.splice(smallest, 1));
  }
  const articles = [...rest, ...absorbed].flatMap((topic) => topic.articles).sort((a, b) => a - b);
  if (rest.length === 1) topics.push({ ...rest[0], articles });
  if (rest.length > 1) topics.push({ title: "其他", gist: "", summary: "", articles });
  if (!topics.length) throw new Error("AI 没有归出主题，请重新生成。");
  return { topics, whole: Boolean(data) };
}

const DIGEST_PROMPT = [
  "你是一位新闻编辑，把读者今天的新闻写成一篇简短的文章。",
  "下面先给出已经整理好的主题，再给出文章，文章前面有编号 [n]。",
  "按主题的顺序写，每个主题一段，第一行“## 主题名”，下一行用两到四句简体中文写清楚发生了什么，句末标出依据的文章编号，如 [2][5]。",
  "只根据给出的内容写，不要编造；不要写开场白或结尾。",
].join("\n");

export function digestMessages(topics, articles) {
  const outline = topics.map((topic, index) => `${index + 1}. ${topic.title}：${topic.gist}（文章 ${topic.articles.map((n) => `[${n}]`).join("")}）`).join("\n");
  return [
    { role: "system", content: DIGEST_PROMPT },
    { role: "user", content: `主题：\n${outline}\n\n文章：\n\n${articleList(articles)}` },
  ];
}

// Splits the (possibly still streaming) digest into sections. Text between
// citations stays text; a citation is kept only if it names a real article.
// Parts are {text} with non-empty text, or {cite}.
export function parseDigest(text, count) {
  const sections = [];
  let current = null;
  const push = (value) => {
    const part = value.replace(/\*\*/g, "");
    if (part) current.parts.push({ text: part });
  };
  for (const raw of String(text || "").split("\n")) {
    const line = raw.trim();
    if (!line) continue;
    const heading = line.match(/^#{1,6}\s*(.+)$/);
    if (heading) {
      current = { title: heading[1].replace(/\*\*/g, "").trim(), parts: [] };
      sections.push(current);
      continue;
    }
    if (!current) { current = { title: "", parts: [] }; sections.push(current); }
    if (current.parts.length) push(" ");
    let last = 0;
    for (const match of line.matchAll(/\[(\d+)\]/g)) {
      const number = Number(match[1]);
      if (match.index > last) push(line.slice(last, match.index));
      if (number >= 1 && number <= count) current.parts.push({ cite: number });
      last = match.index + match[0].length;
    }
    if (last < line.length) push(line.slice(last));
  }
  return sections;
}
