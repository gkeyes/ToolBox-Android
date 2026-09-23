import { useEffect } from "react";
import { createRoot } from "react-dom/client";
import { HashRouter, Routes, Route, useNavigate, useParams } from "react-router-dom";
import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import ActionButtons from "@/components/ArticleView/components/ActionButtons.jsx";
import FeedTitleFilterButton from "@/components/ArticleList/components/FeedTitleFilterButton.jsx";
import { activeArticle, filteredArticles, authState, feeds, SERVER_URL } from "./controls-state.js";
import "../../../src/index.css";
import "../../../src/compact-controls.css";

// Production components and rule/transport modules; only account and native
// network boundaries are fixtures. Never read credentials or contact a server.
await i18n.use(initReactI18next).init({ lng: "en", fallbackLng: "en", interpolation: { escapeValue: false },
  resources: { en: { translation: { common: { close: "Close", previous: "Previous", next: "Next", read: "Read", unread: "Unread", star: "Star", unstar: "Unstar", share: "Share" },
    articleView: { getFullText: "Full text", showSummary: "Show summary", aiSummarize: "AI summary" } } } } });
const initial = { id: 42, title: "当前 RSS 来源", blocklist_rules: "OldAd|旧广告", keeplist_rules: "Important",
  block_filter_entry_rules: "EntryTitle=^公告$\nEntryContent=existing", keep_filter_entry_rules: "EntryTag=科技" };
const remote = { 42: structuredClone(initial), 43: { id: 43, title: "另一条 RSS", block_filter_entry_rules: "" } };
authState.set({ userId: 7, serverUrl: SERVER_URL, authType: "token", token: "fixture-not-a-credential" });
feeds.set(Object.values(remote).map(value => structuredClone(value)));
activeArticle.set({ id: 900, feedId: 42, title: "隔离文章", url: "https://example.invalid/article", status: "read", starred: 1 });
filteredArticles.set([activeArticle.get()]);
const calls = [];
let mode = "normal", release;
window.ToolBox = { network: { request: async request => {
  const target = new URL(request.url);
  if (target.origin !== SERVER_URL || !/^\/v1\/feeds\/[0-9]+$/.test(target.pathname)) throw new Error("Unexpected fixture endpoint");
  const id = Number(target.pathname.split("/").pop());
  calls.push({ id, method: request.method, body: request.body });
  if (mode === "denied") return { status: 403, body: "{}" };
  if (mode === "slowRead" || (mode === "pauseWrite" && request.method === "PUT")) await new Promise(resolve => { release = resolve; });
  if (!remote[id]) return { status: 404, body: "{}" };
  if (request.method === "PUT") {
    if (mode === "rejectSave") return { status: 400, body: "{}" };
    Object.assign(remote[id], JSON.parse(request.body));
  }
  return { status: 200, body: JSON.stringify(remote[id]), headers: {} };
} } };
function Fixture() {
  const navigate = useNavigate();
  const { feedId, articleId } = useParams();
  useEffect(() => {
    window.filterFixture = { remote, calls, mode: value => { mode = value; },
      release: () => { mode = "normal"; release?.(); release = null; },
      account: () => authState.set({ ...authState.get(), userId: 8 }),
      go: (path, article) => { if (article) activeArticle.set(article); navigate(path); } };
    return () => { delete window.filterFixture; };
  }, [navigate]);
  return <>
    {articleId ? <ActionButtons /> : <header className="article-list-header" style={{ display: "flex", justifyContent: "space-between", padding: 8, backdropFilter: "blur(8px)" }}>
      <span>订阅列表</span><FeedTitleFilterButton feedId={feedId} />
    </header>}
    <main style={{ padding: 20, minHeight: 1300 }}><h1>真实源码过滤入口</h1><p>隔离文章内容</p></main>
  </>;
}
createRoot(document.getElementById("root")).render(<HashRouter><Routes>
  <Route path="/feed/:feedId" element={<Fixture />} />
  <Route path="/category/:categoryId/article/:articleId" element={<Fixture />} />
  <Route path="/article/:articleId" element={<Fixture />} />
</Routes></HashRouter>);
