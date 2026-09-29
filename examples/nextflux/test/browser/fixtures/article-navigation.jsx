import { createRoot } from "react-dom/client";
import { HashRouter, Route, Routes, useNavigate, useLocation } from "react-router-dom";
import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import ArticleView from "@/components/ArticleView/ArticleView.jsx";
import { aiSummaries } from "@/stores/aiStore.js";
import { rows, activeArticle, filteredArticles, settingsState, isModalOpen, imageGalleryActive } from "./article-navigation-state.js";
import "../../../src/index.css";

await i18n.use(initReactI18next).init({ lng: "zh", fallbackLng: "zh", resources: { zh: { translation: {
  articleView: { continuousReading: { nextUnread: "上拉阅读下一篇", dockReady: "松开阅读", end: "已读完" } },
  articleList: { emptyPlaceholder: "请选择文章" },
} } }, interpolation: { escapeValue: false } });
document.documentElement.dataset.theme = "light";

window.navigationFixture = {
  setReducedMotion: (value) => settingsState.set({ ...settingsState.get(), reduceMotion: value }),
  setModal: (value) => isModalOpen.set(value),
  setGallery: (value) => imageGalleryActive.set(value),
  setSummary: (id, value) => aiSummaries.set({ ...aiSummaries.get(), [id]: value }),
  setArticleContent: (id, content) => { const row = rows.find((item) => item.id === id); if (row) row.content = content; },
  reset: () => { activeArticle.set(null); filteredArticles.set(rows); },
};

function Fixture() {
  const navigate = useNavigate();
  const location = useLocation();
  return <main className="main-content flex">
    <section data-testid="list" className="w-full shrink-0 md:w-84 md:max-w-[30%] md:min-w-[18rem] h-dvh" style={{ background: "var(--background)" }}>
      {rows.map((row) => <button key={row.id} style={{ display: "block", minHeight: 64 }} onClick={() => navigate(`/article/${row.id}`)}>打开文章 {row.id}</button>)}
      <output data-testid="route">{location.pathname}</output>
    </section>
    <ArticleView />
  </main>;
}
createRoot(document.getElementById("root")).render(<HashRouter><Routes>
  <Route path="/" element={<Fixture />} />
  <Route path="/article/:articleId" element={<Fixture />} />
</Routes></HashRouter>);
