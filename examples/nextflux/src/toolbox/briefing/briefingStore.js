import { atom } from "nanostores";
import { getCachedArticleMetadata, getFeeds } from "@/db/storage.js";
import { settingsState } from "@/stores/settingsStore.js";
import { streamChat } from "@/api/openai.js";
import { briefingMessages, startOfDay, todaysUnread } from "./briefing.mjs";

// The latest briefing, kept while the app runs so reopening the sheet shows
// it again. status: idle | loading | streaming | done | empty | error.
export const briefing = atom({ status: "idle", text: "", articles: [], error: "", at: null });

let current = null;

// Today's briefing is stale once the day it was written for has passed.
export const isStale = (state) => !state.at || state.at < startOfDay().getTime();

export async function generateBriefing() {
  current?.abort();
  const run = new AbortController();
  current = run;
  const update = (patch) => { if (current === run) briefing.set({ ...briefing.get(), ...patch }); };
  update({ status: "loading", text: "", articles: [], error: "", at: Date.now() });
  try {
    const settings = settingsState.get();
    if (!settings.aiApiKey) throw new Error("请先在 NextFlux 设置中配置 AI API Key。");
    const feeds = new Map((await getFeeds())
      .filter((feed) => settings.showHiddenFeeds || !feed.hide_globally)
      .map((feed) => [feed.id, feed]));
    const rows = todaysUnread(await getCachedArticleMetadata({ filter: "unread" }), { isVisibleFeed: (id) => feeds.has(id) });
    if (run.signal.aborted) return;
    if (!rows.length) { update({ status: "empty" }); return; }
    const articles = rows.map((row) => ({
      id: row.id,
      title: row.titleText || row.title || "",
      feedTitle: feeds.get(row.feedId)?.title || "",
      text: row.previewText || "",
    }));
    update({ status: "streaming", articles: articles.map(({ id, title, feedTitle }) => ({ id, title, feedTitle })) });
    let text = "";
    await streamChat(briefingMessages(articles), {
      signal: run.signal,
      onChunk: (chunk) => { text += chunk; update({ text }); },
    });
    if (!text.trim()) throw new Error("AI 没有返回内容，请重新生成。");
    update({ status: "done" });
  } catch (error) {
    if (run.signal.aborted) return;
    update({ status: "error", error: error?.message || "生成简报失败。" });
  }
}
