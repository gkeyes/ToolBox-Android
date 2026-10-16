// Today's briefing: the unread articles that arrived today, condensed by the
// configured AI into a few themed sections. Each section cites its articles
// as [n], which the sheet turns into links back to them.

// Room for excerpts in one request. Every article keeps its title; the
// excerpts share this budget so a busy day still fits a common context.
const EXCERPT_BUDGET = 24000;
const EXCERPT_MAX = 280;
const EXCERPT_MIN = 60;

export function startOfDay(now = new Date()) {
  const day = new Date(now);
  day.setHours(0, 0, 0, 0);
  return day;
}

const time = (value) => {
  const parsed = Date.parse(value || "");
  return Number.isFinite(parsed) ? parsed : 0;
};

// Unread articles published or fetched since local midnight, newest first,
// from feeds the list would show.
export function todaysUnread(rows, { now = new Date(), isVisibleFeed = () => true } = {}) {
  const since = startOfDay(now).getTime();
  return rows
    .filter((row) => row?.status === "unread" && isVisibleFeed(row.feedId) &&
      Math.max(time(row.published_at), time(row.created_at)) >= since)
    .sort((a, b) => time(b.published_at) - time(a.published_at) || b.id - a.id);
}

export function plainText(html) {
  return String(html || "")
    .replace(/<(script|style|iframe|svg|math|template)\b[\s\S]*?<\/\1>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;|&#160;/gi, " ")
    .replace(/&amp;/gi, "&").replace(/&lt;/gi, "<").replace(/&gt;/gi, ">").replace(/&quot;/gi, "\"").replace(/&#39;/gi, "'")
    .replace(/&[a-z0-9#]+;/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export function excerptLength(count) {
  if (count <= 0) return EXCERPT_MAX;
  return Math.max(EXCERPT_MIN, Math.min(EXCERPT_MAX, Math.floor(EXCERPT_BUDGET / count)));
}

const SYSTEM_PROMPT = [
  "你是一位新闻编辑，为读者写“今日简报”。",
  "下面是读者今天还没读的文章，每篇前面有编号 [n]。",
  "把相关的文章归到同一个主题，写 3 到 7 段，按重要性排序；不值得一提的可以不写。",
  "每段格式：第一行“## 主题”，下一行用一到三句简体中文写重点，句末标出引用的文章编号，如 [2][5]。",
  "只根据给出的内容写，不要编造；不要写开场白或结尾。",
].join("\n");

// `articles` are {feedTitle, title, text}; the [n] numbers start at 1.
export function briefingMessages(articles) {
  const length = excerptLength(articles.length);
  const list = articles.map((article, index) => {
    const excerpt = plainText(article.text).slice(0, length);
    return `[${index + 1}] ${article.feedTitle ? `${article.feedTitle} · ` : ""}${article.title || "（无标题）"}${excerpt ? `\n${excerpt}` : ""}`;
  }).join("\n\n");
  return [
    { role: "system", content: SYSTEM_PROMPT },
    { role: "user", content: `今天的未读文章共 ${articles.length} 篇：\n\n${list}` },
  ];
}

// Splits the (possibly still streaming) briefing into sections. Text between
// citations stays text; a citation is kept only if it names a real article.
export function parseBriefing(text, count) {
  const sections = [];
  let current = null;
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
    if (current.parts.length) current.parts.push({ text: " " });
    let last = 0;
    for (const match of line.matchAll(/\[(\d+)\]/g)) {
      const number = Number(match[1]);
      if (match.index > last) current.parts.push({ text: line.slice(last, match.index) });
      if (number >= 1 && number <= count) current.parts.push({ cite: number });
      last = match.index + match[0].length;
    }
    if (last < line.length) current.parts.push({ text: line.slice(last) });
  }
  for (const section of sections) {
    section.parts = section.parts.map((part) => part.text ? { text: part.text.replace(/\*\*/g, "") } : part);
  }
  return sections;
}
