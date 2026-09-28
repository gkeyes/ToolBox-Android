import { atom, map } from "nanostores";

// Only account/database/native boundaries are substituted. The article view,
// pull handoff, gesture, parser worker and DOM renderer are production modules.
export const rows = [1, 2, 3].map((id) => ({
  id, title: `文章 ${id}`, titleText: `文章 ${id}`, status: id === 1 ? "read" : "unread",
  url: `https://example.invalid/article/${id}`, feedId: 1,
  feed: { id: 1, title: "Fixture feed" }, published_at: "2026-09-28T08:00:00Z",
  enclosures: [], content: Array.from({ length: 240 }, (_, index) =>
    `<p>文章 ${id} 段落 ${index + 1}。${"这是用于检验文章转场和正文分批渲染的测试内容。".repeat(5)}</p>`).join(""),
}));
export const activeArticle = atom(null);
export const filteredArticles = atom(rows);
export const articleContentRevision = map({});
export const imageGalleryActive = atom(false);
export const isModalOpen = atom(false);
export const settingsState = atom({
  reduceMotion: false, floatingSidebar: false, fontSize: 16, titleFontSize: 1.5,
  titleAlignType: "left", lineHeight: 1.7, maxWidth: 75, alignJustify: false,
  fontFamily: "system-ui", feedIconShape: "circle", useGrayIcon: false,
});
export const themeState = atom({ lightTheme: "default" });
export const currentThemeMode = atom("light");
export const getAcknowledgedArticleState = () => undefined;
export const loadAccountFeedIcon = async () => null;
export const getArticleById = async (id) => rows.find((row) => String(row.id) === String(id)) ?? null;
export const handleMarkRead = async (article) => {
  filteredArticles.set(filteredArticles.get().map((row) => row.id === article.id ? { ...row, status: "read" } : row));
};
