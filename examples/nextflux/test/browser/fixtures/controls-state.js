import { atom } from "nanostores";

// Fixture data only; no account, database, token, server or native bridge.
export const activeArticle = atom({ id: 42, feedId: 1, title: "Fixture article", url: "https://example.invalid/article", status: "read", starred: 1 });
export const filteredArticles = atom([{ id: 41 }, activeArticle.get(), { id: 43 }]);
export const fullTextLoadingId = atom(null);
export const hasIntegrations = atom(false);
export const settingsState = atom({ aiApiKey: "fixture", floatingSidebar: false });
export const settingsModalOpen = atom(false);
export const currentThemeMode = atom("light");
export const themeState = atom({ lightTheme: "default" });
export const authState = atom({});
export const feeds = atom([]);
export const SERVER_URL = "https://miniflux.example.invalid";
export const calls = [];
export const handleMarkStatus = (article) => calls.push(["status", article.id]);
export const handleMarkRead = (article) => calls.push(["read", article.id]);
export const handleToggleStar = (article) => calls.push(["star", article.id]);
export const handleToggleContent = (article) => calls.push(["content", article.id]);
export const shareText = async (text) => calls.push(["share", text]);
// The UI owns cancellation and stale-run protection. Keep callbacks available
// after abort so tests can deliver a late provider event without masking a bug.
const summaryRequests = [];
let holdSummaries = false;
export const summaryBoundary = {
  hold: (value) => { holdSummaries = value; },
  requests: () => summaryRequests.map(({ articleId, aborted }) => ({ articleId, aborted })),
  chunk: (index, text) => summaryRequests[index].callbacks.onChunk(text),
  done: (index) => summaryRequests[index].callbacks.onDone(),
  fail: (index, message) => summaryRequests[index].callbacks.onError(new Error(message)),
};
export const summarizeArticleStream = (article, callbacks) => {
  const request = { articleId: article.id, callbacks, aborted: false };
  summaryRequests.push(request);
  callbacks.signal?.addEventListener("abort", () => {
    request.aborted = true;
    callbacks.onError(Object.assign(new Error("Synthetic cancelled summary"), { code: "CANCELLED" }));
  }, { once: true });
  if (!holdSummaries) {
    callbacks.onChunk("Fixture summary.");
    callbacks.onDone();
  }
};
export default {
  saveToThirdParty: async (id) => calls.push(["save", id]),
  // No server here: the full-text adapter fails fast unless a test holds the read.
  getFeed: () => globalThis.holdFullTextRead ? new Promise(() => {}) : Promise.reject(new Error("Fixture has no server")),
};
